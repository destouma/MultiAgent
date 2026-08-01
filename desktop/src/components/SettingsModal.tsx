import { useEffect, useState } from 'react';
import type { ProviderType } from '../../../shared/types';
import { useSettingsStore } from '../store/settingsStore';

const OPENAI_DEFAULT_URL = 'http://localhost:13305/api/v1';
const OLLAMA_DEFAULT_URL = 'http://localhost:11434';

export function SettingsModal() {
  const open = useSettingsStore((state) => state.settingsOpen);
  const setSettingsOpen = useSettingsStore((state) => state.setSettingsOpen);
  const settings = useSettingsStore((state) => state.settings);
  const updateSettings = useSettingsStore((state) => state.updateSettings);
  const theme = useSettingsStore((state) => state.settings?.theme ?? 'light');
  const setTheme = useSettingsStore((state) => state.setTheme);

  const [providerType, setProviderType] = useState<ProviderType>('openai');
  const [baseUrl, setBaseUrl] = useState('');
  const [apiKey, setApiKey] = useState('');
  const [maxHistory, setMaxHistory] = useState(40);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    if (open && settings) {
      setProviderType(settings.providerType);
      setBaseUrl(settings.baseUrl);
      setApiKey(settings.apiKey);
      setMaxHistory(settings.maxHistory);
    }
  }, [open, settings]);

  if (!open) return null;

  const onProviderTypeChange = (next: ProviderType) => {
    setProviderType(next);
    // Nudge the base URL to that provider's usual default, but only if the
    // field still holds the *other* provider's default untouched - never
    // clobber a URL the user actually typed.
    if (next === 'ollama' && baseUrl === OPENAI_DEFAULT_URL) {
      setBaseUrl(OLLAMA_DEFAULT_URL);
    } else if (next === 'openai' && baseUrl === OLLAMA_DEFAULT_URL) {
      setBaseUrl(OPENAI_DEFAULT_URL);
    }
  };

  const onSave = async () => {
    setSaving(true);
    try {
      await updateSettings({
        providerType,
        baseUrl: baseUrl.trim().replace(/\/$/, ''),
        apiKey: apiKey.trim() || 'lemonade',
        maxHistory: Math.max(1, Number(maxHistory) || 40),
      });
      setSettingsOpen(false);
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="modal-backdrop" onClick={() => setSettingsOpen(false)}>
      <div className="modal" onClick={(event) => event.stopPropagation()}>
        <h2>Settings</h2>
        <p className="hint">
          Connect to a local Lemonade Server, another OpenAI-compatible server (LM Studio, vLLM,
          NoLlama, ...), or a native Ollama server. Chat and image models are chosen in the top bar.
        </p>
        <div className="modal-grid">
          <div className="field">
            <label htmlFor="providerType">Server type</label>
            <select
              id="providerType"
              className="select"
              value={providerType}
              onChange={(event) => onProviderTypeChange(event.target.value as ProviderType)}
            >
              <option value="openai">OpenAI-compatible (Lemonade, NoLlama, LM Studio, ...)</option>
              <option value="ollama">Ollama</option>
            </select>
          </div>
          <div className="field">
            <label htmlFor="baseUrl">
              {providerType === 'ollama' ? 'Ollama base URL' : 'Server base URL'}
            </label>
            <input
              id="baseUrl"
              className="text-input"
              value={baseUrl}
              placeholder={providerType === 'ollama' ? OLLAMA_DEFAULT_URL : OPENAI_DEFAULT_URL}
              onChange={(event) => setBaseUrl(event.target.value)}
            />
          </div>
          {providerType === 'openai' ? (
            <div className="field">
              <label htmlFor="apiKey">API key stub</label>
              <input
                id="apiKey"
                className="text-input"
                value={apiKey}
                onChange={(event) => setApiKey(event.target.value)}
              />
            </div>
          ) : null}
          <div className="field">
            <label htmlFor="maxHistory">Max history messages</label>
            <input
              id="maxHistory"
              className="text-input"
              type="number"
              min={1}
              max={200}
              value={maxHistory}
              onChange={(event) => setMaxHistory(Number(event.target.value))}
            />
          </div>
          <div className="field">
            <label>Appearance</label>
            <label className="theme-switch">
              <input
                type="checkbox"
                checked={theme === 'dark'}
                onChange={(event) => void setTheme(event.target.checked ? 'dark' : 'light')}
              />
              <span className="theme-switch-track" />
              <span className="theme-switch-label">{theme === 'dark' ? 'Dark' : 'Light'}</span>
            </label>
          </div>
          <p className="hint">
            {providerType === 'ollama'
              ? 'Ollama has no image-generation endpoint, so image sessions are disabled for this provider.'
              : 'Image models use the /images/generations endpoint.'}
          </p>
        </div>
        <div className="modal-actions">
          <button type="button" className="btn btn-ghost" onClick={() => setSettingsOpen(false)}>
            Cancel
          </button>
          <button
            type="button"
            className="btn btn-primary"
            disabled={saving || !baseUrl.trim()}
            onClick={() => void onSave()}
          >
            Save
          </button>
        </div>
      </div>
    </div>
  );
}
