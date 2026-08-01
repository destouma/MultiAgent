import { OpenAiClient } from './openAiClient';
import { ProviderError } from './types';

type LemonadeHealthResponse = {
  status?: string;
  all_models_loaded?: Array<{
    model_name?: string;
    type?: string;
  }>;
};

const MODEL_LOAD_TIMEOUT_MS = 10 * 60 * 1000;
const MODEL_POLL_INTERVAL_MS = 1500;

function sleep(ms: number, signal?: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    if (signal?.aborted) {
      reject(new ProviderError('cancelled', 'Generation cancelled'));
      return;
    }
    const timer = setTimeout(() => {
      signal?.removeEventListener('abort', onAbort);
      resolve();
    }, ms);
    const onAbort = () => {
      clearTimeout(timer);
      reject(new ProviderError('cancelled', 'Generation cancelled'));
    };
    signal?.addEventListener('abort', onAbort, { once: true });
  });
}

/**
 * Client for Lemonade Server specifically. Inherits the standard OpenAI
 * chat/completion/image behavior from OpenAiClient and adds Lemonade's
 * non-standard /health + /load extension on top, for real load-status
 * reporting and explicit model preloading before inference.
 */
export class LemonadeClient extends OpenAiClient {
  // Optimistic until a probe proves the server is unreachable, so the UI
  // doesn't flash "unknown" before the first check completes.
  private loadStatusSupported = true;

  private async tryGetServerHealth(signal?: AbortSignal): Promise<LemonadeHealthResponse | null> {
    try {
      const response = await fetch(`${this.apiBase()}/health`, {
        method: 'GET',
        headers: this.authHeaders(),
        signal,
      });
      if (!response.ok) return null;
      return (await response.json()) as LemonadeHealthResponse;
    } catch {
      return null;
    }
  }

  async listLoadedModelNames(signal?: AbortSignal): Promise<string[]> {
    const health = await this.tryGetServerHealth(signal);
    this.loadStatusSupported = Boolean(health?.all_models_loaded);
    if (!health?.all_models_loaded) return [];
    const names = health.all_models_loaded
      .map((entry) => (entry.model_name || '').trim())
      .filter(Boolean);
    return [...new Set(names)];
  }

  supportsLoadStatus(): boolean {
    return this.loadStatusSupported;
  }

  private async loadModel(model: string, signal?: AbortSignal): Promise<void> {
    try {
      const response = await fetch(`${this.apiBase()}/load`, {
        method: 'POST',
        headers: this.authHeaders(),
        body: JSON.stringify({ model_name: model }),
        signal,
      });
      if (!response.ok) {
        const text = await response.text();
        throw new ProviderError(
          'model_not_loaded',
          `Failed to load model "${model}" (${response.status}): ${text || 'no details'}`,
        );
      }
    } catch (error) {
      throw this.mapError(error);
    }
  }

  /** Lemonade needs an explicit /load call and polling before a model is ready for inference. */
  async ensureModelLoaded(
    model: string,
    options?: {
      signal?: AbortSignal;
      onStatus?: (message: string) => void;
    },
  ): Promise<void> {
    const name = model.trim();
    if (!name) {
      throw new ProviderError('model_not_loaded', 'No model selected.');
    }

    const signal = options?.signal;
    options?.onStatus?.(`Checking if ${name} is loaded…`);

    const health = await this.tryGetServerHealth(signal);
    if (!health?.all_models_loaded) {
      options?.onStatus?.(`${name} ready`);
      return;
    }

    const isLoaded = (names: string[]) =>
      names.some((entry) => entry.toLowerCase() === name.toLowerCase());

    if (isLoaded(await this.listLoadedModelNames(signal))) {
      options?.onStatus?.(`${name} is ready`);
      return;
    }

    options?.onStatus?.(`Loading ${name}…`);
    await this.loadModel(name, signal);

    if (isLoaded(await this.listLoadedModelNames(signal))) {
      options?.onStatus?.(`${name} is ready`);
      return;
    }

    const deadline = Date.now() + MODEL_LOAD_TIMEOUT_MS;
    while (Date.now() < deadline) {
      if (signal?.aborted) {
        throw new ProviderError('cancelled', 'Generation cancelled');
      }
      options?.onStatus?.(`Waiting for ${name} to become available…`);
      await sleep(MODEL_POLL_INTERVAL_MS, signal);
      if (isLoaded(await this.listLoadedModelNames(signal))) {
        options?.onStatus?.(`${name} is ready`);
        return;
      }
    }

    throw new ProviderError(
      'model_not_loaded',
      `Timed out waiting for model "${name}" to load. Check the server and try again.`,
    );
  }
}
