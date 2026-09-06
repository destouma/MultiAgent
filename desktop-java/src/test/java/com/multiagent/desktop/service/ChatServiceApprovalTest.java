package com.multiagent.desktop.service;

import com.multiagent.desktop.action.ActionApprover;
import com.multiagent.desktop.action.PendingAction;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The "ask before writing/deleting/renaming a file" gate (ToolLoopRunner + ActionApprover)
 * end to end: a declined write must not touch disk and must surface as a graceful tool
 * error, not an exception; an approved one proceeds exactly as before the gate existed.
 */
class ChatServiceApprovalTest {
    private ConversationStore store;
    private ChatService chatService;
    private Persona persona;
    private Path workspace;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws Exception {
        workspace = Files.createDirectories(tempDir.resolve("workspace"));
        store = new ConversationStore(tempDir.resolve("chats.db"));
        chatService = new ChatService(store);
        persona = new Persona("general", "General", "desc", "You are a helpful assistant.", "#0F766E");
    }

    @AfterEach
    void tearDown() {
        chatService.shutdown();
        store.close();
    }

    @Test
    void aDeclinedWriteNeverTouchesDiskAndReportsTheDeclineToTheModel() throws Exception {
        Conversation conversation = store.createConversation("chat", ConversationKind.CHAT, workspace.toString());
        RecordingApprover approver = new RecordingApprover(false);
        chatService.setActionApprover(approver);

        FakeLlmClient client = new FakeLlmClient(List.of(
                messages -> new ChatCompletionResult(null, List.of(new ToolCall(
                        "call-1", "write_file", "{\"path\":\"notes.txt\",\"content\":\"hello\"}"))),
                messages -> {
                    // The tool result the model sees must reflect the decline so it can adapt.
                    String toolResult = messages.get(messages.size() - 1).content();
                    return new ChatCompletionResult("Understood: " + toolResult, List.of());
                }));

        ChatMessage result = sendAndAwait(client, conversation, "please write notes.txt");

        assertEquals(1, approver.callCount());
        assertFalse(Files.exists(workspace.resolve("notes.txt")), "declined write must not touch disk");
        assertTrue(result.getContent().contains("declined"));
    }

    @Test
    void anApprovedWriteProceedsNormally() throws Exception {
        Conversation conversation = store.createConversation("chat", ConversationKind.CHAT, workspace.toString());
        RecordingApprover approver = new RecordingApprover(true);
        chatService.setActionApprover(approver);

        FakeLlmClient client = new FakeLlmClient(List.of(
                messages -> new ChatCompletionResult(null, List.of(new ToolCall(
                        "call-1", "write_file", "{\"path\":\"notes.txt\",\"content\":\"hello\"}"))),
                messages -> new ChatCompletionResult("Done.", List.of())));

        sendAndAwait(client, conversation, "please write notes.txt");

        assertEquals(1, approver.callCount());
        assertEquals("hello", Files.readString(workspace.resolve("notes.txt")));
    }

    @Test
    void readOnlyToolsNeverPromptForApproval() throws Exception {
        Files.writeString(workspace.resolve("notes.txt"), "hello");
        Conversation conversation = store.createConversation("chat", ConversationKind.CHAT, workspace.toString());
        RecordingApprover approver = new RecordingApprover(true);
        chatService.setActionApprover(approver);

        FakeLlmClient client = new FakeLlmClient(List.of(
                messages -> new ChatCompletionResult(null, List.of(new ToolCall(
                        "call-1", "read_file", "{\"path\":\"notes.txt\"}"))),
                messages -> new ChatCompletionResult("It says hello.", List.of())));

        sendAndAwait(client, conversation, "please read notes.txt");

        assertEquals(0, approver.callCount(), "read_file must never require approval");
    }

    @Test
    void noApproverInstalledAutoApprovesAsATestingConvenience() throws Exception {
        // No setActionApprover() call at all - matches every other test in this module,
        // and confirms that behavior is intentional rather than an oversight.
        Conversation conversation = store.createConversation("chat", ConversationKind.CHAT, workspace.toString());
        FakeLlmClient client = new FakeLlmClient(List.of(
                messages -> new ChatCompletionResult(null, List.of(new ToolCall(
                        "call-1", "write_file", "{\"path\":\"notes.txt\",\"content\":\"hello\"}"))),
                messages -> new ChatCompletionResult("Done.", List.of())));

        sendAndAwait(client, conversation, "please write notes.txt");

        assertEquals("hello", Files.readString(workspace.resolve("notes.txt")));
    }

    @Test
    void modelLoadStatusIsForwardedToTheListenerBeforeGenerationStarts() throws Exception {
        // Workspace-bound so send() runs the tool loop (which uses completeChat), not
        // streamChat - this FakeLlmClient's streamChat deliberately throws.
        Conversation conversation = store.createConversation("chat", ConversationKind.CHAT, workspace.toString());
        FakeLlmClient client = new FakeLlmClient(List.of(
                messages -> new ChatCompletionResult("hi", List.of())));

        List<String> statuses = new java.util.concurrent.CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);
        chatService.send(client, conversation, "hello", persona, "fake-model", 40, new ChatService.Listener() {
            @Override
            public void onToken(String conversationId, String messageId, String delta) {
            }

            @Override
            public void onDone(String conversationId, ChatMessage message) {
                latch.countDown();
            }

            @Override
            public void onError(String conversationId, String messageId, ErrorCode code, String message) {
                latch.countDown();
            }

            @Override
            public void onModelStatus(String conversationId, String status) {
                statuses.add(status);
            }
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS), "send did not complete in time");
        assertEquals(List.of("Loading fake-model...", "fake-model is ready"), statuses);
    }

    private ChatMessage sendAndAwait(LlmClient client, Conversation conversation, String prompt) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<ChatMessage> result = new AtomicReference<>();
        chatService.send(client, conversation, prompt, persona, "fake-model", 40, new ChatService.Listener() {
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
        assertNotNull(result.get());
        return result.get();
    }

    private static final class RecordingApprover implements ActionApprover {
        private final boolean decision;
        private int callCount = 0;

        RecordingApprover(boolean decision) {
            this.decision = decision;
        }

        @Override
        public synchronized boolean approve(PendingAction action) {
            callCount++;
            return decision;
        }

        synchronized int callCount() {
            return callCount;
        }
    }

    /** Scripted LlmClient: completeChat() replays canned responses in order. */
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
            if (onStatus != null) {
                onStatus.accept("Loading " + model + "...");
                onStatus.accept(model + " is ready");
            }
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
