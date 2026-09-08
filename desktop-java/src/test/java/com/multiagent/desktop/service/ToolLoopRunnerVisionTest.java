package com.multiagent.desktop.service;

import com.multiagent.desktop.llm.CancellationToken;
import com.multiagent.desktop.llm.ChatCompletionResult;
import com.multiagent.desktop.llm.ChatRequestMessage;
import com.multiagent.desktop.llm.LlmClient;
import com.multiagent.desktop.llm.ProviderSettings;
import com.multiagent.desktop.llm.ToolCall;
import com.multiagent.desktop.llm.ToolDefinition;
import com.multiagent.desktop.model.HealthStatus;
import com.multiagent.desktop.model.ModelInfo;
import com.multiagent.desktop.persistence.ConversationStore;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The describe_image dispatch branch + the "only advertise it when a vision model is set" gate. */
class ToolLoopRunnerVisionTest {

    private ConversationStore store;
    private Path workspace;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws IOException {
        store = new ConversationStore(tempDir.resolve("chats.db"));
        workspace = tempDir.resolve("ws");
        Files.createDirectories(workspace);
        Files.write(workspace.resolve("shot.png"), new byte[]{(byte) 0x89, 'P', 'N', 'G', 1, 2, 3});
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    @Test
    void describeImageToolIsAdvertisedAndDispatchedToTheVisionModel() {
        VisionFake client = new VisionFake(List.of(
                msgs -> new ChatCompletionResult(null, List.of(new ToolCall(
                        "c1", "describe_image", "{\"path\":\"shot.png\",\"question\":\"what is shown?\"}"))),
                msgs -> new ChatCompletionResult("The screenshot shows a login form.", List.of())));

        List<String> ops = new ArrayList<>();
        String finalText = new ToolLoopRunner(store).run(client, "code-model", "vision-model", 0,
                new ArrayList<>(List.of(ChatRequestMessage.user("look at shot.png"))),
                workspace.toString(), "conv1", new CancellationToken(),
                (op, path, status, detail, checkpointId) -> ops.add(op + ":" + status));

        assertTrue(client.toolNamesSeen.contains("describe_image"), "vision tool should be advertised");
        assertEquals("vision-model", client.describeImageModel);
        assertEquals(7, client.describeImageBytesLen);
        assertEquals("image/png", client.describeImageMime);
        assertTrue(ops.contains("describe_image:running"));
        assertTrue(ops.contains("describe_image:ok"));
        assertEquals("The screenshot shows a login form.", finalText);
    }

    @Test
    void describeImageToolIsHiddenWhenNoVisionModelConfigured() {
        VisionFake client = new VisionFake(List.of(
                msgs -> new ChatCompletionResult("done", List.of())));

        new ToolLoopRunner(store).run(client, "code-model", "  ", 0,
                new ArrayList<>(List.of(ChatRequestMessage.user("hi"))),
                workspace.toString(), "conv2", new CancellationToken(),
                (op, path, status, detail, checkpointId) -> { });

        assertFalse(client.toolNamesSeen.contains("describe_image"));
    }

    private static final class VisionFake implements LlmClient {
        private final List<java.util.function.Function<List<ChatRequestMessage>, ChatCompletionResult>> turns;
        private int i = 0;
        final List<String> toolNamesSeen = new ArrayList<>();
        String describeImageModel;
        int describeImageBytesLen = -1;
        String describeImageMime;

        VisionFake(List<java.util.function.Function<List<ChatRequestMessage>, ChatCompletionResult>> turns) {
            this.turns = turns;
        }

        @Override
        public ChatCompletionResult completeChat(List<ChatRequestMessage> messages, String model,
                                                  List<ToolDefinition> tools, CancellationToken token) {
            tools.forEach(t -> toolNamesSeen.add(t.name()));
            return turns.get(i++).apply(messages);
        }

        @Override
        public String describeImage(String model, String question, byte[] imageBytes, String mimeType,
                                     CancellationToken token) {
            describeImageModel = model;
            describeImageBytesLen = imageBytes.length;
            describeImageMime = mimeType;
            return "vision says: a login form";
        }

        @Override public void updateSettings(ProviderSettings settings) { }
        @Override public HealthStatus checkHealth() { return HealthStatus.ok("ok", 0); }
        @Override public List<ModelInfo> listModels() { return List.of(); }
        @Override public List<String> listLoadedModelNames() { return List.of(); }
        @Override public boolean supportsLoadStatus() { return false; }
        @Override public void ensureModelLoaded(String model, Consumer<String> onStatus, CancellationToken token) { }
        @Override public void streamChat(List<ChatRequestMessage> messages, String model,
                                          Consumer<String> onDelta, CancellationToken token) { }
        @Override public boolean supportsImageGeneration() { return false; }
    }
}
