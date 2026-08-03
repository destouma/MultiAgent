import { useEffect, useRef, useState } from 'react';
import type { HealthStatus } from '../../../shared/types';
import { useChatStore, type ChatStoreHook } from '../store/chatStore';
import { useSettingsStore } from '../store/settingsStore';

type Props = {
  store?: ChatStoreHook;
};

function dotClassFor(status: HealthStatus | undefined, checking: boolean): string {
  if (checking || !status) return 'checking';
  return status.ok ? 'ok' : 'bad';
}

function dotLabelFor(status: HealthStatus | undefined, checking: boolean): string {
  if (checking || !status) return 'Checking…';
  return status.ok
    ? `Online${status.latencyMs != null ? ` · ${status.latencyMs}ms` : ''}`
    : status.message;
}

export function ServerPicker({ store = useChatStore }: Props) {
  // Select the stable `settings` object itself, not `settings?.servers ?? []`
  // — that fallback creates a brand-new array on every read while `settings`
  // is still null (e.g. before settingsStore.load() resolves on startup),
  // which defeats useSyncExternalStore's reference check and causes an
  // infinite render loop ("Maximum update depth exceeded").
  const settings = useSettingsStore((state) => state.settings);
  const globalHealth = useSettingsStore((state) => state.health);
  const conversations = store((state) => state.conversations);
  const activeConversationId = store((state) => state.activeConversationId);
  const setConversationServer = store((state) => state.setConversationServer);

  const rootRef = useRef<HTMLDivElement | null>(null);
  const [open, setOpen] = useState(false);
  const [health, setHealth] = useState<Record<string, HealthStatus | undefined>>({});
  const [checking, setChecking] = useState<Set<string>>(new Set());

  const servers = settings?.servers ?? [];
  const serverIdsKey = servers.map((server) => server.id).join(',');

  useEffect(() => {
    for (const server of servers) {
      setChecking((prev) => new Set(prev).add(server.id));
      void window.api
        .checkHealthForServer(server.id)
        .then((result) => setHealth((prev) => ({ ...prev, [server.id]: result })))
        .catch(() =>
          setHealth((prev) => ({ ...prev, [server.id]: { ok: false, message: 'Check failed' } })),
        )
        .finally(() =>
          setChecking((prev) => {
            const next = new Set(prev);
            next.delete(server.id);
            return next;
          }),
        );
    }
    // Re-check only when the actual set of server ids changes, not on every render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [serverIdsKey]);

  useEffect(() => {
    if (!open) return;
    const onPointerDown = (event: MouseEvent) => {
      if (!rootRef.current?.contains(event.target as Node)) setOpen(false);
    };
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setOpen(false);
    };
    window.addEventListener('mousedown', onPointerDown);
    window.addEventListener('keydown', onKeyDown);
    return () => {
      window.removeEventListener('mousedown', onPointerDown);
      window.removeEventListener('keydown', onKeyDown);
    };
  }, [open]);

  if (!servers.length) return null;

  const active = conversations.find((item) => item.id === activeConversationId);
  const value = active?.serverId ?? '';
  const selectedServer = servers.find((server) => server.id === value) ?? null;

  const defaultDotClass = globalHealth ? (globalHealth.ok ? 'ok' : 'bad') : 'checking';
  const defaultDotLabel = globalHealth
    ? globalHealth.ok
      ? `Online${globalHealth.latencyMs != null ? ` · ${globalHealth.latencyMs}ms` : ''}`
      : globalHealth.message
    : 'Checking…';

  const selectedDotClass = value
    ? dotClassFor(health[value], checking.has(value))
    : defaultDotClass;
  const selectedDotLabel = value
    ? dotLabelFor(health[value], checking.has(value))
    : defaultDotLabel;

  const selectServer = (serverId: string | null) => {
    void setConversationServer(serverId);
    setOpen(false);
  };

  return (
    <div className="field model-select server-picker" ref={rootRef}>
      <label htmlFor="server-picker-select">Server</label>
      <button
        type="button"
        id="server-picker-select"
        className="select model-select-trigger"
        aria-haspopup="listbox"
        aria-expanded={open}
        onClick={() => setOpen((current) => !current)}
      >
        <span
          className={`server-status-dot ${selectedDotClass}`}
          title={selectedDotLabel}
          aria-hidden
        />
        <span className="model-select-value">{selectedServer?.name ?? 'Default'}</span>
        <span className="model-select-caret" aria-hidden>
          ▾
        </span>
      </button>

      {open ? (
        <div className="model-select-menu" role="listbox" aria-labelledby="server-picker-select">
          <button
            type="button"
            role="option"
            aria-selected={!value}
            className={`model-select-option ${!value ? 'active' : ''}`}
            onClick={() => selectServer(null)}
          >
            <span
              className={`server-status-dot ${defaultDotClass}`}
              title={defaultDotLabel}
              aria-hidden
            />
            <span className="model-select-option-name">Default</span>
          </button>
          {servers.map((server) => (
            <button
              key={server.id}
              type="button"
              role="option"
              aria-selected={server.id === value}
              className={`model-select-option ${server.id === value ? 'active' : ''}`}
              onClick={() => selectServer(server.id)}
            >
              <span
                className={`server-status-dot ${dotClassFor(health[server.id], checking.has(server.id))}`}
                title={dotLabelFor(health[server.id], checking.has(server.id))}
                aria-hidden
              />
              <span className="model-select-option-name">{server.name}</span>
            </button>
          ))}
        </div>
      ) : null}
    </div>
  );
}
