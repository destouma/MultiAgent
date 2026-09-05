package com.multiagent.desktop.llm;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Mirrors the substring-classification behavior of shared/llm/openAiClient.ts's mapError. */
class ProviderExceptionTest {

    @Test
    void classifyPassesThroughAnExistingProviderException() {
        ProviderException original = new ProviderException(ErrorCode.MODEL_NOT_LOADED, "already classified");
        assertSame(original, ProviderException.classify(original));
    }

    @Test
    void classifyMapsCancellationToCancelled() {
        ProviderException result = ProviderException.classify(new CancellationException());
        assertEquals(ErrorCode.CANCELLED, result.code());
    }

    @Test
    void classifyMapsConnectionRefusedToServerUnreachable() {
        ProviderException result = ProviderException.classify(new IOException("Connection refused"));
        assertEquals(ErrorCode.SERVER_UNREACHABLE, result.code());
    }

    @Test
    void classifyMapsUnknownHostToServerUnreachable() {
        ProviderException result = ProviderException.classify(new IOException("Unknown host: localhost"));
        assertEquals(ErrorCode.SERVER_UNREACHABLE, result.code());
    }

    @Test
    void classifyMapsModelNotFoundToModelNotLoaded() {
        ProviderException result = ProviderException.classify(new IOException("model \"foo\" not found"));
        assertEquals(ErrorCode.MODEL_NOT_LOADED, result.code());
    }

    @Test
    void classifyMapsContextLengthExceededToContextExceeded() {
        ProviderException result = ProviderException.classify(
                new IOException("This model's context length is exceeded by your messages"));
        assertEquals(ErrorCode.CONTEXT_EXCEEDED, result.code());
    }

    @Test
    void classifyFallsBackToUnknownForAnUnrecognizedMessage() {
        ProviderException result = ProviderException.classify(new IOException("something completely different"));
        assertEquals(ErrorCode.UNKNOWN, result.code());
    }
}
