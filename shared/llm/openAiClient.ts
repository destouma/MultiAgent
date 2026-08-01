import OpenAI from 'openai';
import type {
  ChatCompletionMessageParam,
  ChatCompletionTool,
} from 'openai/resources/chat/completions';
import type { HealthStatus, ModelInfo } from '../types';
import {
  ProviderError,
  type ChatCompletionResult,
  type GenerateImageInput,
  type LlmClient,
  type ProviderSettings,
} from './types';

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
 * Client for any OpenAI-compatible server: Lemonade, NoLlama (port 8000),
 * LM Studio, vLLM, real OpenAI, etc. Lemonade additionally exposes a
 * non-standard /health + /load extension for explicit model preloading;
 * that's used opportunistically when present, but every other part of this
 * client only relies on the standard /v1 surface so it works against any
 * of them.
 */
export class OpenAiClient implements LlmClient {
  private client: OpenAI;
  private settings: ProviderSettings;

  constructor(settings: ProviderSettings) {
    this.settings = settings;
    this.client = this.createClient(settings);
  }

  updateSettings(settings: ProviderSettings): void {
    this.settings = settings;
    this.client = this.createClient(settings);
  }

  supportsImageGeneration(): boolean {
    return true;
  }

  private createClient(settings: ProviderSettings): OpenAI {
    return new OpenAI({
      baseURL: settings.baseUrl.replace(/\/$/, ''),
      apiKey: settings.apiKey || 'unused',
      dangerouslyAllowBrowser: false,
    });
  }

  private apiBase(): string {
    return this.settings.baseUrl.replace(/\/$/, '');
  }

  private authHeaders(): Record<string, string> {
    return {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${this.settings.apiKey || 'unused'}`,
    };
  }

  async checkHealth(): Promise<HealthStatus> {
    const started = Date.now();
    try {
      await this.client.models.list();
      return {
        ok: true,
        message: 'Connected',
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

  /** Returns null if the server doesn't expose Lemonade's /health extension at all. */
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
    if (!health?.all_models_loaded) return [];
    const names = health.all_models_loaded
      .map((entry) => (entry.model_name || '').trim())
      .filter(Boolean);
    return [...new Set(names)];
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
        throw new ProviderError(
          'model_not_loaded',
          `Failed to load model "${model}" (${response.status}): ${text || 'no details'}`,
        );
      }
    } catch (error) {
      throw this.mapError(error);
    }
  }

  /**
   * Ensure a model is loaded before inference. Lemonade needs an explicit
   * /load call and polling; most other OpenAI-compatible servers (NoLlama,
   * LM Studio, vLLM, real OpenAI...) manage loading themselves and don't
   * expose that extension at all, in which case this is a no-op and the
   * chat call itself will surface a clear error if the model truly isn't
   * available.
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
          throw new ProviderError('cancelled', 'Generation cancelled');
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

  async generateImage(input: GenerateImageInput): Promise<string> {
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

  private async generateImageRaw(input: GenerateImageInput): Promise<string> {
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
      throw new ProviderError(
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
      throw new ProviderError('unknown', 'Image generation returned no image data');
    }
    return b64;
  }

  private mapError(error: unknown): ProviderError {
    if (error instanceof ProviderError) {
      return error;
    }

    if (error instanceof Error && error.name === 'AbortError') {
      return new ProviderError('cancelled', 'Generation cancelled');
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
      return new ProviderError('server_unreachable', 'Cannot reach the server. Is it running?');
    }

    if (lower.includes('model') && (lower.includes('not found') || lower.includes('load'))) {
      return new ProviderError(
        'model_not_loaded',
        'Model is not available. Pull or load it on the server, then retry.',
      );
    }

    if (
      (lower.includes('context size') || lower.includes('context length')) &&
      (lower.includes('exceed') || lower.includes('too long') || lower.includes('too large'))
    ) {
      return new ProviderError(
        'context_exceeded',
        `${message} Lower "Max history messages" in Settings, ask about a smaller part of a ` +
          'bound workspace folder, or load this model with a larger context window on the server.',
      );
    }

    return new ProviderError('unknown', message || 'Unexpected provider error');
  }
}
