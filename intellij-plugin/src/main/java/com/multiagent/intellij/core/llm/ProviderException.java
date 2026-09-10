package com.multiagent.intellij.core.llm;

/** Mirrors shared/llm/types.ts ProviderError. */
public class ProviderException extends RuntimeException {
    private final ErrorCode code;

    public ProviderException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ProviderException(ErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }

    /**
     * Classifies a raw exception into a ProviderException via substring matching on its
     * message, mirroring shared/llm/openAiClient.ts's mapError - kept here as a shared
     * utility since OpenAiClient/LemonadeClient (and eventually OllamaClient) all need it.
     */
    public static ProviderException classify(Throwable error) {
        if (error instanceof ProviderException pe) {
            return pe;
        }
        if (error instanceof java.util.concurrent.CancellationException
                || error instanceof InterruptedException) {
            return new ProviderException(ErrorCode.CANCELLED, "Generation cancelled", error);
        }

        String message = error.getMessage() != null ? error.getMessage() : String.valueOf(error);
        String lower = message.toLowerCase();

        if (lower.contains("econnrefused") || lower.contains("connection refused")
                || lower.contains("network") || lower.contains("unknown host")
                || lower.contains("timed out") || lower.contains("connection")) {
            return new ProviderException(ErrorCode.SERVER_UNREACHABLE,
                    "Cannot reach the server. Is it running?", error);
        }

        if (lower.contains("model") && (lower.contains("not found") || lower.contains("load"))) {
            return new ProviderException(ErrorCode.MODEL_NOT_LOADED,
                    "Model is not available. Pull or load it on the server, then retry.", error);
        }

        if ((lower.contains("context size") || lower.contains("context length"))
                && (lower.contains("exceed") || lower.contains("too long") || lower.contains("too large"))) {
            return new ProviderException(ErrorCode.CONTEXT_EXCEEDED,
                    message + " Lower \"Max history messages\" in Settings, or load this model "
                            + "with a larger context window on the server.", error);
        }

        return new ProviderException(ErrorCode.UNKNOWN,
                message.isBlank() ? "Unexpected provider error" : message, error);
    }
}
