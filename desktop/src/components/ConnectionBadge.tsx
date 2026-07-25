import { useSettingsStore } from '../store/settingsStore';

export function ConnectionBadge() {
  const health = useSettingsStore((state) => state.health);
  const refreshHealth = useSettingsStore((state) => state.refreshHealth);

  const ok = Boolean(health?.ok);
  const label = health?.ok
    ? `Connected${health.latencyMs != null ? ` · ${health.latencyMs}ms` : ''}`
    : health?.message || 'Offline';

  return (
    <button
      type="button"
      className={`badge ${ok ? 'ok' : 'bad'}`}
      onClick={() => void refreshHealth()}
      title="Click to refresh connection"
    >
      <span className="badge-dot" />
      {label}
    </button>
  );
}
