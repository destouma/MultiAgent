package com.multiagent.desktop.llm;

import com.multiagent.desktop.model.ProviderType;

/**
 * Mirrors shared/llm/createLlmClient.ts. OllamaClient is deferred to Phase 2 (see the
 * migration plan) - selecting OLLAMA falls back to the generic OpenAI-compatible client
 * with a clearly wrong wire format rather than silently working, so surface that early.
 */
public final class LlmClientFactory {
    private LlmClientFactory() {
    }

    public static LlmClient create(ProviderType providerType, ProviderSettings settings) {
        return switch (providerType) {
            case LEMONADE -> new LemonadeClient(settings);
            case OPENAI -> new OpenAiClient(settings);
            case OLLAMA -> throw new ProviderException(ErrorCode.UNSUPPORTED,
                    "Ollama support lands in a later phase of the Java client - use Lemonade "
                            + "or an OpenAI-compatible server for now.");
        };
    }
}
