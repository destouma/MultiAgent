import { useSettingsStore } from '../store/settingsStore';

export function ModelPicker() {
  const models = useSettingsStore((state) => state.models);
  const settings = useSettingsStore((state) => state.settings);
  const setModel = useSettingsStore((state) => state.setModel);

  return (
    <div className="field">
      <label htmlFor="model">Model</label>
      <select
        id="model"
        className="select"
        value={settings?.model ?? ''}
        onChange={(event) => void setModel(event.target.value)}
        disabled={!models.length}
      >
        {!models.length && <option value="">No models</option>}
        {models.map((model) => (
          <option key={model.id} value={model.id}>
            {model.id}
          </option>
        ))}
      </select>
    </div>
  );
}
