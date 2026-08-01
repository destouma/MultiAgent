import { isLikelyImageModel } from '../../../shared/types';
import { useChatStore } from '../store/chatStore';
import { useSettingsStore } from '../store/settingsStore';
import { ModelSelect } from './ModelSelect';

type Props = {
  kind?: 'chat' | 'image';
};

export function ModelPicker({ kind = 'chat' }: Props) {
  const models = useSettingsStore((state) => state.models);
  const modelsError = useSettingsStore((state) => state.modelsError);
  const loadedModels = useSettingsStore((state) => state.loadedModels);
  const loadStatusSupported = useSettingsStore((state) => state.loadStatusSupported);
  const refreshModels = useSettingsStore((state) => state.refreshModels);
  const refreshLoadedModels = useSettingsStore((state) => state.refreshLoadedModels);
  const settings = useSettingsStore((state) => state.settings);
  const conversations = useChatStore((state) => state.conversations);
  const activeConversationId = useChatStore((state) => state.activeConversationId);
  const setConversationModel = useChatStore((state) => state.setConversationModel);

  const active = conversations.find((item) => item.id === activeConversationId);
  const filtered =
    kind === 'image'
      ? (() => {
          const imageOnly = models.filter((model) => isLikelyImageModel(model.id));
          return imageOnly.length ? imageOnly : models;
        })()
      : models;

  const fallback =
    kind === 'image' ? (settings?.imageModel ?? settings?.model ?? '') : (settings?.model ?? '');
  const value = active?.model ?? fallback;
  const resolvedValue = filtered.some((model) => model.id === value)
    ? value
    : (filtered[0]?.id ?? '');

  return (
    <div className="model-picker">
      <ModelSelect
        id={kind === 'image' ? 'image-model' : 'model'}
        label="Model"
        value={resolvedValue}
        models={filtered}
        loadedModels={loadedModels}
        loadStatusSupported={loadStatusSupported}
        disabled={!filtered.length}
        onChange={(modelId) => void setConversationModel(modelId)}
        onOpen={() => void refreshLoadedModels()}
      />
      <button
        type="button"
        className="btn model-picker-refresh"
        title={modelsError ? `Refresh models — ${modelsError}` : 'Refresh models'}
        onClick={() => {
          void refreshModels();
          void refreshLoadedModels();
        }}
      >
        {modelsError ? '⚠ Retry' : '⟳'}
      </button>
      {modelsError ? <span className="model-picker-error">{modelsError}</span> : null}
    </div>
  );
}
