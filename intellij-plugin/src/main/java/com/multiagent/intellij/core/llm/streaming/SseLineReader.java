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
 * Reads an OpenAI-style Server-Sent-Events stream ("data: {...}\n\n", terminated by
 * "data: [DONE]") and hands each JSON payload line to onData. Equivalent to what the
 * `openai` npm SDK's async-iterable streaming does under the hood in openAiClient.ts.
 */
public final class SseLineReader {
    private SseLineReader() {
    }

    public static void read(InputStream in, Consumer<String> onData, CancellationToken token) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (token != null && token.isCancelled()) {
                    throw new ProviderException(ErrorCode.CANCELLED, "Generation cancelled");
                }
                if (line.isEmpty() || !line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring(5).trim();
                if (data.equals("[DONE]")) {
                    break;
                }
                onData.accept(data);
            }
        }
    }
}
