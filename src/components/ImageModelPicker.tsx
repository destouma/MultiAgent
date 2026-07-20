import { useSettingsStore } from '../store/settingsStore';

export function ImageModelPicker() {
  const settings = useSettingsStore((state) => state.settings);
  const imageModels = useSettingsStore((state) => state.imageModels);
  const setImageModel = useSettingsStore((state) => state.setImageModel);
  const models = imageModels();

  return (
    <div className="field">
      <label htmlFor="imageModel">Image model</label>
      <select
        id="imageModel"
        className="select"
        value={settings?.imageModel ?? ''}
        onChange={(event) => void setImageModel(event.target.value)}
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
