import OpenAI from 'openai';
import type { ChatCompletionMessageParam } from 'openai/resources/chat/completions';
import type { AppErrorCode, HealthStatus, ModelInfo } from '../../shared/types';

export type LemonadeConnectionSettings = {
  baseUrl: string;
  apiKey: string;
};

type LemonadeHealthResponse = {
  status?: string;
  all_models_loaded?: Array<{
    model_name?: string;
    type?: string;
  }>;
};

const MODEL_LOAD_TIMEOUT_MS = 10 * 60 * 1000;
const MODEL_POLL_INTERVAL_MS = 1500;

export class LemonadeError extends Error {
  code: AppErrorCode;

  constructor(code: AppErrorCode, message: string) {
    super(message);
    this.name = 'LemonadeError';
    this.code = code;
  }
}

function sleep(ms: number, signal?: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    if (signal?.aborted) {
      reject(new LemonadeError('cancelled', 'Generation cancelled'));
      return;
    }
    const timer = setTimeout(() => {
      signal?.removeEventListener('abort', onAbort);
      resolve();
    }, ms);
    const onAbort = () => {
      clearTimeout(timer);
      reject(new LemonadeError('cancelled', 'Generation cancelled'));
    };
    signal?.addEventListener('abort', onAbort, { once: true });
  });
}

export class LemonadeClient {
  private client: OpenAI;

  constructor(private settings: LemonadeConnectionSettings) {
    this.client = this.createClient(settings);
  }

  updateSettings(settings: LemonadeConnectionSettings): void {
    this.settings = settings;
    this.client = this.createClient(settings);
  }

  private createClient(settings: LemonadeConnectionSettings): OpenAI {
    return new OpenAI({
      baseURL: settings.baseUrl.replace(/\/$/, ''),
      apiKey: settings.apiKey || 'lemonade',
    });
  }

  private apiBase(): string {
    return this.settings.baseUrl.replace(/\/$/, '');
  }

  private authHeaders(): Record<string, string> {
    return {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${this.settings.apiKey || 'lemonade'}`,
    };
  }

  async checkHealth(): Promise<HealthStatus> {
    const started = Date.now();
    try {
      await this.client.models.list();
      return {
        ok: true,
        message: 'Connected to Lemonade',
        latencyMs: Date.now() - started,
      };
    } catch (error) {
      return {
        ok: false,
        message: this.mapError(error).message,
        latencyMs: Date.now() - started,
      };
    }
  }

  async listModels(): Promise<ModelInfo[]> {
    try {
      const response = await this.client.models.list();
      return response.data.map((model) => ({
        id: model.id,
        ownedBy: model.owned_by,
      }));
    } catch (error) {
      throw this.mapError(error);
    }
  }

  async getServerHealth(signal?: AbortSignal): Promise<LemonadeHealthResponse> {
    try {
      const response = await fetch(`${this.apiBase()}/health`, {
        method: 'GET',
        headers: this.authHeaders(),
        signal,
      });
      if (!response.ok) {
        const text = await response.text();
        throw new LemonadeError(
          'server_unreachable',
          `Health check failed (${response.status}): ${text || 'no details'}`,
        );
      }
      return (await response.json()) as LemonadeHealthResponse;
    } catch (error) {
      throw this.mapError(error);
    }
  }

  async isModelLoaded(model: string, signal?: AbortSignal): Promise<boolean> {
    const health = await this.getServerHealth(signal);
    const target = model.trim().toLowerCase();
    return (health.all_models_loaded ?? []).some(
      (entry) => (entry.model_name || '').trim().toLowerCase() === target,
    );
  }

  async loadModel(model: string, signal?: AbortSignal): Promise<void> {
    try {
      const response = await fetch(`${this.apiBase()}/load`, {
        method: 'POST',
        headers: this.authHeaders(),
        body: JSON.stringify({ model_name: model }),
        signal,
      });
      if (!response.ok) {
        const text = await response.text();
        throw new LemonadeError(
          'model_not_loaded',
          `Failed to load model "${model}" (${response.status}): ${text || 'no details'}`,
        );
      }
    } catch (error) {
      throw this.mapError(error);
    }
  }

  /**
   * Ensure a model is loaded in Lemonade before inference.
   * Checks /health; if missing, POSTs /load and polls until ready (or timeout).
   */
  async ensureModelLoaded(
    model: string,
    options?: {
      signal?: AbortSignal;
      onStatus?: (message: string) => void;
    },
  ): Promise<void> {
    const name = model.trim();
    if (!name) {
      throw new LemonadeError('model_not_loaded', 'No model selected.');
    }

    const signal = options?.signal;
    options?.onStatus?.(`Checking if ${name} is loaded…`);

    if (await this.isModelLoaded(name, signal)) {
      options?.onStatus?.(`${name} is ready`);
      return;
    }

    options?.onStatus?.(`Loading ${name}…`);
    await this.loadModel(name, signal);

    if (await this.isModelLoaded(name, signal)) {
      options?.onStatus?.(`${name} is ready`);
      return;
    }

    const deadline = Date.now() + MODEL_LOAD_TIMEOUT_MS;
    while (Date.now() < deadline) {
      if (signal?.aborted) {
        throw new LemonadeError('cancelled', 'Generation cancelled');
      }
      options?.onStatus?.(`Waiting for ${name} to become available…`);
      await sleep(MODEL_POLL_INTERVAL_MS, signal);
      if (await this.isModelLoaded(name, signal)) {
        options?.onStatus?.(`${name} is ready`);
        return;
      }
    }

    throw new LemonadeError(
      'model_not_loaded',
      `Timed out waiting for model "${name}" to load. Check Lemonade and try again.`,
    );
  }

  async *streamChat(
    messages: ChatCompletionMessageParam[],
    model: string,
    signal?: AbortSignal,
  ): AsyncGenerator<string, void, unknown> {
    try {
      const stream = await this.client.chat.completions.create(
        {
          model,
          messages,
          stream: true,
        },
        { signal },
      );

      for await (const chunk of stream) {
        if (signal?.aborted) {
          throw new LemonadeError('cancelled', 'Generation cancelled');
        }
        const delta = chunk.choices[0]?.delta?.content;
        if (delta) {
          yield delta;
        }
      }
    } catch (error) {
      throw this.mapError(error);
    }
  }

  private mapError(error: unknown): LemonadeError {
    if (error instanceof LemonadeError) {
      return error;
    }

    if (error instanceof Error && error.name === 'AbortError') {
      return new LemonadeError('cancelled', 'Generation cancelled');
    }

    const message = error instanceof Error ? error.message : String(error);
    const lower = message.toLowerCase();

    if (
      lower.includes('econnrefused') ||
      lower.includes('fetch failed') ||
      lower.includes('network') ||
      lower.includes('enotfound') ||
      lower.includes('timed out') ||
      lower.includes('connection')
    ) {
      return new LemonadeError(
        'server_unreachable',
        'Cannot reach Lemonade Server. Is it running?',
      );
    }

    if (lower.includes('model') && (lower.includes('not found') || lower.includes('load'))) {
      return new LemonadeError(
        'model_not_loaded',
        'Model is not available. Pull or load it in Lemonade, then retry.',
      );
    }

    if (
      (lower.includes('context size') || lower.includes('context length')) &&
      (lower.includes('exceed') || lower.includes('too long') || lower.includes('too large'))
    ) {
      return new LemonadeError(
        'context_exceeded',
        `${message} Lower "multiagent.maxHistory" in Settings, or load this model with a larger ` +
          'context window in Lemonade.',
      );
    }

    return new LemonadeError('unknown', message || 'Unexpected Lemonade error');
  }
}
