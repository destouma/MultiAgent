package com.multiagent.intellij.core.model;

/**
 * Mirrors shared/types.ts ModelInfo, plus {@code contextLength} - the model's context
 * window in tokens when the server reports it (Lemonade's /models {@code max_context_window},
 * vLLM's {@code max_model_len}, ...); null when it doesn't.
 */
public record ModelInfo(String id, String ownedBy, Integer contextLength) {

    public ModelInfo(String id, String ownedBy) {
        this(id, ownedBy, null);
    }

    @Override
    public String toString() {
        return id;
    }
}
