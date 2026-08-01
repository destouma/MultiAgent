import type { ProviderType } from '../types';
import { LemonadeClient } from './lemonadeClient';
import { OllamaClient } from './ollamaClient';
import { OpenAiClient } from './openAiClient';
import type { LlmClient, ProviderSettings } from './types';

export function createLlmClient(providerType: ProviderType, settings: ProviderSettings): LlmClient {
  if (providerType === 'ollama') return new OllamaClient(settings);
  if (providerType === 'lemonade') return new LemonadeClient(settings);
  return new OpenAiClient(settings);
}
