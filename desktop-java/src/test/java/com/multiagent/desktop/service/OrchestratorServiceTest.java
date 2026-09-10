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
import com.multiagent.desktop.model.ImageAttachment;
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
import static org.junit.jupiter.api.Assertions.assertNull;
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

        orchestratorService.send(client, conversation, "why is the sky blue?", "fake-model", 40, java.util.Map.of(), 0,
                null, null, new ChatService.Listener() {
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
        orchestratorService.send(client, conversation, "hello", "fake-model", 40, java.util.Map.of(), 0, null, null, new ChatService.Listener() {
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
        // Malformed plan -> fall back to a single specialist (the first available id, "general"
        // by PersonaRegistry's preferred order), so the shape is still user + note + synthesis.
        assertEquals(3, persisted.size());
        assertEquals("general", persisted.get(1).getPersonaId());
    }

    @Test
    void aSpecialistCanUseSearchFileOnALargeWorkspaceFile(@TempDir Path ws) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 500; i++) {
            sb.append(i == 314 ? "the SECRET marker is here\n" : "filler line " + i + "\n");
        }
        Files.writeString(ws.resolve("big.log"), sb.toString());
        Conversation conversation = store.createConversation(
                "orchestrator chat", ConversationKind.ORCHESTRATOR, ws.toString());

        FakeLlmClient client = new FakeLlmClient(
                List.of(
                        messages -> new ChatCompletionResult(
                                "{\"specialists\":[\"researcher\"],\"rationale\":\"scan the log\"}", List.of()),
                        messages -> new ChatCompletionResult(null, List.of(
                                new ToolCall("c1", "search_file", "{\"path\":\"big.log\",\"pattern\":\"SECRET\"}"))),
                        messages -> new ChatCompletionResult("Found the marker.", List.of())),
                "Final answer.");

        ConcurrentLinkedQueue<String> ops = new ConcurrentLinkedQueue<>();
        CountDownLatch latch = new CountDownLatch(1);
        orchestratorService.send(client, conversation, "find the secret", "fake-model", 40, java.util.Map.of(), 0,
                null, null, new ChatService.Listener() {
                    @Override public void onToken(String c, String m, String d) { }
                    @Override public void onDone(String c, ChatMessage m) { latch.countDown(); }
                    @Override public void onError(String c, String m, ErrorCode e, String msg) { latch.countDown(); }
                    @Override public void onWorkspaceOp(String c, String m, String op, String p, String s,
                                                       String detail, String cp) {
                        ops.add(op + ":" + s + ":" + (detail == null ? "" : detail));
                    }
                });

        assertTrue(latch.await(10, TimeUnit.SECONDS));
        assertTrue(ops.stream().anyMatch(s -> s.startsWith("search_file:ok:") && s.contains("314:")),
                "search_file should succeed for a specialist and report line 314, ops were: " + ops);
    }

    @Test
    void aSpecialistCanRunGitToolsIncludingGitAddInABoundRepo(@TempDir Path repo) throws Exception {
        Assumptions.assumeTrue(hasGit(), "git is not on PATH - skipping");
        initRepo(repo);
        Conversation conversation = store.createConversation(
                "orchestrator chat", ConversationKind.ORCHESTRATOR, repo.toString());

        FakeLlmClient client = new FakeLlmClient(
                List.of(
                        messages -> new ChatCompletionResult(
                                "{\"specialists\":[\"researcher\"],\"rationale\":\"inspect + stage\"}", List.of()),
                        // specialist round 1 - a read tool and a mutating one; both allowed now
                        // (git_add is approval-gated, and the test's null approver auto-approves).
                        messages -> new ChatCompletionResult(null, List.of(
                                new ToolCall("c1", "git_log", "{\"count\":5}"),
                                new ToolCall("c2", "git_add", "{\"path\":\".\"}"))),
                        messages -> new ChatCompletionResult("Inspected and staged.", List.of())),
                "Final answer.");

        ConcurrentLinkedQueue<String> ops = new ConcurrentLinkedQueue<>();
        CountDownLatch latch = new CountDownLatch(1);
        orchestratorService.send(client, conversation, "what's in the history?", "fake-model", 40, java.util.Map.of(), 0,
                null, null, new ChatService.Listener() {
                    @Override public void onToken(String c, String m, String d) { }
                    @Override public void onDone(String c, ChatMessage m) { latch.countDown(); }
                    @Override public void onError(String c, String m, ErrorCode e, String msg) { latch.countDown(); }
                    @Override public void onWorkspaceOp(String c, String m, String op, String path, String status,
                                                       String detail, String cp) {
                        ops.add(op + ":" + status + ":" + (detail == null ? "" : detail));
                    }
                });

        assertTrue(latch.await(10, TimeUnit.SECONDS), "orchestratorService.send() did not complete in time");
        assertTrue(ops.stream().anyMatch(s -> s.startsWith("git_log:ok:") && s.contains("initial commit")),
                "git_log should succeed for a specialist, ops were: " + ops);
        assertTrue(ops.stream().anyMatch(s -> s.startsWith("git_add:ok:")),
                "git_add is now available to specialists (approval-gated), ops were: " + ops);
    }

    /**
     * The reported scenario: an orchestrator chat with a folder bound, asked to write files.
     * The specialist itself writes them through the shared tool loop - no separate executor
     * step, no opt-in. Every write is approval-gated (the test's null approver auto-approves).
     */
    @Test
    void aSpecialistWritesFilesDirectlyToTheBoundWorkspace(@TempDir Path ws) throws Exception {
        Conversation conversation = store.createConversation(
                "propose an architecture", ConversationKind.ORCHESTRATOR, ws.toString());

        FakeLlmClient client = new FakeLlmClient(
                List.of(
                        messages -> new ChatCompletionResult(
                                "{\"specialists\":[\"coder\"],\"rationale\":\"scaffold it\"}", List.of()),
                        // specialist round 1 - write the file
                        messages -> new ChatCompletionResult(null, List.of(new ToolCall(
                                "w1", "write_file",
                                "{\"path\":\"src/main.rs\",\"content\":\"fn main() {}\\n\"}"))),
                        // specialist round 2 - summary, no more tools
                        messages -> new ChatCompletionResult("Created src/main.rs with the entry point.", List.of())),
                "Scaffolded the project - see src/main.rs.");

        ConcurrentLinkedQueue<String> ops = new ConcurrentLinkedQueue<>();
        List<String> stepPhases = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);
        orchestratorService.send(client, conversation, "write the files in the folders", "fake-model", 40,
                java.util.Map.of(), 0, null, null, new ChatService.Listener() {
                    @Override public void onToken(String c, String m, String d) { }
                    @Override public void onDone(String c, ChatMessage m) { latch.countDown(); }
                    @Override public void onError(String c, String m, ErrorCode e, String msg) { latch.countDown(); }
                    @Override public void onStep(String c, String phase, String personaId, String label) {
                        stepPhases.add(phase);
                    }
                    @Override public void onWorkspaceOp(String c, String m, String op, String p, String s,
                                                       String detail, String cp) {
                        ops.add(op + ":" + s);
                    }
                });

        assertTrue(latch.await(10, TimeUnit.SECONDS));
        assertTrue(ops.stream().anyMatch(s -> s.equals("write_file:ok")), "write_file should fire, ops were: " + ops);
        assertEquals("fn main() {}\n", Files.readString(ws.resolve("src/main.rs")));
        assertTrue(stepPhases.stream().noneMatch(p -> p.equals("executing")),
                "there is no separate executor step any more, phases were: " + stepPhases);

        List<ChatMessage> persisted = store.getMessages(conversation.getId());
        assertEquals("coder", persisted.get(1).getPersonaId());
        assertTrue(persisted.get(1).getContent().contains("src/main.rs"),
                "the specialist note should be its own summary: " + persisted.get(1).getContent());
    }

    @Test
    void synthesisIsToldToGroundFileClaimsInTheSpecialistNotes(@TempDir Path ws) throws Exception {
        Files.writeString(ws.resolve("notes.txt"), "hi\n");
        Conversation conversation = store.createConversation(
                "orchestrator chat", ConversationKind.ORCHESTRATOR, ws.toString());

        FakeLlmClient client = new FakeLlmClient(
                List.of(
                        messages -> new ChatCompletionResult(
                                "{\"specialists\":[\"coder\"],\"rationale\":\"draft it\"}", List.of()),
                        messages -> new ChatCompletionResult("Here is the code.", List.of())),
                "Final answer.");

        CountDownLatch latch = new CountDownLatch(1);
        orchestratorService.send(client, conversation, "write the files in the folder", "fake-model", 40,
                java.util.Map.of(), 0, null, null, new ChatService.Listener() {
                    @Override public void onToken(String c, String m, String d) { }
                    @Override public void onDone(String c, ChatMessage m) { latch.countDown(); }
                    @Override public void onError(String c, String m, ErrorCode e, String msg) { latch.countDown(); }
                });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertTrue(client.lastStreamSystemPrompt.contains("do NOT invent writes"),
                "synthesis prompt should ground file claims in the notes: " + client.lastStreamSystemPrompt);
    }

    /**
     * The vision step: with a vision model set and an image attached, one specialist examines
     * the image <em>inline</em> on the vision model (the same multimodal chat call a regular
     * chat's inline mode uses - not describe_image) before planning. Its note is persisted as
     * its own specialist message and folded into the shared context, so the planner, the
     * other specialists, and the synthesis all work from a real description.
     */
    @Test
    void anAttachedImageIsExaminedInlineByAVisionSpecialist() throws InterruptedException {
        Conversation conversation = store.createConversation(
                "orchestrator chat", ConversationKind.ORCHESTRATOR, null);

        AtomicReference<Boolean> visionCallHadImage = new AtomicReference<>(false);
        AtomicReference<String> planUserBlock = new AtomicReference<>();
        FakeLlmClient client = new FakeLlmClient(
                List.of(
                        // 1: examineImage() - the inline vision call; must carry the image.
                        messages -> {
                            visionCallHadImage.set(messages.stream().anyMatch(m -> m.image() != null));
                            return new ChatCompletionResult(
                                    "A login screen with a red banner reading \"Invalid password\".", List.of());
                        },
                        // 2: planSpecialists() - its user block now contains the vision note.
                        messages -> {
                            planUserBlock.set(messages.get(messages.size() - 1).content());
                            return new ChatCompletionResult(
                                    "{\"specialists\":[\"coder\"],\"rationale\":\"dig in\"}", List.of());
                        },
                        // 3: the "coder" specialist (no workspace -> single completeChat).
                        messages -> new ChatCompletionResult("Coder note.", List.of())),
                "Final answer referencing the invalid password banner.");

        CountDownLatch latch = new CountDownLatch(1);
        List<String> specialistStepPersonas = new ArrayList<>();
        ImageAttachment image = new ImageAttachment("shot.png", "image/png", new byte[] {1, 2, 3});
        orchestratorService.send(client, conversation, "what's wrong here?", "fake-model", 40, java.util.Map.of(), 0,
                "vision-model", image, new ChatService.Listener() {
                    @Override public void onToken(String c, String m, String d) { }
                    @Override public void onDone(String c, ChatMessage m) { latch.countDown(); }
                    @Override public void onError(String c, String m, ErrorCode e, String msg) { latch.countDown(); }
                    @Override public void onStep(String c, String phase, String personaId, String label) {
                        if ("specialist".equals(phase)) {
                            specialistStepPersonas.add(personaId);
                        }
                    }
                });

        assertTrue(latch.await(5, TimeUnit.SECONDS), "orchestratorService.send() did not complete in time");
        assertTrue(visionCallHadImage.get(), "the vision step must send the image inline");
        assertEquals("researcher", specialistStepPersonas.get(0),
                "the vision step runs first, as the 'researcher' viewer: " + specialistStepPersonas);
        assertNotNull(planUserBlock.get());
        assertTrue(planUserBlock.get().contains("Invalid password"),
                "the vision note should reach the planner: " + planUserBlock.get());

        List<ChatMessage> persisted = store.getMessages(conversation.getId());
        assertTrue(persisted.get(0).getContent().contains("[🖼️ shot.png]"),
                "user message keeps only the marker: " + persisted.get(0).getContent());
        // user + vision note + coder note + synthesis
        assertEquals(4, persisted.size(), "the vision note is persisted as its own specialist message");
        assertEquals("researcher", persisted.get(1).getPersonaId());
        assertTrue(persisted.get(1).getContent().contains("Invalid password"),
                "the vision note is persisted verbatim: " + persisted.get(1).getContent());
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
        /** The system prompt from the most recent streamChat call (the synthesis step). */
        volatile String lastStreamSystemPrompt;

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
            lastStreamSystemPrompt = messages.stream()
                    .filter(m -> "system".equals(m.role()))
                    .map(ChatRequestMessage::content)
                    .findFirst().orElse("");
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
