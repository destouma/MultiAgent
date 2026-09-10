package com.multiagent.intellij.core.llm.streaming;

import com.multiagent.intellij.core.llm.CancellationToken;
import com.multiagent.intellij.core.llm.ErrorCode;
import com.multiagent.intellij.core.llm.ProviderException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * Reads a newline-delimited JSON stream - one JSON object per line, no "data:" prefix and
 * no "[DONE]" sentinel - and hands each non-blank line to onData. This is Ollama's
 * /api/chat streaming wire format, as opposed to the OpenAI-style SSE framing SseLineReader
 * reads for OpenAiClient/LemonadeClient.
 */
public final class NdjsonLineReader {
    private NdjsonLineReader() {
    }

    public static void read(InputStream in, Consumer<String> onData, CancellationToken token) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (token != null && token.isCancelled()) {
                    throw new ProviderException(ErrorCode.CANCELLED, "Generation cancelled");
                }
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                onData.accept(trimmed);
            }
        }
    }
}
