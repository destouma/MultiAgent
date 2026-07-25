import OpenAI from 'openai';
import type {
  ChatCompletionMessageParam,
  ChatCompletionTool,
} from 'openai/resources/chat/completions';
import type { AppErrorCode, AppSettings, HealthStatus, ModelInfo } from '../../../shared/types';

export type ChatCompletionResult = {
  content: string;
  toolCalls: Array<{
    id: string;
    name: string;
    arguments: string;
  }>;
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
  private settings: AppSettings;

  constructor(settings: AppSettings) {
    this.settings = settings;
    this.client = this.createClient(settings);
  }

  updateSettings(settings: AppSettings): void {
    this.settings = settings;
    this.client = this.createClient(settings);
  }

  private createClient(settings: AppSettings): OpenAI {
    return new OpenAI({
      baseURL: settings.baseUrl.replace(/\/$/, ''),
      apiKey: settings.apiKey || 'lemonade',
      dangerouslyAllowBrowser: false,
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

  async completeChat(
    messages: ChatCompletionMessageParam[],
    model: string,
    options?: {
      tools?: ChatCompletionTool[];
      signal?: AbortSignal;
    },
  ): Promise<ChatCompletionResult> {
    try {
      const response = await this.client.chat.completions.create(
        {
          model,
          messages,
          stream: false,
          ...(options?.tools?.length ? { tools: options.tools } : {}),
        },
        { signal: options?.signal },
      );

      const message = response.choices[0]?.message;
      const toolCalls =
        message?.tool_calls
          ?.filter(
            (
              call,
            ): call is Extract<
              typeof call,
              { type?: 'function'; function: { name: string; arguments: string } }
            > => 'function' in call && Boolean(call.function?.name),
          )
          .map((call) => ({
            id: call.id,
            name: call.function.name,
            arguments: call.function.arguments || '{}',
          })) ?? [];

      return {
        content: message?.content ?? '',
        toolCalls,
      };
    } catch (error) {
      throw this.mapError(error);
    }
  }

  async generateImage(input: {
    prompt: string;
    model: string;
    size?: string;
    steps?: number;
    cfgScale?: number;
    seed?: number;
    signal?: AbortSignal;
  }): Promise<string> {
    try {
      return await this.generateImageRaw(input);
    } catch (rawError) {
      try {
        const response = await this.client.images.generate(
          {
            model: input.model,
            prompt: input.prompt,
            n: 1,
            size: (input.size || '512x512') as '256x256' | '512x512' | '1024x1024',
            response_format: 'b64_json',
          },
          { signal: input.signal },
        );
        const b64 = response.data?.[0]?.b64_json;
        if (!b64) {
          throw rawError;
        }
        return b64;
      } catch {
        throw this.mapError(rawError);
      }
    }
  }

  private async generateImageRaw(input: {
    prompt: string;
    model: string;
    size?: string;
    steps?: number;
    cfgScale?: number;
    seed?: number;
    signal?: AbortSignal;
  }): Promise<string> {
    const body: Record<string, unknown> = {
      model: input.model,
      prompt: input.prompt,
      n: 1,
      size: input.size || '512x512',
      response_format: 'b64_json',
      steps: input.steps ?? 20,
    };
    if (input.cfgScale != null) {
      body.guidance_scale = input.cfgScale;
      body.cfg_scale = input.cfgScale;
    }
    if (input.seed != null && Number.isFinite(input.seed)) {
      body.seed = input.seed;
    }

    const response = await fetch(`${this.apiBase()}/images/generations`, {
      method: 'POST',
      headers: this.authHeaders(),
      body: JSON.stringify(body),
      signal: input.signal,
    });

    if (!response.ok) {
      const text = await response.text();
      throw new LemonadeError(
        'unknown',
        `Image API error (${response.status}): ${text || 'no details'}. ` +
          'Load an image model on the local server and select it in the model menu.',
      );
    }

    const json = (await response.json()) as {
      data?: Array<{ b64_json?: string }>;
    };
    const b64 = json.data?.[0]?.b64_json;
    if (!b64) {
      throw new LemonadeError('unknown', 'Image generation returned no image data');
    }
    return b64;
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
        `${message} In MultiAgent: lower "Max history messages" in Settings, ask about a smaller ` +
          'part of a bound workspace folder, or load this model with a larger context window in Lemonade.',
      );
    }

    return new LemonadeError('unknown', message || 'Unexpected Lemonade error');
  }
}
