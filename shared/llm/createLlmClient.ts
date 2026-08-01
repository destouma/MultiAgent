import type { ProviderType } from '../types';
import { OllamaClient } from './ollamaClient';
import { OpenAiClient } from './openAiClient';
import type { LlmClient, ProviderSettings } from './types';

export function createLlmClient(providerType: ProviderType, settings: ProviderSettings): LlmClient {
  return providerType === 'ollama' ? new OllamaClient(settings) : new OpenAiClient(settings);
}
