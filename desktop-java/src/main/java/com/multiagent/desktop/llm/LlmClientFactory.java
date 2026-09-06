package com.multiagent.desktop.llm;

import com.multiagent.desktop.model.ProviderType;

/** Mirrors shared/llm/createLlmClient.ts. */
public final class LlmClientFactory {
    private LlmClientFactory() {
    }

    public static LlmClient create(ProviderType providerType, ProviderSettings settings) {
        return switch (providerType) {
            case LEMONADE -> new LemonadeClient(settings);
            case OPENAI -> new OpenAiClient(settings);
            case OLLAMA -> new OllamaClient(settings);
        };
    }
}
