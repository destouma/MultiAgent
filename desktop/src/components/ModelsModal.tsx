import { useEffect, useState } from 'react';
import { isLikelyImageModel } from '../../../shared/types';
import { useChatStore } from '../store/chatStore';
import { useSettingsStore } from '../store/settingsStore';

function cleanErrorMessage(error: unknown, fallback: string): string {
  const message = error instanceof Error ? error.message : fallback;
  return message.replace(/^Error invoking remote method '[^']*':\s*/, '');
}

export function ModelsModal() {
  const open = useSettingsStore((state) => state.modelsOpen);
  const setModelsOpen = useSettingsStore((state) => state.setModelsOpen);
  const models = useSettingsStore((state) => state.models);
  const loadedModels = useSettingsStore((state) => state.loadedModels);
  const loadingModelId = useSettingsStore((state) => state.loadingModelId);
  const health = useSettingsStore((state) => state.health);
  const refreshModels = useSettingsStore((state) => state.refreshModels);
  const refreshLoadedModels = useSettingsStore((state) => state.refreshLoadedModels);
  const loadModel = useSettingsStore((state) => state.loadModel);
  const modelStatus = useChatStore((state) => state.modelStatus);

  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const loadedSet = new Set(loadedModels.map((name) => name.toLowerCase()));

  useEffect(() => {
    if (!open) return;
    setError(null);
    setRefreshing(true);
    void Promise.all([refreshModels(), refreshLoadedModels()])
      .catch((err: unknown) => {
        setError(cleanErrorMessage(err, 'Failed to refresh models'));
      })
      .finally(() => setRefreshing(false));
  }, [open, refreshLoadedModels, refreshModels]);

  if (!open) return null;

  const sorted = [...models].sort((a, b) => a.id.localeCompare(b.id));

  return (
    <div
      className="modal-backdrop"
      onClick={() => {
        if (!loadingModelId) setModelsOpen(false);
      }}
    >
      <div className="modal models-modal" onClick={(event) => event.stopPropagation()}>
        <h2>Models</h2>
        <p className="hint">
          Models available from Lemonade. Loaded models are ready for chat or image generation.
          Load any model that is not yet in memory.
        </p>

        <div className="models-modal-toolbar">
          <span className="models-modal-status">
            {health?.ok ? `${sorted.length} available · ${loadedModels.length} loaded` : 'Offline'}
            {modelStatus ? ` · ${modelStatus}` : ''}
          </span>
          <button
            type="button"
            className="btn"
            disabled={refreshing || Boolean(loadingModelId)}
            onClick={() => {
              setRefreshing(true);
              setError(null);
              void Promise.all([refreshModels(), refreshLoadedModels()])
                .catch((err: unknown) => {
                  setError(cleanErrorMessage(err, 'Failed to refresh models'));
                })
                .finally(() => setRefreshing(false));
            }}
          >
            {refreshing ? 'Refreshing…' : 'Refresh'}
          </button>
        </div>

        {error ? <p className="error-banner">{error}</p> : null}

        <div className="models-list">
          {!sorted.length ? (
            <p className="hint">
              {health?.ok
                ? 'No models reported by Lemonade yet.'
                : 'Connect to Lemonade to list models.'}
            </p>
          ) : (
            sorted.map((model) => {
              const loaded = loadedSet.has(model.id.toLowerCase());
              const isLoading = loadingModelId === model.id;
              const kind = isLikelyImageModel(model.id) ? 'image' : 'chat';
              return (
                <div key={model.id} className={`models-row ${loaded ? 'loaded' : ''}`}>
                  <div className="models-row-meta">
                    <span className="models-row-name">{model.id}</span>
                    <span className="models-row-tags">
                      <span className="models-tag">{kind}</span>
                      <span className={`models-tag ${loaded ? 'ok' : 'muted'}`}>
                        {loaded ? 'loaded' : 'not loaded'}
                      </span>
                    </span>
                  </div>
                  <div className="models-row-actions">
                    {!loaded ? (
                      <button
                        type="button"
                        className="btn btn-primary"
                        disabled={Boolean(loadingModelId)}
                        onClick={() => {
                          setError(null);
                          void loadModel(model.id).catch((err: unknown) => {
                            setError(cleanErrorMessage(err, `Failed to load ${model.id}`));
                          });
                        }}
                      >
                        {isLoading ? 'Loading…' : 'Load'}
                      </button>
                    ) : null}
                  </div>
                </div>
              );
            })
          )}
        </div>

        <div className="modal-actions">
          <button
            type="button"
            className="btn btn-primary"
            disabled={Boolean(loadingModelId)}
            onClick={() => setModelsOpen(false)}
          >
            Close
          </button>
        </div>
      </div>
    </div>
  );
}
