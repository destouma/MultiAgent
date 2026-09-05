import { useEffect, useState } from 'react';
import type { HealthStatus, ModelInfo, ServerProfile } from '../../../shared/types';
import { isLikelyImageModel } from '../../../shared/types';
import { cleanErrorMessage } from '../lib/errors';
import { useSettingsStore } from '../store/settingsStore';

type ServerModelsState = {
  health: HealthStatus | null;
  models: ModelInfo[];
  loadedModels: string[];
  loadStatusSupported: boolean;
  loading: boolean;
  error: string | null;
};

const EMPTY_STATE: ServerModelsState = {
  health: null,
  models: [],
  loadedModels: [],
  loadStatusSupported: true,
  loading: false,
  error: null,
};

function dotClass(health: HealthStatus | null, loading: boolean): string {
  if (loading || !health) return 'checking';
  return health.ok ? 'ok' : 'bad';
}

export function ModelsModal() {
  const open = useSettingsStore((state) => state.modelsOpen);
  const setModelsOpen = useSettingsStore((state) => state.setModelsOpen);
  const settings = useSettingsStore((state) => state.settings);

  const [byServer, setByServer] = useState<Record<string, ServerModelsState>>({});
  const [loadingKey, setLoadingKey] = useState<string | null>(null);

  const servers = settings?.servers ?? [];

  const refreshServer = (server: ServerProfile) => {
    setByServer((prev) => ({
      ...prev,
      [server.id]: { ...(prev[server.id] ?? EMPTY_STATE), loading: true, error: null },
    }));

    // Health is fetched independently of models/loaded-models: checkHealth()
    // always resolves (even to { ok: false }), but listModels()/
    // listLoadedModels() reject outright when the server is unreachable.
    // Bundling all three into one Promise.all would let a models-fetch
    // rejection wipe out a perfectly good health result, leaving the dot
    // stuck on "checking" forever for an offline server.
    void window.api.checkHealthForServer(server.id).then((health) => {
      setByServer((prev) => ({
        ...prev,
        [server.id]: { ...(prev[server.id] ?? EMPTY_STATE), health },
      }));
    });

    void Promise.all([
      window.api.listModelsForServer(server.id),
      window.api.listLoadedModelsForServer(server.id),
    ])
      .then(([models, loaded]) => {
        setByServer((prev) => ({
          ...prev,
          [server.id]: {
            ...(prev[server.id] ?? EMPTY_STATE),
            models,
            loadedModels: loaded.names,
            loadStatusSupported: loaded.supported,
            loading: false,
            error: null,
          },
        }));
      })
      .catch((err: unknown) => {
        setByServer((prev) => ({
          ...prev,
          [server.id]: {
            ...(prev[server.id] ?? EMPTY_STATE),
            models: [],
            loadedModels: [],
            loading: false,
            error: cleanErrorMessage(err, 'Failed to load models'),
          },
        }));
      });
  };

  const refreshAll = () => {
    for (const server of servers) refreshServer(server);
  };

  useEffect(() => {
    if (!open) return;
    refreshAll();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  if (!open) return null;

  const onLoad = (server: ServerProfile, modelId: string) => {
    const key = `${server.id}:${modelId}`;
    setLoadingKey(key);
    void window.api
      .loadModelForServer(server.id, modelId)
      .then(() => refreshServer(server))
      .catch((err: unknown) => {
        setByServer((prev) => ({
          ...prev,
          [server.id]: {
            ...(prev[server.id] ?? EMPTY_STATE),
            error: cleanErrorMessage(err, `Failed to load ${modelId}`),
          },
        }));
      })
      .finally(() => setLoadingKey(null));
  };

  return (
    <div className="modal-backdrop" onClick={() => !loadingKey && setModelsOpen(false)}>
      <div className="modal models-modal" onClick={(event) => event.stopPropagation()}>
        <h2>Models</h2>
        <p className="hint">
          Models available from each of your saved servers. Loaded models are ready for chat or
          image generation; load any model that is not yet in memory.
        </p>

        <div className="models-modal-toolbar">
          <span className="models-modal-status">
            {servers.length} server{servers.length === 1 ? '' : 's'}
          </span>
          <button type="button" className="btn" disabled={Boolean(loadingKey)} onClick={refreshAll}>
            Refresh all
          </button>
        </div>

        <div className="models-list">
          {!servers.length ? (
            <p className="hint">No servers saved yet — add one in Settings.</p>
          ) : (
            servers.map((server) => {
              const state = byServer[server.id] ?? EMPTY_STATE;
              // The generic OpenAI-compatible provider (NoLlama, LM Studio, vLLM,
              // real OpenAI, ...) has no on-demand loading endpoint — its models
              // are whatever the server itself already loaded at startup, so
              // ensureModelLoaded() is a no-op there. Only Lemonade and Ollama
              // support asking the server to load a model.
              const canLoadOnDemand = server.providerType !== 'openai';
              const loadedSet = new Set(state.loadedModels.map((name) => name.toLowerCase()));
              const sorted = [...state.models].sort((a, b) => a.id.localeCompare(b.id));
              const isDefault = server.id === settings?.activeServerId;

              return (
                <div key={server.id} className="models-server-group">
                  <div className="models-server-header">
                    <span
                      className={`server-status-dot ${dotClass(state.health, state.loading)}`}
                      title={
                        state.loading
                          ? 'Checking…'
                          : state.health?.ok
                            ? `Online${state.health.latencyMs != null ? ` · ${state.health.latencyMs}ms` : ''}`
                            : (state.health?.message ?? 'Unknown')
                      }
                      aria-hidden
                    />
                    <span className="models-server-name">{server.name}</span>
                    {isDefault ? <span className="models-tag accent">default</span> : null}
                    <span className="models-modal-status models-server-count">
                      {state.loading
                        ? 'Checking…'
                        : !state.health?.ok
                          ? 'offline'
                          : state.loadStatusSupported
                            ? `${sorted.length} available · ${state.loadedModels.length} loaded`
                            : `${sorted.length} available · load status not reported`}
                    </span>
                  </div>

                  {state.error ? <p className="error-banner">{state.error}</p> : null}

                  {state.health?.ok && !sorted.length && !state.loading ? (
                    <p className="hint">No models reported by this server yet.</p>
                  ) : null}

                  {sorted.map((model) => {
                    const loaded = loadedSet.has(model.id.toLowerCase());
                    const isLoading = loadingKey === `${server.id}:${model.id}`;
                    const kind = isLikelyImageModel(model.id) ? 'image' : 'chat';
                    const statusText = state.loadStatusSupported
                      ? loaded
                        ? 'loaded'
                        : 'not loaded'
                      : 'status unknown';
                    return (
                      <div
                        key={model.id}
                        className={`models-row ${state.loadStatusSupported && loaded ? 'loaded' : ''}`}
                      >
                        <div className="models-row-meta">
                          <span className="models-row-name">{model.id}</span>
                          <span className="models-row-tags">
                            <span className="models-tag">{kind}</span>
                            <span
                              className={`models-tag ${state.loadStatusSupported ? (loaded ? 'ok' : 'muted') : 'muted'}`}
                              title={
                                state.loadStatusSupported
                                  ? undefined
                                  : 'This server does not report which models are loaded.'
                              }
                            >
                              {statusText}
                            </span>
                          </span>
                        </div>
                        <div className="models-row-actions">
                          {canLoadOnDemand && !loaded ? (
                            <button
                              type="button"
                              className="btn btn-primary"
                              disabled={Boolean(loadingKey)}
                              onClick={() => onLoad(server, model.id)}
                            >
                              {isLoading ? 'Loading…' : 'Load'}
                            </button>
                          ) : null}
                        </div>
                      </div>
                    );
                  })}
                </div>
              );
            })
          )}
        </div>

        <div className="modal-actions">
          <button
            type="button"
            className="btn btn-primary"
            disabled={Boolean(loadingKey)}
            onClick={() => setModelsOpen(false)}
          >
            Close
          </button>
        </div>
      </div>
    </div>
  );
}
