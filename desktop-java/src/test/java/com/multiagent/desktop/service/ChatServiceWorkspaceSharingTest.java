package com.multiagent.desktop.service;

import com.multiagent.desktop.llm.CancellationToken;
import com.multiagent.desktop.llm.ChatCompletionResult;
import com.multiagent.desktop.llm.ChatRequestMessage;
import com.multiagent.desktop.llm.ErrorCode;
import com.multiagent.desktop.llm.LlmClient;
import com.multiagent.desktop.llm.ProviderSettings;
import com.multiagent.desktop.llm.ToolCall;
import com.multiagent.desktop.llm.ToolDefinition;
import com.multiagent.desktop.model.ChatMessage;
import com.multiagent.desktop.model.Conversation;
import com.multiagent.desktop.model.ConversationKind;
import com.multiagent.desktop.model.HealthStatus;
import com.multiagent.desktop.model.ModelInfo;
import com.multiagent.desktop.model.Persona;
import com.multiagent.desktop.persistence.ConversationStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two conversations bound to the same folder are meant to be a shared workspace, not two
 * private copies of it: a file one of them writes must be visible to the other, since they
 * both resolve tool calls against the identical Conversation.workspacePath on disk. This
 * exercises the real ChatService + ToolLoopRunner + WorkspaceService pipeline end to end,
 * with a scripted fake LlmClient standing in for the network call.
 */
class ChatServiceWorkspaceSharingTest {
    private ConversationStore store;
    private ChatService chatService;
    private Persona persona;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        store = new ConversationStore(tempDir.resolve("chats.db"));
        chatService = new ChatService(store);
        persona = new Persona("general", "General", "Helpful assistant", "You are a helpful assistant.", "#0F766E");
    }

    @AfterEach
    void tearDown() {
        chatService.shutdown();
        store.close();
    }

    @Test
    void aFileWrittenByOneConversationIsVisibleToAnotherConversationInTheSameFolder(@TempDir Path workspace)
            throws InterruptedException {
        Conversation writer = store.createConversation("writer chat", ConversationKind.CHAT, workspace.toString());
        Conversation reader = store.createConversation("reader chat", ConversationKind.CHAT, workspace.toString());

        FakeLlmClient writerClient = new FakeLlmClient(List.of(
                messages -> new ChatCompletionResult(null, List.of(new ToolCall(
                        "call-1", "write_file", "{\"path\":\"shared.txt\",\"content\":\"hello from writer\"}"))),
                messages -> new ChatCompletionResult("Wrote the file.", List.of())));

        ChatMessage writerResult = sendAndAwait(writerClient, writer, "please write shared.txt");
        assertNotNull(writerResult);

        FakeLlmClient readerClient = new FakeLlmClient(List.of(
                messages -> new ChatCompletionResult(null, List.of(new ToolCall(
                        "call-2", "read_file", "{\"path\":\"shared.txt\"}"))),
                messages -> {
                    // Prove the tool result actually reached this completely separate
                    // conversation by echoing it back into the final answer.
                    String toolResult = messages.get(messages.size() - 1).content();
                    return new ChatCompletionResult("The file contains: " + toolResult, List.of());
                }));

        ChatMessage readerResult = sendAndAwait(readerClient, reader, "please read shared.txt");

        assertNotNull(readerResult);
        assertTrue(readerResult.getContent().contains("hello from writer"),
                "reader conversation should see the exact content the writer conversation persisted to disk");
    }

    private ChatMessage sendAndAwait(LlmClient client, Conversation conversation, String prompt)
            throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<ChatMessage> result = new AtomicReference<>();
        chatService.send(client, conversation, prompt, persona, "fake-model", null, 40, null, new ChatService.Listener() {
            @Override
            public void onToken(String conversationId, String messageId, String delta) {
            }

            @Override
            public void onDone(String conversationId, ChatMessage message) {
                result.set(message);
                latch.countDown();
            }

            @Override
            public void onError(String conversationId, String messageId, ErrorCode code, String message) {
                latch.countDown();
            }
        });
        assertTrue(latch.await(5, TimeUnit.SECONDS), "chatService.send() did not complete in time");
        return result.get();
    }

    /** Scripted LlmClient: returns its canned completions in order, ignoring the actual request content. */
    private static final class FakeLlmClient implements LlmClient {
        private final List<Function<List<ChatRequestMessage>, ChatCompletionResult>> completions;
        private int callIndex = 0;

        FakeLlmClient(List<Function<List<ChatRequestMessage>, ChatCompletionResult>> completions) {
            this.completions = completions;
        }

        @Override
        public void updateSettings(ProviderSettings settings) {
        }

        @Override
        public HealthStatus checkHealth() {
            return HealthStatus.ok("ok", 0);
        }

        @Override
        public List<ModelInfo> listModels() {
            return List.of();
        }

        @Override
        public List<String> listLoadedModelNames() {
            return List.of();
        }

        @Override
        public boolean supportsLoadStatus() {
            return false;
        }

        @Override
        public void ensureModelLoaded(String model, Consumer<String> onStatus, CancellationToken token) {
        }

        @Override
        public void streamChat(List<ChatRequestMessage> messages, String model, Consumer<String> onDelta,
                                CancellationToken token) {
            throw new UnsupportedOperationException("not used for workspace-bound chats");
        }

        @Override
        public ChatCompletionResult completeChat(List<ChatRequestMessage> messages, String model,
                                                  List<ToolDefinition> tools, CancellationToken token) {
            return completions.get(callIndex++).apply(messages);
        }

        @Override
        public boolean supportsImageGeneration() {
            return false;
        }
    }
}
