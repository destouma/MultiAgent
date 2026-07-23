import { useChatStore } from '../store/chatStore';
import { useSettingsStore } from '../store/settingsStore';

export function ModelPicker() {
  const models = useSettingsStore((state) => state.models);
  const settings = useSettingsStore((state) => state.settings);
  const conversations = useChatStore((state) => state.conversations);
  const activeConversationId = useChatStore((state) => state.activeConversationId);
  const setConversationModel = useChatStore((state) => state.setConversationModel);

  const active = conversations.find((item) => item.id === activeConversationId);
  const value = active?.model ?? settings?.model ?? '';

  return (
    <div className="field">
      <label htmlFor="model">Model</label>
      <select
        id="model"
        className="select"
        value={value}
        onChange={(event) => void setConversationModel(event.target.value)}
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
