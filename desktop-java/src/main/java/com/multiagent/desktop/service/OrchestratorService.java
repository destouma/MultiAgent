package com.multiagent.desktop.service;

import com.multiagent.desktop.action.ActionApprover;
import com.multiagent.desktop.llm.CancellationToken;
import com.multiagent.desktop.llm.ChatCompletionResult;
import com.multiagent.desktop.llm.ChatRequestMessage;
import com.multiagent.desktop.llm.ErrorCode;
import com.multiagent.desktop.llm.LlmClient;
import com.multiagent.desktop.llm.ProviderException;
import com.multiagent.desktop.model.ChatMessage;
import com.multiagent.desktop.model.Conversation;
import com.multiagent.desktop.model.ImageAttachment;
import com.multiagent.desktop.model.MessageRole;
import com.multiagent.desktop.model.Persona;
import com.multiagent.desktop.persistence.ConversationStore;
import com.multiagent.desktop.workspace.WorkspaceService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * Plan -> specialists -> synthesize workflow for {@code kind == orchestrator} conversations,
 * mirroring {@code desktop/electron/services/orchestratorService.ts}. Each user message:
 * (1) asks the coordinator persona which specialists should help, (2) runs each chosen
 * specialist in turn, persisting its note as its own message as it completes, then
 * (3) streams a final synthesized answer from the notes.
 *
 * <p>When a workspace folder is bound, each specialist runs through the shared
 * {@link ToolLoopRunner} - the same read <em>and write</em> tool set a normal workspace chat
 * gets ({@code list_dir}/{@code read_file}/{@code search_file} plus
 * {@code write_file}/{@code delete_file}/{@code rename_file} and the {@code git_*} tools),
 * every mutating call gated behind the {@link ActionApprover} dialog and captured as a
 * checkpoint. Binding a folder means the agent can work in it; there is no separate
 * "apply changes" step. With no workspace bound a specialist is a plain text completion.
 */
public class OrchestratorService {

    private record SpecialistNote(Persona persona, String content) {
    }

    private final ConversationStore store;
    private final PersonaRegistry personas;
    private final WorkspaceService workspace = new WorkspaceService();
    // The shared write/git tool loop - specialists run through it when a workspace is bound,
    // behind the same ActionApprover gate as ChatService's.
    private final ToolLoopRunner toolLoop;
    private final Map<String, CancellationToken> activeTokens = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "orchestrator-service-worker");
        thread.setDaemon(true);
        return thread;
    });

    public OrchestratorService(ConversationStore store, PersonaRegistry personas) {
        this.store = store;
        this.personas = personas;
        this.toolLoop = new ToolLoopRunner(store);
    }

    /** Installs the write/delete/rename/git confirmation gate specialists run behind (App wires the real one). */
    public void setActionApprover(ActionApprover approver) {
        toolLoop.setActionApprover(approver);
    }

    public void send(LlmClient client, Conversation conversation, String content, String fallbackModel,
                      int maxHistory, Map<String, String> specialistModels, int contextTokens,
                      String visionModel, ImageAttachment image, ChatService.Listener listener) {
        // Mirror ChatService: the transcript keeps only a "[🖼️ name]" marker, the image itself
        // is never persisted - a vision specialist examines it inline below (see the vision
        // step in the executor task) and its written note carries the content onward.
        String persistedContent = image != null
                ? (content.isBlank() ? "" : content + "\n\n") + "[🖼️ " + image.name() + "]"
                : content;
        store.addMessage(conversation.getId(), MessageRole.USER, persistedContent, null);

        Map<String, String> overrides = specialistModels == null ? Map.of() : specialistModels;
        // The "Coordinator" topbar box picks the persona that runs the plan + synthesis;
        // fall back to the dedicated orchestrator persona, then general.
        String coordinatorId = conversation.getPersonaId();
        Persona orchestrator = (coordinatorId == null || coordinatorId.isBlank()
                ? Optional.<Persona>empty() : personas.get(coordinatorId))
                .or(() -> personas.get("orchestrator"))
                .or(() -> personas.get("general"))
                .orElseGet(() -> personas.list().get(0));
        String model = orchestrator.getDefaultModel() != null && !orchestrator.getDefaultModel().isBlank()
                ? orchestrator.getDefaultModel() : fallbackModel;
        int reserve = contextTokens > 0 ? Math.max(512, Math.min(contextTokens / 4, 4096)) : 0;
        int maxTokens = reserve;

        CancellationToken token = new CancellationToken();
        activeTokens.put(conversation.getId(), token);
        String assistantMessageId = UUID.randomUUID().toString();

        boolean hasVision = image != null && visionModel != null && !visionModel.isBlank();

        executor.submit(() -> {
            try {
                // An attached image is handled the "regular chat" way - shown inline to a
                // model that can actually see it - not transcribed through describe_image.
                // One dedicated vision specialist runs first (below), examines the image on
                // the vision model, and writes a note; that note is folded into userContent
                // and added to the specialist notes, so the planner, every later specialist,
                // and the synthesis all work from a real description without seeing bytes.
                String userContent = content;
                if (image != null && !hasVision) {
                    String note = "[Image \"" + image.name() + "\" attached, but no vision model is "
                            + "configured for this chat, so it could not be read.]";
                    userContent = content.isBlank() ? note : content + "\n\n" + note;
                }

                client.ensureModelLoaded(model,
                        status -> listener.onModelStatus(conversation.getId(), status), token);

                String workspacePath = conversation.getWorkspacePath();
                boolean hasWorkspace = workspacePath != null && !workspacePath.isBlank();
                String workspaceTree = null;
                if (hasWorkspace) {
                    try {
                        workspaceTree = workspace.buildTree(workspacePath);
                    } catch (RuntimeException e) {
                        workspaceTree = e.getMessage();
                    }
                }

                listener.onStep(conversation.getId(), "planning", orchestrator.getId(),
                        "Planning which specialists to use...");

                List<ChatMessage> history = store.getMessages(conversation.getId());
                int from = Math.max(0, history.size() - maxHistory);
                List<String> entries = history.subList(from, history.size()).stream()
                        .filter(m -> m.getRole() == MessageRole.USER || m.getRole() == MessageRole.ASSISTANT)
                        .map(m -> {
                            String who = m.getRole() == MessageRole.USER ? "User"
                                    : (m.getPersonaId() != null ? "Assistant(" + m.getPersonaId() + ")" : "Assistant");
                            return who + ": " + m.getContent();
                        })
                        .collect(Collectors.toCollection(ArrayList::new));
                if (contextTokens > 0) {
                    int budget = contextTokens - reserve;
                    while (entries.size() > 1
                            && TokenEstimate.estimateTokens(entries) + TokenEstimate.estimateTokens(List.of(userContent)) > budget) {
                        entries.remove(0);
                    }
                }
                String priorContext = String.join("\n\n", entries);

                List<SpecialistNote> notes = new ArrayList<>();

                // Vision step: before planning, one specialist looks at the attached image on
                // the vision model (inline, exactly like a regular multimodal chat) and its
                // note becomes the image's description for the rest of the workflow.
                if (hasVision) {
                    token.throwIfCancelled();
                    Persona viewer = personas.get("researcher")
                            .or(() -> personas.get("general"))
                            .or(() -> personas.get("critic"))
                            .orElseGet(() -> personas.list().stream()
                                    .filter(p -> !"orchestrator".equals(p.getId()))
                                    .findFirst().orElse(orchestrator));
                    listener.onStep(conversation.getId(), "specialist", viewer.getId(),
                            viewer.getName() + " is examining " + image.name() + "...");
                    String imageNote = examineImage(client, viewer, visionModel, maxTokens, content, image,
                            conversation.getId(), token, listener);
                    store.addMessage(conversation.getId(), MessageRole.ASSISTANT, imageNote, viewer.getId());
                    listener.onMessagesUpdated(conversation.getId());
                    notes.add(new SpecialistNote(viewer, imageNote));
                    String block = "[Image \"" + image.name() + "\" - examined by " + viewer.getName() + ":]\n" + imageNote;
                    userContent = userContent.isBlank() ? block : userContent + "\n\n" + block;
                }

                PlanParser.PlanResult plan = planSpecialists(client, orchestrator, model, maxTokens, userContent,
                        priorContext, workspacePath, workspaceTree, token);

                for (String specialistId : plan.specialists()) {
                    token.throwIfCancelled();
                    Optional<Persona> maybePersona = personas.get(specialistId);
                    if (maybePersona.isEmpty()) {
                        continue;
                    }
                    Persona persona = maybePersona.get();
                    String specialistModel = specialistModelFor(persona, overrides, model);

                    listener.onStep(conversation.getId(), "specialist", persona.getId(),
                            persona.getName() + " (" + specialistModel + ") is working...");

                    String specialistContent = runSpecialist(client, persona, specialistModel, maxTokens, userContent,
                            priorContext, plan.rationale(), workspacePath, workspaceTree, conversation.getId(),
                            assistantMessageId, token, listener);

                    store.addMessage(conversation.getId(), MessageRole.ASSISTANT, specialistContent, persona.getId());
                    listener.onMessagesUpdated(conversation.getId());
                    notes.add(new SpecialistNote(persona, specialistContent));
                }

                listener.onStep(conversation.getId(), "synthesizing", orchestrator.getId(),
                        "Synthesizing final answer...");

                StringBuilder full = new StringBuilder();
                synthesize(client, orchestrator, model, maxTokens, userContent, plan.rationale(), notes, workspacePath,
                        workspaceTree, token, delta -> {
                            full.append(delta);
                            listener.onToken(conversation.getId(), assistantMessageId, delta);
                        });
                String finalText = full.toString().trim();
                if (finalText.isEmpty()) {
                    finalText = "I could not produce a final answer from the specialist notes.";
                }

                listener.onStep(conversation.getId(), "done", orchestrator.getId(), "Done");

                ChatMessage saved = store.addMessage(conversation.getId(), MessageRole.ASSISTANT, finalText,
                        orchestrator.getId());
                listener.onDone(conversation.getId(), saved);
            } catch (ProviderException e) {
                listener.onError(conversation.getId(), assistantMessageId, e.code(), e.getMessage());
            } catch (Exception e) {
                listener.onError(conversation.getId(), assistantMessageId, ErrorCode.UNKNOWN,
                        e.getMessage() != null ? e.getMessage() : e.toString());
            } finally {
                activeTokens.remove(conversation.getId());
            }
        });
    }

    /**
     * The vision step: shows the attached image inline to the vision model (the same
     * multimodal {@code completeChat} path a regular chat's inline mode uses - not the
     * separate {@code describe_image} call) and returns its written description, which the
     * caller persists as {@code viewer}'s specialist note and folds into the shared context.
     * A canned "I can't see images" reply ({@link VisionResponses#looksLikeRefusal}) or any
     * VLM error degrades to a short note rather than aborting the turn.
     */
    private String examineImage(LlmClient client, Persona viewer, String visionModel, int maxTokens,
                                 String userRequest, ImageAttachment image, String conversationId,
                                 CancellationToken token, ChatService.Listener listener) {
        String ask = userRequest == null || userRequest.isBlank()
                ? "Describe this image in full detail: any text, code, diagrams, tables, UI elements, and errors."
                : "The user's request is:\n" + userRequest + "\n\nDescribe this image in full detail with that "
                        + "request in mind - transcribe any text, code, diagrams, tables, UI elements, and errors.";
        List<ChatRequestMessage> messages = List.of(
                ChatRequestMessage.system(viewer.getSystemPrompt() + "\n\n"
                        + "You are the only specialist who can see the attached image. Describe it thoroughly and "
                        + "factually - other specialists and the coordinator will rely entirely on your note."),
                ChatRequestMessage.userWithImage(ask, image));
        try {
            client.ensureModelLoaded(visionModel,
                    status -> listener.onModelStatus(conversationId, status), token);
            ChatCompletionResult result = client.completeChat(messages, visionModel, List.of(), maxTokens, token);
            String description = result.content() == null ? "" : result.content().strip();
            return VisionResponses.looksLikeRefusal(description)
                    ? "The attached image \"" + image.name() + "\" could not be read by the vision model ("
                        + visionModel + "); its contents are unavailable."
                    : description;
        } catch (RuntimeException e) {
            return "Could not read the attached image \"" + image.name() + "\": "
                    + (e.getMessage() != null ? e.getMessage() : e.toString());
        }
    }

    /** Every loaded persona except the orchestrator itself is a candidate specialist. */
    private List<String> availableSpecialistIds() {
        return personas.list().stream()
                .map(Persona::getId)
                .filter(id -> !"orchestrator".equals(id))
                .toList();
    }

    private String specialistModelFor(Persona persona, Map<String, String> overrides, String conversationModel) {
        String override = overrides.get(persona.getId());
        if (override != null && !override.isBlank()) {
            return override;
        }
        if (persona.getDefaultModel() != null && !persona.getDefaultModel().isBlank()) {
            return persona.getDefaultModel();
        }
        return conversationModel;
    }

    private PlanParser.PlanResult planSpecialists(LlmClient client, Persona orchestrator, String model, int maxTokens,
                                                    String userContent, String priorContext, String workspacePath,
                                                    String workspaceTree, CancellationToken token) {
        List<String> availableIds = availableSpecialistIds();
        String available = availableIds.stream()
                .map(id -> personas.get(id).map(p -> id + " (" + p.getName() + ")").orElse(id))
                .collect(Collectors.joining(", "));

        List<String> systemLines = new ArrayList<>(List.of(
                orchestrator.getSystemPrompt(),
                "",
                "Your job now is ONLY to choose which specialists should help with the user request.",
                "Available specialist ids: " + available,
                "Respond with JSON only, no markdown:",
                "{\"specialists\":[\"researcher\"],\"rationale\":\"why these were chosen\"}",
                "Rules:",
                "- specialists must be a subset of the available ids",
                "- pick 1-3 specialists; omit any that are not useful",
                "- if the request is simple, pick one specialist or an empty array",
                "- do not answer the user yet"));
        if (workspaceTree != null) {
            systemLines.addAll(List.of("",
                    "A workspace folder is bound to this conversation: " + workspacePath,
                    "Specialists you pick can read and write files in it (every write is approved by the user).",
                    "Workspace tree (top levels only; \"…\" means there is more under that folder):", workspaceTree));
        }

        String userBlock = (priorContext != null && !priorContext.isBlank() ? "Conversation so far:\n" + priorContext + "\n" : "")
                + "Latest user request:\n" + userContent;

        List<ChatRequestMessage> messages = List.of(
                ChatRequestMessage.system(String.join("\n", systemLines)),
                ChatRequestMessage.user(userBlock));

        ChatCompletionResult completion = client.completeChat(messages, model, List.of(), maxTokens, token);
        return PlanParser.parsePlan(completion.content(), availableIds);
    }

    private String runSpecialist(LlmClient client, Persona persona, String specialistModel, int maxTokens,
                                  String userContent, String priorContext, String planRationale, String workspacePath,
                                  String workspaceTree, String conversationId, String messageId,
                                  CancellationToken token, ChatService.Listener listener) {
        boolean hasWorkspace = workspacePath != null && !workspacePath.isBlank();

        List<String> systemLines = new ArrayList<>(List.of(
                persona.getSystemPrompt(), "",
                "You are contributing as a specialist inside a multi-agent workflow.",
                "Focus on your specialty. Do not pretend to be the final answer for the user.",
                "Be concrete and useful; the coordinator will synthesize your notes."));
        if (planRationale != null && !planRationale.isBlank()) {
            systemLines.add("Coordinator rationale for involving you: " + planRationale);
        }
        if (hasWorkspace && workspaceTree != null) {
            systemLines.addAll(List.of("",
                    "A workspace folder is bound to this conversation: " + workspacePath,
                    "This folder IS the project root - scaffold files directly into it (write Cargo.toml / "
                            + "package.json / src/... at the top level); do NOT create a subfolder named after "
                            + "the project or run `cargo new` / `npm init <name>` that would nest one.",
                    "You can inspect AND change files in it. Read tools: list_dir, read_file, search_file. "
                            + "Write tools: write_file, delete_file, rename_file"
                            + (isGitRepo(workspacePath) ? ", git_add, git_commit" : "") + ". "
                            + "run_command runs ONE build/test/lint command with the project's own toolchain "
                            + "(no shell/pipes; runs in the workspace root, pass cwd only for a monorepo "
                            + "subdir; non-zero exit comes back as text to read and fix).",
                    "Every write and every command is shown to the user for approval first - if a result says "
                            + "the user declined, stop that action and don't retry it.",
                    "Inspect real files with read_file/search_file before changing them - don't guess from the "
                            + "tree alone. The tree shows only the top levels; list_dir into any folder marked "
                            + "with a trailing \"…\". If the request asks you to create or edit files, DO IT with "
                            + "the tools now - don't just describe the change. When done, briefly summarize what "
                            + "you changed.",
                    "Workspace tree:", workspaceTree));
        }

        String userBlock = (priorContext != null && !priorContext.isBlank() ? "Conversation so far:\n" + priorContext + "\n" : "")
                + "User request:\n" + userContent;

        List<ChatRequestMessage> messages = new ArrayList<>(List.of(
                ChatRequestMessage.system(String.join("\n", systemLines)),
                ChatRequestMessage.user(userBlock)));

        client.ensureModelLoaded(specialistModel,
                status -> listener.onModelStatus(conversationId, status), token);

        if (!hasWorkspace) {
            ChatCompletionResult completion = client.completeChat(messages, specialistModel, List.of(), maxTokens, token);
            String result = completion.content() == null ? "" : completion.content().trim();
            return result.isEmpty() ? "(" + persona.getName() + " returned an empty response.)" : result;
        }

        // Full read+write tool loop, every mutating call gated by the ActionApprover and checkpointed.
        String result = toolLoop.run(client, specialistModel, null, maxTokens, messages, workspacePath, conversationId,
                token, (op, path, status, detail, checkpointId) ->
                        listener.onWorkspaceOp(conversationId, messageId, op, path, status, detail, checkpointId));
        return result == null || result.isBlank() ? "(" + persona.getName() + " finished.)" : result.trim();
    }

    /** Cheap ".git present?" check so a specialist's prompt only mentions git tools when the workspace is actually a repo. */
    private boolean isGitRepo(String workspacePath) {
        try {
            Path dotGit = Path.of(workspacePath).resolve(".git");
            return Files.isDirectory(dotGit) || Files.isRegularFile(dotGit);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void synthesize(LlmClient client, Persona orchestrator, String model, int maxTokens, String userContent,
                             String planRationale, List<SpecialistNote> notes, String workspacePath,
                             String workspaceTree, CancellationToken token, java.util.function.Consumer<String> onDelta) {
        List<String> systemLines = new ArrayList<>(List.of(
                orchestrator.getSystemPrompt(), "",
                "Synthesize a final answer for the user from the specialist notes.",
                "Lead with the answer, resolve disagreements, and keep it clear.",
                "Do not mention internal planning JSON or that you are an orchestrator unless useful.",
                "Plan rationale: " + planRationale));
        if (workspaceTree != null) {
            systemLines.addAll(List.of("",
                    "Workspace folder bound to this conversation: " + workspacePath,
                    "The specialists could read and write files here. Summarize what they actually did, "
                            + "including any files created or changed, and base every file claim on their notes - "
                            + "do NOT invent writes they didn't report.",
                    "Workspace tree (for reference):", workspaceTree));
        }

        String userBlock = "User request:\n" + userContent + "\n\nSpecialist notes:\n" + notesBlock(notes);
        List<ChatRequestMessage> messages = List.of(
                ChatRequestMessage.system(String.join("\n", systemLines)),
                ChatRequestMessage.user(userBlock));

        client.streamChat(messages, model, maxTokens, onDelta, token);
    }

    private static String notesBlock(List<SpecialistNote> notes) {
        return notes.isEmpty()
                ? "(No specialists were consulted.)"
                : notes.stream().map(note -> "### " + note.persona().getName() + "\n" + note.content())
                        .collect(Collectors.joining("\n\n"));
    }

    public void cancel(String conversationId) {
        CancellationToken token = activeTokens.get(conversationId);
        if (token != null) {
            token.cancel();
        }
    }

    public boolean isActive(String conversationId) {
        return activeTokens.containsKey(conversationId);
    }

    public void shutdown() {
        executor.shutdownNow();
    }
}
