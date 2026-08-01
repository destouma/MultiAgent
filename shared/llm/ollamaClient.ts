import { randomUUID } from 'node:crypto';
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

type OllamaToolCall = {
  function: {
    name: string;
    arguments: Record<string, unknown>;
  };
};

type OllamaMessage = {
  role: string;
  content: string;
  tool_calls?: OllamaToolCall[];
};

type OllamaChatChunk = {
  model?: string;
  message?: {
    role?: string;
    content?: string;
    tool_calls?: OllamaToolCall[];
  };
  done?: boolean;
  error?: string;
};

type OllamaTagsResponse = {
  models?: Array<{ name?: string; model?: string }>;
};

/**
 * Client for the native Ollama API (/api/chat, /api/tags, /api/ps, ...) -
 * distinct wire format from OpenAI's: NDJSON streaming instead of SSE,
 * different model-list shape, no id on tool calls, arguments as an object
 * instead of a JSON string, and no image-generation endpoint at all.
 */
export class OllamaClient implements LlmClient {
  private settings: ProviderSettings;

  constructor(settings: ProviderSettings) {
    this.settings = settings;
  }

  updateSettings(settings: ProviderSettings): void {
    this.settings = settings;
  }

  supportsImageGeneration(): boolean {
    return false;
  }

  private apiBase(): string {
    return this.settings.baseUrl.replace(/\/$/, '');
  }

  private headers(): Record<string, string> {
    const headers: Record<string, string> = { 'Content-Type': 'application/json' };
    if (this.settings.apiKey) {
      headers.Authorization = `Bearer ${this.settings.apiKey}`;
    }
    return headers;
  }

  private async fetchJson<T>(path: string, init?: RequestInit): Promise<T> {
    try {
      const response = await fetch(`${this.apiBase()}${path}`, {
        ...init,
        headers: { ...this.headers(), ...(init?.headers as Record<string, string> | undefined) },
      });
      if (!response.ok) {
        throw new ProviderError(
          response.status === 404 ? 'model_not_loaded' : 'unknown',
          (await this.readErrorText(response)) || `Request failed (${response.status})`,
        );
      }
      return (await response.json()) as T;
    } catch (error) {
      throw this.mapError(error);
    }
  }

  private async readErrorText(response: Response): Promise<string> {
    const text = await response.text().catch(() => '');
    try {
      const parsed = JSON.parse(text) as { error?: string };
      return parsed.error || text;
    } catch {
      return text;
    }
  }

  async checkHealth(): Promise<HealthStatus> {
    const started = Date.now();
    try {
      await this.fetchJson('/api/tags');
      return { ok: true, message: 'Connected', latencyMs: Date.now() - started };
    } catch (error) {
      return { ok: false, message: this.mapError(error).message, latencyMs: Date.now() - started };
    }
  }

  async listModels(): Promise<ModelInfo[]> {
    const data = await this.fetchJson<OllamaTagsResponse>('/api/tags');
    return (data.models ?? [])
      .map((entry) => entry.model || entry.name)
      .filter((id): id is string => Boolean(id))
      .map((id) => ({ id, ownedBy: 'ollama' }));
  }

  async listLoadedModelNames(): Promise<string[]> {
    try {
      const data = await this.fetchJson<OllamaTagsResponse>('/api/ps');
      return (data.models ?? [])
        .map((entry) => entry.model || entry.name)
        .filter((id): id is string => Boolean(id));
    } catch {
      return [];
    }
  }

  /**
   * Ollama loads models on demand; there's no separate preload endpoint in
   * the public API. This confirms the model is actually pulled, then fires
   * a no-prompt /api/generate call to trigger loading and waits for it,
   * mirroring the "load before first use" UX the app already has for
   * Lemonade rather than surprising the user with a silent multi-minute
   * wait on their first message.
   */
  async ensureModelLoaded(
    model: string,
    options?: { signal?: AbortSignal; onStatus?: (message: string) => void },
  ): Promise<void> {
    const name = model.trim();
    if (!name) {
      throw new ProviderError('model_not_loaded', 'No model selected.');
    }

    const signal = options?.signal;
    options?.onStatus?.(`Checking if ${name} is loaded…`);

    const alreadyLoaded = await this.listLoadedModelNames();
    if (alreadyLoaded.some((entry) => entry.toLowerCase() === name.toLowerCase())) {
      options?.onStatus?.(`${name} is ready`);
      return;
    }

    const known = await this.listModels();
    if (!known.some((entry) => entry.id.toLowerCase() === name.toLowerCase())) {
      throw new ProviderError(
        'model_not_loaded',
        `Model "${name}" was not found on this Ollama server. Pull it first: ollama pull ${name}`,
      );
    }

    options?.onStatus?.(`Loading ${name}…`);
    try {
      const response = await fetch(`${this.apiBase()}/api/generate`, {
        method: 'POST',
        headers: this.headers(),
        body: JSON.stringify({ model: name, keep_alive: '5m' }),
        signal,
      });
      if (!response.ok) {
        throw new ProviderError(
          'model_not_loaded',
          (await this.readErrorText(response)) || `Failed to load "${name}" (${response.status})`,
        );
      }
    } catch (error) {
      throw this.mapError(error);
    }
    options?.onStatus?.(`${name} ready`);
  }

  async *streamChat(
    messages: ChatCompletionMessageParam[],
    model: string,
    signal?: AbortSignal,
  ): AsyncGenerator<string, void, unknown> {
    const response = await this.rawChat(messages, model, true, undefined, signal);
    yield* this.readNdjsonDeltas(response);
  }

  async completeChat(
    messages: ChatCompletionMessageParam[],
    model: string,
    options?: { tools?: ChatCompletionTool[]; signal?: AbortSignal },
  ): Promise<ChatCompletionResult> {
    const response = await this.rawChat(messages, model, false, options?.tools, options?.signal);
    try {
      const chunk = (await response.json()) as OllamaChatChunk;
      if (chunk.error) throw new ProviderError('unknown', chunk.error);
      const toolCalls = (chunk.message?.tool_calls ?? []).map((call) => ({
        // Ollama doesn't assign tool-call ids; synthesize one so the
        // shared tool-loop logic (which correlates results via id) works
        // the same as it does against OpenAI-compatible servers.
        id: randomUUID(),
        name: call.function.name,
        arguments: JSON.stringify(call.function.arguments ?? {}),
      }));
      return { content: chunk.message?.content ?? '', toolCalls };
    } catch (error) {
      throw this.mapError(error);
    }
  }

  generateImage(_input: GenerateImageInput): Promise<string> {
    return Promise.reject(
      new ProviderError('unsupported', 'This provider (Ollama) does not support image generation.'),
    );
  }

  private async rawChat(
    messages: ChatCompletionMessageParam[],
    model: string,
    stream: boolean,
    tools: ChatCompletionTool[] | undefined,
    signal: AbortSignal | undefined,
  ): Promise<Response> {
    try {
      const response = await fetch(`${this.apiBase()}/api/chat`, {
        method: 'POST',
        headers: this.headers(),
        body: JSON.stringify({
          model,
          messages: toOllamaMessages(messages),
          stream,
          ...(tools?.length ? { tools } : {}),
        }),
        signal,
      });
      if (!response.ok) {
        throw new ProviderError(
          'unknown',
          (await this.readErrorText(response)) || `Request failed (${response.status})`,
        );
      }
      return response;
    } catch (error) {
      throw this.mapError(error);
    }
  }

  private async *readNdjsonDeltas(response: Response): AsyncGenerator<string, void, unknown> {
    if (!response.body) return;
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    try {
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });

        let newlineIndex = buffer.indexOf('\n');
        while (newlineIndex !== -1) {
          const line = buffer.slice(0, newlineIndex).trim();
          buffer = buffer.slice(newlineIndex + 1);
          newlineIndex = buffer.indexOf('\n');
          if (!line) continue;

          let chunk: OllamaChatChunk;
          try {
            chunk = JSON.parse(line) as OllamaChatChunk;
          } catch {
            continue;
          }
          if (chunk.error) throw new ProviderError('unknown', chunk.error);
          const delta = chunk.message?.content;
          if (delta) yield delta;
          if (chunk.done) return;
        }
      }
    } catch (error) {
      throw this.mapError(error);
    } finally {
      reader.releaseLock();
    }
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
      return new ProviderError(
        'server_unreachable',
        'Cannot reach the Ollama server. Is it running?',
      );
    }

    if (lower.includes('model') && lower.includes('not found')) {
      return new ProviderError(
        'model_not_loaded',
        'Model is not available. Run "ollama pull <model>" first.',
      );
    }

    if (lower.includes('context') && (lower.includes('exceed') || lower.includes('too long'))) {
      return new ProviderError(
        'context_exceeded',
        `${message} Lower "Max history messages" in Settings or use a model with a larger context window.`,
      );
    }

    return new ProviderError('unknown', message || 'Unexpected Ollama error');
  }
}

function toOllamaMessages(messages: ChatCompletionMessageParam[]): OllamaMessage[] {
  return messages.map((message) => {
    if (message.role === 'assistant' && message.tool_calls?.length) {
      return {
        role: 'assistant',
        content: typeof message.content === 'string' ? message.content : '',
        tool_calls: message.tool_calls
          .filter(
            (
              call,
            ): call is Extract<typeof call, { function: { name: string; arguments: string } }> =>
              'function' in call,
          )
          .map((call) => {
            let args: Record<string, unknown> = {};
            try {
              args = JSON.parse(call.function.arguments) as Record<string, unknown>;
            } catch {
              args = {};
            }
            return { function: { name: call.function.name, arguments: args } };
          }),
      };
    }

    return {
      role: message.role,
      content: typeof message.content === 'string' ? message.content : '',
    };
  });
}
