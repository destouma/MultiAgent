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

/**
 * Client for any generic OpenAI-compatible server: NoLlama, LM Studio,
 * vLLM, real OpenAI, etc. Only relies on the standard /v1 surface, so
 * there's no way to know whether a model is actually loaded in memory -
 * listLoadedModelNames()/supportsLoadStatus() reflect that honestly rather
 * than guessing. For Lemonade specifically, which exposes a non-standard
 * /health + /load extension for real load status and explicit preloading,
 * see LemonadeClient, which adds that behavior on top of this one.
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

  protected apiBase(): string {
    return this.settings.baseUrl.replace(/\/$/, '');
  }

  protected authHeaders(): Record<string, string> {
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

  /** Generic OpenAI-compatible servers don't expose any load-status signal. */
  async listLoadedModelNames(): Promise<string[]> {
    return [];
  }

  supportsLoadStatus(): boolean {
    return false;
  }

  /**
   * Generic OpenAI-compatible servers (NoLlama, LM Studio, vLLM, real
   * OpenAI...) manage model loading themselves with no separate preload
   * endpoint, so this is a no-op; the chat call itself will surface a clear
   * error if the model truly isn't available.
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
    options?.onStatus?.(`${name} ready`);
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

  protected mapError(error: unknown): ProviderError {
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
