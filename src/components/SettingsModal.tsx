import { useEffect, useState } from 'react';
import { useSettingsStore } from '../store/settingsStore';

export function SettingsModal() {
  const open = useSettingsStore((state) => state.settingsOpen);
  const setSettingsOpen = useSettingsStore((state) => state.setSettingsOpen);
  const settings = useSettingsStore((state) => state.settings);
  const updateSettings = useSettingsStore((state) => state.updateSettings);

  const [baseUrl, setBaseUrl] = useState('');
  const [apiKey, setApiKey] = useState('');
  const [maxHistory, setMaxHistory] = useState(40);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    if (open && settings) {
      setBaseUrl(settings.baseUrl);
      setApiKey(settings.apiKey);
      setMaxHistory(settings.maxHistory);
    }
  }, [open, settings]);

  if (!open) return null;

  const onSave = async () => {
    setSaving(true);
    try {
      await updateSettings({
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
          Point the app at your local Lemonade Server. Default base URL is{' '}
          <code>http://localhost:13305/api/v1</code>. The API key is required by the OpenAI client
          but unused by Lemonade.
        </p>
        <div className="modal-grid">
          <div className="field">
            <label htmlFor="baseUrl">Lemonade base URL</label>
            <input
              id="baseUrl"
              className="text-input"
              value={baseUrl}
              onChange={(event) => setBaseUrl(event.target.value)}
            />
          </div>
          <div className="field">
            <label htmlFor="apiKey">API key stub</label>
            <input
              id="apiKey"
              className="text-input"
              value={apiKey}
              onChange={(event) => setApiKey(event.target.value)}
            />
          </div>
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
          <p className="hint">
            Chat model and image model are chosen in the top bar. Image models use Lemonade{' '}
            <code>/images/generations</code>.
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
