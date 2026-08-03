import { useEffect, useState } from 'react';
import { isLikelyImageModel, type ModelInfo } from '../../../shared/types';
import { cleanErrorMessage } from '../lib/errors';
import { useChatStore, type ChatStoreHook } from '../store/chatStore';
import { useSettingsStore } from '../store/settingsStore';
import { ModelSelect } from './ModelSelect';

type Props = {
  kind?: 'chat' | 'image';
  store?: ChatStoreHook;
};

export function ModelPicker({ kind = 'chat', store = useChatStore }: Props) {
  const globalModels = useSettingsStore((state) => state.models);
  const globalModelsError = useSettingsStore((state) => state.modelsError);
  const globalLoadedModels = useSettingsStore((state) => state.loadedModels);
  const globalLoadStatusSupported = useSettingsStore((state) => state.loadStatusSupported);
  const refreshGlobalModels = useSettingsStore((state) => state.refreshModels);
  const refreshGlobalLoadedModels = useSettingsStore((state) => state.refreshLoadedModels);
  const settings = useSettingsStore((state) => state.settings);
  const conversations = store((state) => state.conversations);
  const activeConversationId = store((state) => state.activeConversationId);
  const setConversationModel = store((state) => state.setConversationModel);

  const active = conversations.find((item) => item.id === activeConversationId);
  const serverId = active?.serverId ?? null;

  // A conversation pinned to a specific saved server has its own model list,
  // independent of the global active-connection list the rest of the app
  // (Settings/Models modal, connection badge) reads from.
  const [serverModels, setServerModels] = useState<ModelInfo[]>([]);
  const [serverModelsError, setServerModelsError] = useState<string | null>(null);
  const [serverLoadedModels, setServerLoadedModels] = useState<string[]>([]);
  const [serverLoadStatusSupported, setServerLoadStatusSupported] = useState(true);

  const refreshServerModels = (id: string) => {
    void window.api
      .listModelsForServer(id)
      .then((result) => {
        setServerModels(result);
        setServerModelsError(null);
      })
      .catch((err) => {
        setServerModels([]);
        setServerModelsError(cleanErrorMessage(err, 'Failed to load models'));
      });
  };

  const refreshServerLoadedModels = (id: string) => {
    void window.api
      .listLoadedModelsForServer(id)
      .then((result) => {
        setServerLoadedModels(result.names);
        setServerLoadStatusSupported(result.supported);
      })
      .catch(() => setServerLoadedModels([]));
  };

  useEffect(() => {
    if (!serverId) return;
    refreshServerModels(serverId);
    refreshServerLoadedModels(serverId);
  }, [serverId]);

  const models = serverId ? serverModels : globalModels;
  const modelsError = serverId ? serverModelsError : globalModelsError;
  const loadedModels = serverId ? serverLoadedModels : globalLoadedModels;
  const loadStatusSupported = serverId ? serverLoadStatusSupported : globalLoadStatusSupported;
  const refreshModels = () =>
    serverId ? refreshServerModels(serverId) : void refreshGlobalModels();
  const refreshLoadedModels = () =>
    serverId ? refreshServerLoadedModels(serverId) : void refreshGlobalLoadedModels();

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
        onOpen={() => refreshLoadedModels()}
      />
      <button
        type="button"
        className="btn model-picker-refresh"
        title={modelsError ? `Refresh models — ${modelsError}` : 'Refresh models'}
        onClick={() => {
          refreshModels();
          refreshLoadedModels();
        }}
      >
        {modelsError ? '⚠ Retry' : '⟳'}
      </button>
      {modelsError ? <span className="model-picker-error">{modelsError}</span> : null}
    </div>
  );
}
