import { useEffect, useState } from 'react';
import type { ProviderType, ServerProfile, ThemeMode } from '../../../shared/types';
import { useSettingsStore } from '../store/settingsStore';

const THEME_OPTIONS: Array<{ value: ThemeMode; label: string }> = [
  { value: 'light', label: 'Light' },
  { value: 'dark', label: 'Dark' },
  { value: 'terminal', label: 'Green Terminal' },
];

const LEMONADE_DEFAULT_URL = 'http://localhost:13305/api/v1';
const OLLAMA_DEFAULT_URL = 'http://localhost:11434';
const OPENAI_URL_PLACEHOLDER = 'http://localhost:8000/v1';
const KNOWN_DEFAULT_URLS = [LEMONADE_DEFAULT_URL, OLLAMA_DEFAULT_URL];

type ServerFormState = {
  editingId: string | null;
  name: string;
  providerType: ProviderType;
  baseUrl: string;
  apiKey: string;
  maxHistory: number;
};

function emptyForm(): ServerFormState {
  return {
    editingId: null,
    name: '',
    providerType: 'lemonade',
    baseUrl: LEMONADE_DEFAULT_URL,
    apiKey: 'local-llm',
    maxHistory: 40,
  };
}

function providerLabel(type: ProviderType): string {
  if (type === 'ollama') return 'Ollama';
  if (type === 'lemonade') return 'Lemonade';
  return 'OpenAI-compatible';
}

function formFromProfile(profile: ServerProfile): ServerFormState {
  return {
    editingId: profile.id,
    name: profile.name,
    providerType: profile.providerType,
    baseUrl: profile.baseUrl,
    apiKey: profile.apiKey,
    maxHistory: profile.maxHistory,
  };
}

export function SettingsModal() {
  const open = useSettingsStore((state) => state.settingsOpen);
  const setSettingsOpen = useSettingsStore((state) => state.setSettingsOpen);
  const settings = useSettingsStore((state) => state.settings);
  const theme = useSettingsStore((state) => state.settings?.theme ?? 'light');
  const setTheme = useSettingsStore((state) => state.setTheme);
  const addServer = useSettingsStore((state) => state.addServer);
  const updateServerProfile = useSettingsStore((state) => state.updateServerProfile);
  const removeServer = useSettingsStore((state) => state.removeServer);
  const activateServer = useSettingsStore((state) => state.activateServer);

  const [form, setForm] = useState<ServerFormState | null>(null);
  const [saving, setSaving] = useState(false);
  const [switchingId, setSwitchingId] = useState<string | null>(null);

  useEffect(() => {
    if (!open) setForm(null);
  }, [open]);

  if (!open || !settings) return null;

  const servers = settings.servers;

  const onProviderTypeChange = (next: ProviderType) => {
    if (!form) return;
    // Nudge the base URL to that provider's usual default, but only if the
    // field still holds a *known* default untouched - never clobber a URL
    // the user actually typed. Generic OpenAI-compatible servers have no
    // single default, so switching to it just clears a known default.
    const nextUrl = !KNOWN_DEFAULT_URLS.includes(form.baseUrl)
      ? form.baseUrl
      : next === 'ollama'
        ? OLLAMA_DEFAULT_URL
        : next === 'lemonade'
          ? LEMONADE_DEFAULT_URL
          : '';
    setForm({ ...form, providerType: next, baseUrl: nextUrl });
  };

  const onSaveForm = async () => {
    if (!form) return;
    setSaving(true);
    try {
      const profile = {
        name: form.name.trim() || (form.providerType === 'ollama' ? 'Ollama' : 'Local LLM'),
        providerType: form.providerType,
        baseUrl: form.baseUrl.trim().replace(/\/$/, ''),
        apiKey: form.apiKey.trim() || 'local-llm',
        maxHistory: Math.max(1, Number(form.maxHistory) || 40),
      };
      if (form.editingId) {
        await updateServerProfile(form.editingId, profile);
      } else {
        await addServer(profile);
      }
      setForm(null);
    } finally {
      setSaving(false);
    }
  };

  const onUse = async (id: string) => {
    setSwitchingId(id);
    try {
      await activateServer(id);
    } finally {
      setSwitchingId(null);
    }
  };

  const onDelete = async (server: ServerProfile) => {
    if (!window.confirm(`Remove "${server.name}"? This only removes it from this list.`)) return;
    await removeServer(server.id);
  };

  return (
    <div className="modal-backdrop" onClick={() => setSettingsOpen(false)}>
      <div className="modal settings-modal" onClick={(event) => event.stopPropagation()}>
        <h2>Settings</h2>
        <p className="hint">
          Save connections for Lemonade, any other OpenAI-compatible server (NoLlama, LM Studio,
          vLLM, ...), or a native Ollama server, then switch between them here. Chat and image
          models are chosen in the top bar.
        </p>

        <div className="server-list">
          {!servers.length ? (
            <p className="hint">No servers saved yet. Add one below.</p>
          ) : (
            servers.map((server) => {
              const isActive = server.id === settings.activeServerId;
              return (
                <div key={server.id} className={`server-row ${isActive ? 'active' : ''}`}>
                  <div className="server-row-meta">
                    <span className="server-row-name">{server.name}</span>
                    <span className="server-row-detail">
                      <span className="models-tag">{providerLabel(server.providerType)}</span>
                      <span className="server-row-url">{server.baseUrl}</span>
                    </span>
                  </div>
                  <div className="server-row-actions">
                    {isActive ? (
                      <span className="server-row-active-label">Active</span>
                    ) : (
                      <button
                        type="button"
                        className="btn"
                        disabled={switchingId === server.id}
                        onClick={() => void onUse(server.id)}
                      >
                        {switchingId === server.id ? 'Switching…' : 'Use'}
                      </button>
                    )}
                    <button
                      type="button"
                      className="btn"
                      onClick={() => setForm(formFromProfile(server))}
                    >
                      Edit
                    </button>
                    <button
                      type="button"
                      className="btn btn-ghost"
                      onClick={() => void onDelete(server)}
                    >
                      Delete
                    </button>
                  </div>
                </div>
              );
            })
          )}
        </div>

        {form ? (
          <div className="server-form modal-grid">
            <div className="field">
              <label htmlFor="serverName">Name</label>
              <input
                id="serverName"
                className="text-input"
                value={form.name}
                placeholder="e.g. NoLlama (NPU)"
                onChange={(event) => setForm({ ...form, name: event.target.value })}
              />
            </div>
            <div className="field">
              <label htmlFor="serverProviderType">Server type</label>
              <select
                id="serverProviderType"
                className="select"
                value={form.providerType}
                onChange={(event) => onProviderTypeChange(event.target.value as ProviderType)}
              >
                <option value="lemonade">Lemonade</option>
                <option value="openai">OpenAI-compatible (NoLlama, LM Studio, vLLM, ...)</option>
                <option value="ollama">Ollama</option>
              </select>
            </div>
            <div className="field">
              <label htmlFor="serverBaseUrl">
                {form.providerType === 'ollama'
                  ? 'Ollama base URL'
                  : form.providerType === 'lemonade'
                    ? 'Lemonade base URL'
                    : 'Server base URL'}
              </label>
              <input
                id="serverBaseUrl"
                className="text-input"
                value={form.baseUrl}
                placeholder={
                  form.providerType === 'ollama'
                    ? OLLAMA_DEFAULT_URL
                    : form.providerType === 'lemonade'
                      ? LEMONADE_DEFAULT_URL
                      : OPENAI_URL_PLACEHOLDER
                }
                onChange={(event) => setForm({ ...form, baseUrl: event.target.value })}
              />
            </div>
            {form.providerType !== 'ollama' ? (
              <div className="field">
                <label htmlFor="serverApiKey">API key stub</label>
                <input
                  id="serverApiKey"
                  className="text-input"
                  value={form.apiKey}
                  onChange={(event) => setForm({ ...form, apiKey: event.target.value })}
                />
              </div>
            ) : null}
            <div className="field">
              <label htmlFor="serverMaxHistory">Max history messages</label>
              <input
                id="serverMaxHistory"
                className="text-input"
                type="number"
                min={1}
                max={200}
                value={form.maxHistory}
                onChange={(event) => setForm({ ...form, maxHistory: Number(event.target.value) })}
              />
            </div>
            <div className="server-form-actions">
              <button type="button" className="btn btn-ghost" onClick={() => setForm(null)}>
                Cancel
              </button>
              <button
                type="button"
                className="btn btn-primary"
                disabled={saving || !form.baseUrl.trim()}
                onClick={() => void onSaveForm()}
              >
                {form.editingId ? 'Save changes' : 'Add server'}
              </button>
            </div>
          </div>
        ) : (
          <button type="button" className="btn" onClick={() => setForm(emptyForm())}>
            Add server
          </button>
        )}

        <div className="modal-grid">
          <div className="field">
            <label htmlFor="theme">Appearance</label>
            <select
              id="theme"
              className="select"
              value={theme}
              onChange={(event) => void setTheme(event.target.value as ThemeMode)}
            >
              {THEME_OPTIONS.map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </select>
          </div>
          <p className="hint">
            {settings.providerType === 'ollama'
              ? 'Ollama has no image-generation endpoint, so image sessions are disabled for this provider.'
              : 'Image models use the /images/generations endpoint.'}
          </p>
        </div>

        <div className="modal-actions">
          <button type="button" className="btn btn-primary" onClick={() => setSettingsOpen(false)}>
            Close
          </button>
        </div>
      </div>
    </div>
  );
}
