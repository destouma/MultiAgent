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
import com.multiagent.desktop.persistence.ConversationStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end plan -> specialist -> synthesize run against the real repo-root
 * personas/*.json (same as PersonaRegistryTest), with a scripted fake LlmClient standing
 * in for the network call, mirroring the pattern in ChatServiceWorkspaceSharingTest.
 */
class OrchestratorServiceTest {
    private ConversationStore store;
    private OrchestratorService orchestratorService;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        store = new ConversationStore(tempDir.resolve("chats.db"));
        orchestratorService = new OrchestratorService(store, new PersonaRegistry());
    }

    @AfterEach
    void tearDown() {
        orchestratorService.shutdown();
        store.close();
    }

    @Test
    void plansOneSpecialistRunsItAndSynthesizesAFinalAnswer() throws InterruptedException {
        Conversation conversation = store.createConversation("orchestrator chat", ConversationKind.ORCHESTRATOR, null);

        FakeLlmClient client = new FakeLlmClient(
                List.of(
                        // 1: planSpecialists() - choose exactly one specialist.
                        messages -> new ChatCompletionResult(
                                "{\"specialists\":[\"researcher\"],\"rationale\":\"needs research\"}", List.of()),
                        // 2: runSpecialist() for "researcher" (no workspace bound -> single completeChat call).
                        messages -> new ChatCompletionResult("Researcher's findings: the sky is blue.", List.of())),
                "Final answer: the sky is blue, per the researcher.");

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<ChatMessage> doneMessage = new AtomicReference<>();
        List<String> stepPhases = new ArrayList<>();

        orchestratorService.send(client, conversation, "why is the sky blue?", "fake-model", 40,
                new ChatService.Listener() {
                    @Override
                    public void onToken(String conversationId, String messageId, String delta) {
                    }

                    @Override
                    public void onDone(String conversationId, ChatMessage message) {
                        doneMessage.set(message);
                        latch.countDown();
                    }

                    @Override
                    public void onError(String conversationId, String messageId, ErrorCode code, String message) {
                        latch.countDown();
                    }

                    @Override
                    public void onStep(String conversationId, String phase, String personaId, String label) {
                        stepPhases.add(phase);
                    }
                });

        assertTrue(latch.await(5, TimeUnit.SECONDS), "orchestratorService.send() did not complete in time");
        assertNotNull(doneMessage.get());
        assertTrue(doneMessage.get().getContent().contains("the sky is blue, per the researcher"));

        // Phases fire in order: planning -> specialist -> synthesizing -> done.
        assertEquals(List.of("planning", "specialist", "synthesizing", "done"), stepPhases);

        List<ChatMessage> persisted = store.getMessages(conversation.getId());
        assertEquals(3, persisted.size(), "user message + one specialist note + final synthesis");
        assertEquals("researcher", persisted.get(1).getPersonaId());
        assertTrue(persisted.get(1).getContent().contains("the sky is blue"));
        assertEquals("orchestrator", persisted.get(2).getPersonaId());
    }

    @Test
    void fallsBackToTheDefaultSpecialistWhenThePlanIsMalformed() throws InterruptedException {
        Conversation conversation = store.createConversation("orchestrator chat", ConversationKind.ORCHESTRATOR, null);

        FakeLlmClient client = new FakeLlmClient(
                List.of(
                        messages -> new ChatCompletionResult("not valid json at all", List.of()),
                        messages -> new ChatCompletionResult("Researcher note.", List.of())),
                "Final answer.");

        CountDownLatch latch = new CountDownLatch(1);
        orchestratorService.send(client, conversation, "hello", "fake-model", 40, new ChatService.Listener() {
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
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        List<ChatMessage> persisted = store.getMessages(conversation.getId());
        // Default plan falls back to a single "researcher" specialist, so the shape is the same.
        assertEquals(3, persisted.size());
        assertEquals("researcher", persisted.get(1).getPersonaId());
    }

    @Test
    void aSpecialistCanRunReadOnlyGitToolsButNotGitAddOrGitCommit(@TempDir Path repo) throws Exception {
        Assumptions.assumeTrue(hasGit(), "git is not on PATH - skipping");
        initRepo(repo);
        Conversation conversation = store.createConversation(
                "orchestrator chat", ConversationKind.ORCHESTRATOR, repo.toString());

        FakeLlmClient client = new FakeLlmClient(
                List.of(
                        // 1: plan - one specialist.
                        messages -> new ChatCompletionResult(
                                "{\"specialists\":[\"researcher\"],\"rationale\":\"inspect history\"}", List.of()),
                        // 2: specialist round 1 - one allowed git tool, one forbidden one.
                        messages -> new ChatCompletionResult(null, List.of(
                                new ToolCall("c1", "git_log", "{\"count\":5}"),
                                new ToolCall("c2", "git_add", "{\"path\":\".\"}"))),
                        // 3: specialist round 2 - no more tools, emit the note.
                        messages -> new ChatCompletionResult("Inspected the log.", List.of())),
                "Final answer.");

        ConcurrentLinkedQueue<String> ops = new ConcurrentLinkedQueue<>();
        CountDownLatch latch = new CountDownLatch(1);
        orchestratorService.send(client, conversation, "what's in the history?", "fake-model", 40,
                new ChatService.Listener() {
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
                    public void onWorkspaceOp(String conversationId, String messageId, String op, String path,
                                              String status, String detail, String checkpointId) {
                        ops.add(op + ":" + status + ":" + (detail == null ? "" : detail));
                    }
                });

        assertTrue(latch.await(10, TimeUnit.SECONDS), "orchestratorService.send() did not complete in time");

        assertTrue(ops.stream().anyMatch(s -> s.startsWith("git_log:ok:") && s.contains("initial commit")),
                "git_log should succeed for a specialist, ops were: " + ops);
        assertTrue(ops.stream().anyMatch(s -> s.startsWith("git_add:error:") && s.contains("not available to specialists")),
                "git_add must be refused for a specialist, ops were: " + ops);
    }

    private static boolean hasGit() {
        try {
            Process process = new ProcessBuilder("git", "--version").redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            return process.waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    private static void initRepo(Path dir) throws IOException, InterruptedException {
        runGit(dir, "init", "-q");
        runGit(dir, "config", "user.email", "test@example.com");
        runGit(dir, "config", "user.name", "Test User");
        runGit(dir, "config", "commit.gpgsign", "false");
        Files.writeString(dir.resolve("README.md"), "hello\n");
        runGit(dir, "add", "README.md");
        runGit(dir, "commit", "-q", "-m", "initial commit");
    }

    private static void runGit(Path dir, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(Arrays.asList(args));
        Process process = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " failed:\n" + output);
        }
    }

    /** Scripted LlmClient: completeChat() replays canned responses in order; streamChat() emits one fixed final answer. */
    private static final class FakeLlmClient implements LlmClient {
        private final List<Function<List<ChatRequestMessage>, ChatCompletionResult>> completions;
        private final String synthesizedAnswer;
        private int callIndex = 0;

        FakeLlmClient(List<Function<List<ChatRequestMessage>, ChatCompletionResult>> completions,
                       String synthesizedAnswer) {
            this.completions = completions;
            this.synthesizedAnswer = synthesizedAnswer;
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
            onDelta.accept(synthesizedAnswer);
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
