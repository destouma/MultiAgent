import type {
  ChatCompletionMessageParam,
  ChatCompletionTool,
} from 'openai/resources/chat/completions';
import type { AppErrorCode, HealthStatus, ModelInfo } from '../types';

export type ProviderSettings = {
  baseUrl: string;
  apiKey: string;
};

export type ChatCompletionResult = {
  content: string;
  toolCalls: Array<{
    id: string;
    name: string;
    arguments: string;
  }>;
};

export type GenerateImageInput = {
  prompt: string;
  model: string;
  size?: string;
  steps?: number;
  cfgScale?: number;
  seed?: number;
  signal?: AbortSignal;
};

export class ProviderError extends Error {
  code: AppErrorCode;

  constructor(code: AppErrorCode, message: string) {
    super(message);
    this.name = 'ProviderError';
    this.code = code;
  }
}

/**
 * Common surface both the OpenAI-compatible client (Lemonade, NoLlama,
 * LM Studio, vLLM, real OpenAI, ...) and the native Ollama client implement,
 * so callers can be written once against whichever provider is configured.
 */
export interface LlmClient {
  updateSettings(settings: ProviderSettings): void;

  checkHealth(): Promise<HealthStatus>;
  listModels(): Promise<ModelInfo[]>;

  /** Names of models the server currently has loaded/running, if it can report that. */
  listLoadedModelNames(signal?: AbortSignal): Promise<string[]>;

  ensureModelLoaded(
    model: string,
    options?: { signal?: AbortSignal; onStatus?: (message: string) => void },
  ): Promise<void>;

  streamChat(
    messages: ChatCompletionMessageParam[],
    model: string,
    signal?: AbortSignal,
  ): AsyncGenerator<string, void, unknown>;

  completeChat(
    messages: ChatCompletionMessageParam[],
    model: string,
    options?: { tools?: ChatCompletionTool[]; signal?: AbortSignal },
  ): Promise<ChatCompletionResult>;

  /** False for providers with no image-generation endpoint (e.g. Ollama). */
  supportsImageGeneration(): boolean;
  generateImage(input: GenerateImageInput): Promise<string>;
}
