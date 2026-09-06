package com.multiagent.desktop.llm;

import com.multiagent.desktop.model.HealthStatus;
import com.multiagent.desktop.model.ModelInfo;

import java.util.List;
import java.util.function.Consumer;

/**
 * Common surface every provider implements, so ChatService is written once against
 * whichever provider is active. Mirrors shared/llm/types.ts LlmClient. completeChat's
 * tools parameter and generateImage are unused until Phase 2/3 but kept on the interface
 * now so those phases are additive rather than a signature break.
 */
public interface LlmClient {

    void updateSettings(ProviderSettings settings);

    HealthStatus checkHealth();

    List<ModelInfo> listModels();

    /** Names of models the server currently has loaded/running, if it can report that. */
    List<String> listLoadedModelNames();

    /**
     * Whether the last listLoadedModelNames() call got a real signal from the server, as
     * opposed to silently degrading to an empty list because the server doesn't expose load
     * status at all. Callers should treat an empty list as "unknown" rather than "definitely
     * not loaded" when this is false.
     */
    boolean supportsLoadStatus();

    void ensureModelLoaded(String model, Consumer<String> onStatus, CancellationToken token);

    /** Streams text deltas via onDelta, blocking the calling thread until the stream ends. */
    void streamChat(List<ChatRequestMessage> messages, String model, Consumer<String> onDelta,
                     CancellationToken token);

    /** Pass an empty list for a plain (no tool-calling) completion. */
    ChatCompletionResult completeChat(List<ChatRequestMessage> messages, String model,
                                       List<ToolDefinition> tools, CancellationToken token);

    /** False for providers with no image-generation endpoint (e.g. Ollama). */
    boolean supportsImageGeneration();

    default String generateImage(GenerateImageInput input) {
        throw new ProviderException(ErrorCode.UNSUPPORTED, "Image generation not implemented yet");
    }
}
