package com.multiagent.desktop.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiagent.desktop.action.ActionApprover;
import com.multiagent.desktop.llm.CancellationToken;
import com.multiagent.desktop.llm.ChatCompletionResult;
import com.multiagent.desktop.llm.ChatRequestMessage;
import com.multiagent.desktop.llm.ErrorCode;
import com.multiagent.desktop.llm.LlmClient;
import com.multiagent.desktop.llm.ProviderException;
import com.multiagent.desktop.llm.ToolCall;
import com.multiagent.desktop.llm.ToolDefinition;
import com.multiagent.desktop.model.ChatMessage;
import com.multiagent.desktop.model.Conversation;
import com.multiagent.desktop.model.MessageRole;
import com.multiagent.desktop.model.Persona;
import com.multiagent.desktop.persistence.ConversationStore;
import com.multiagent.desktop.workspace.GitService;
import com.multiagent.desktop.workspace.WorkspaceService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Plan -> specialists -> synthesize workflow for kind == orchestrator conversations,
 * mirroring desktop/electron/services/orchestratorService.ts. Each user message: (1) asks
 * the orchestrator persona which specialists (researcher/coder/critic) should help, (2)
 * runs each chosen specialist in turn - read-only workspace tools plus the read-only git
 * tools, via MAX_SPECIALIST_TOOL_ROUNDS - persisting each note as its own message as it completes,
 * then (3) streams a final synthesized answer from the notes. Intentionally its own
 * service (like ChatService) rather than sharing ToolLoopRunner directly, since
 * specialists need a read-only-filtered tool set and per-specialist note bookkeeping that
 * doesn't fit ToolLoopRunner's single-final-answer shape - though both build on the same
 * WorkspaceService/ChatRequestMessage primitives, avoiding the ~150-line duplication the
 * TS version has between chatService.ts and orchestratorService.ts at the tool-execution
 * layer (executeSpecialistTool here is a deliberately small, read-only sibling of
 * ToolLoopRunner's runToolAndEmit, not a copy of its write/delete or git_add/git_commit handling).
 */
public class OrchestratorService {
    private static final int MAX_SPECIALIST_TOOL_ROUNDS = 4;
    // list_dir/read_file/search_file plus GitService's read-only git_* tools - never write_file/delete_file/rename_file or git_add/git_commit.
    private static final Set<String> READ_ONLY_TOOL_NAMES = Stream.concat(
            Stream.of("list_dir", "read_file", "search_file"), GitService.READ_ONLY_TOOLS.stream())
            .collect(Collectors.toUnmodifiableSet());

    private static final List<ToolDefinition> READ_ONLY_TOOLS = Stream.concat(
            WorkspaceService.workspaceTools().stream(), GitService.gitTools().stream())
            .filter(tool -> READ_ONLY_TOOL_NAMES.contains(tool.name()))
            .toList();

    private record SpecialistNote(Persona persona, String content) {
    }

    private final ConversationStore store;
    private final PersonaRegistry personas;
    private final WorkspaceService workspace = new WorkspaceService();
    private final GitService git = new GitService();
    private final ObjectMapper mapper = new ObjectMapper();
    // Only used by the opt-in executor phase - the full write/git tool loop, behind the same
    // ActionApprover gate as ChatService's.
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

    /** Installs the write/delete/rename/git confirmation gate for the executor phase (App wires the real one). */
    public void setActionApprover(ActionApprover approver) {
        toolLoop.setActionApprover(approver);
    }

    public void send(LlmClient client, Conversation conversation, String content, String fallbackModel,
                      int maxHistory, Map<String, String> specialistModels, int contextTokens, boolean apply,
                      ChatService.Listener listener) {
        store.addMessage(conversation.getId(), MessageRole.USER, content, null);

        Map<String, String> overrides = specialistModels == null ? Map.of() : specialistModels;
        // The "Persona" topbar box picks the coordinator for an orchestrator chat (it runs
        // the plan + synthesis); fall back to the dedicated orchestrator persona, then general.
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

        executor.submit(() -> {
            try {
                client.ensureModelLoaded(model,
                        status -> listener.onModelStatus(conversation.getId(), status), token);

                String workspacePath = conversation.getWorkspacePath();
                String workspaceTree = null;
                if (workspacePath != null && !workspacePath.isBlank()) {
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
                            && TokenEstimate.estimateTokens(entries) + TokenEstimate.estimateTokens(List.of(content)) > budget) {
                        entries.remove(0);
                    }
                }
                String priorContext = String.join("\n\n", entries);

                PlanParser.PlanResult plan = planSpecialists(client, orchestrator, model, maxTokens, content,
                        priorContext, workspacePath, workspaceTree, token);

                List<SpecialistNote> notes = new ArrayList<>();
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

                    String specialistContent = runSpecialist(client, persona, specialistModel, maxTokens, content,
                            priorContext, plan.rationale(), workspacePath, workspaceTree, conversation.getId(),
                            assistantMessageId, token, listener);

                    store.addMessage(conversation.getId(), MessageRole.ASSISTANT, specialistContent, persona.getId());
                    listener.onMessagesUpdated(conversation.getId());
                    notes.add(new SpecialistNote(persona, specialistContent));
                }

                listener.onStep(conversation.getId(), "synthesizing", orchestrator.getId(),
                        "Synthesizing final answer...");

                boolean willApply = apply && workspacePath != null && !workspacePath.isBlank();

                StringBuilder full = new StringBuilder();
                synthesize(client, orchestrator, model, maxTokens, content, plan.rationale(), notes, workspacePath,
                        workspaceTree, willApply, token, delta -> {
                            full.append(delta);
                            listener.onToken(conversation.getId(), assistantMessageId, delta);
                        });
                String finalText = full.toString().trim();
                if (finalText.isEmpty()) {
                    finalText = "I could not produce a final answer from the specialist notes.";
                }

                if (willApply) {
                    listener.onStep(conversation.getId(), "executing", orchestrator.getId(),
                            "Applying changes...");
                    String applied = runExecutor(client, orchestrator, model, maxTokens, content, notes, finalText,
                            workspacePath, workspaceTree, conversation.getId(), assistantMessageId, token, listener);
                    finalText = finalText + "\n\n---\n\n**Applied changes**\n\n" + applied.trim();
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
                    "A read-only workspace folder is bound to this conversation: " + workspacePath,
                    "Specialists you pick will be able to list and read files in it.",
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
        List<String> systemLines = new ArrayList<>(List.of(
                persona.getSystemPrompt(), "",
                "You are contributing as a specialist inside a multi-agent workflow.",
                "Focus on your specialty. Do not pretend to be the final answer for the user.",
                "Be concrete and useful; the orchestrator will synthesize your notes.",
                "Orchestrator rationale for involving you: " + planRationale));
        if (workspacePath != null && workspaceTree != null) {
            systemLines.addAll(List.of("",
                    "You have read-only access to a workspace folder bound to this conversation: " + workspacePath,
                    "Use the list_dir, read_file and search_file tools to inspect actual files before answering - "
                            + "do not guess at contents from the tree alone. The tree shows only the top "
                            + "levels; list_dir into any folder marked with a trailing \"…\". For a large file, "
                            + "search_file to find the relevant lines then read_file with offset/limit around them."));
            if (isGitRepo(workspacePath)) {
                systemLines.add("This workspace is a git repository: git_status, git_diff, git_log, git_show and "
                        + "git_branch are available for inspecting history and pending changes (read-only - you "
                        + "cannot stage or commit).");
            }
            systemLines.addAll(List.of("Workspace tree:", workspaceTree));
        }

        String userBlock = (priorContext != null && !priorContext.isBlank() ? "Conversation so far:\n" + priorContext + "\n" : "")
                + "User request:\n" + userContent;

        List<ChatRequestMessage> messages = new ArrayList<>(List.of(
                ChatRequestMessage.system(String.join("\n", systemLines)),
                ChatRequestMessage.user(userBlock)));

        client.ensureModelLoaded(specialistModel,
                status -> listener.onModelStatus(conversationId, status), token);

        if (workspacePath == null || workspacePath.isBlank()) {
            ChatCompletionResult completion = client.completeChat(messages, specialistModel, List.of(), maxTokens, token);
            String result = completion.content() == null ? "" : completion.content().trim();
            return result.isEmpty() ? "(" + persona.getName() + " returned an empty response.)" : result;
        }

        return runSpecialistWithTools(client, messages, specialistModel, maxTokens, workspacePath, persona,
                conversationId, messageId, token, listener);
    }

    private String runSpecialistWithTools(LlmClient client, List<ChatRequestMessage> initialMessages, String model,
                                           int maxTokens, String workspacePath, Persona persona, String conversationId,
                                           String messageId, CancellationToken token, ChatService.Listener listener) {
        List<ChatRequestMessage> messages = new ArrayList<>(initialMessages);
        boolean toolsEnabled = true;

        for (int round = 0; round < MAX_SPECIALIST_TOOL_ROUNDS; round++) {
            token.throwIfCancelled();
            ContextTrimmer.elideOldToolOutput(messages);

            ChatCompletionResult completion;
            try {
                completion = client.completeChat(messages, model, toolsEnabled ? READ_ONLY_TOOLS : List.of(), maxTokens, token);
            } catch (RuntimeException e) {
                if (toolsEnabled) {
                    toolsEnabled = false;
                    completion = client.completeChat(messages, model, List.of(), maxTokens, token);
                } else {
                    throw e;
                }
            }

            List<ToolCall> calls = completion.toolCalls();
            if (calls == null || calls.isEmpty()) {
                String result = completion.content() == null ? "" : completion.content().trim();
                return result.isEmpty() ? "(" + persona.getName() + " returned an empty response.)" : result;
            }

            String assistantContent = completion.content() != null && !completion.content().isEmpty()
                    ? completion.content() : null;
            messages.add(ChatRequestMessage.assistantWithToolCalls(assistantContent, calls));

            for (ToolCall call : calls) {
                String result = executeSpecialistTool(workspacePath, conversationId, messageId, call.name(),
                        call.arguments(), listener);
                messages.add(ChatRequestMessage.tool(call.id(), result));
            }
        }

        return "(" + persona.getName() + " stopped after exploring the workspace - try a more specific question.)";
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

    private String executeSpecialistTool(String workspacePath, String conversationId, String messageId, String name,
                                          String rawArgsJson, ChatService.Listener listener) {
        Map<String, Object> args;
        try {
            args = mapper.readValue(rawArgsJson == null || rawArgsJson.isBlank() ? "{}" : rawArgsJson,
                    new TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            args = Map.of();
        }

        String relPath = args.get("path") != null
                ? String.valueOf(args.get("path"))
                : (name.startsWith("git_") ? "" : ".");
        if (listener != null) {
            listener.onWorkspaceOp(conversationId, messageId, name, relPath, "running", null, null);
        }

        if (!READ_ONLY_TOOL_NAMES.contains(name)) {
            String message = "Tool \"" + name + "\" is not available to specialists (read-only access).";
            if (listener != null) {
                listener.onWorkspaceOp(conversationId, messageId, name, relPath, "error", message, null);
            }
            return "Error: " + message;
        }

        try {
            // Specialists only ever get read-only tools (list_dir/read_file + read-only git_*),
            // never write_file/delete_file or git_add/git_commit, so there's no checkpoint to
            // capture here - unlike ToolLoopRunner's runToolAndEmit.
            String result = name.startsWith("git_")
                    ? git.executeTool(workspacePath, name, args)
                    : workspace.executeTool(workspacePath, name, args);
            if (listener != null) {
                listener.onWorkspaceOp(conversationId, messageId, name, relPath, "ok",
                        result.length() > 240 ? result.substring(0, 240) : result, null);
            }
            return result;
        } catch (RuntimeException e) {
            String message = e.getMessage() != null ? e.getMessage() : e.toString();
            if (listener != null) {
                listener.onWorkspaceOp(conversationId, messageId, name, relPath, "error", message, null);
            }
            return "Error: " + message;
        }
    }

    private void synthesize(LlmClient client, Persona orchestrator, String model, int maxTokens, String userContent,
                             String planRationale, List<SpecialistNote> notes, String workspacePath,
                             String workspaceTree, boolean willApply,
                             CancellationToken token, java.util.function.Consumer<String> onDelta) {
        List<String> systemLines = new ArrayList<>(List.of(
                orchestrator.getSystemPrompt(), "",
                "Synthesize a final answer for the user from the specialist notes.",
                "Lead with the answer, resolve disagreements, and keep it clear.",
                "Do not mention internal planning JSON or that you are an orchestrator unless useful.",
                "Plan rationale: " + planRationale));
        if (workspaceTree != null) {
            systemLines.addAll(List.of("",
                    "Workspace folder bound to this conversation: " + workspacePath,
                    "Workspace tree (for reference; specialist notes already reflect its actual contents):",
                    workspaceTree));
        }
        // Neither the specialists nor this synthesis step can write to disk - only the opt-in
        // executor phase can, and it runs *after* this. So the answer must not claim files were
        // created/written unless that phase is actually going to run.
        if (willApply) {
            systemLines.add("");
            systemLines.add("After your answer, an executor step will apply file changes to the workspace "
                    + "using your plan (each write is approved by the user). You may describe the changes as "
                    + "the plan to be applied.");
        } else if (workspaceTree != null) {
            systemLines.add("");
            systemLines.add("IMPORTANT: no files can be created or modified in this turn - there is no write "
                    + "step, and the user has not enabled \"apply changes\". Present any code and file layout as "
                    + "a proposal. Do NOT say that files were created, written, generated, or placed in folders. "
                    + "If the user asked you to write files, tell them to enable \"Let the coordinator apply "
                    + "changes to the workspace\" in the Specialists… dialog (or use a normal workspace chat).");
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

    /**
     * Runs the write-capable executor: the specialist notes + synthesis are the brief; every
     * write/delete/rename still goes through ToolLoopRunner's ActionApprover gate and captures
     * a checkpoint. Returns the executor's own summary of what it did.
     */
    private String runExecutor(LlmClient client, Persona orchestrator, String model, int maxTokens,
                                String userContent, List<SpecialistNote> notes, String plan, String workspacePath,
                                String workspaceTree, String conversationId, String messageId,
                                CancellationToken token, ChatService.Listener listener) {
        List<String> systemLines = new ArrayList<>(List.of(
                orchestrator.getSystemPrompt(), "",
                "You are now APPLYING the agreed changes to the bound workspace folder: " + workspacePath,
                "Tools: list_dir, read_file, search_file (read); write_file, delete_file, rename_file"
                        + (isGitRepo(workspacePath) ? ", and git_add / git_commit" : "") + " (write).",
                "Every write is shown to the user for approval first - if a result says the user declined, "
                        + "stop that edit and don't retry it.",
                "Make only the changes the plan calls for; keep them minimal and on-topic. "
                        + "If nothing actually needs changing, say so and make no edits.",
                "When finished, give a short bullet summary of what you changed."));
        if (workspaceTree != null) {
            systemLines.addAll(List.of("Workspace tree:", workspaceTree));
        }
        List<ChatRequestMessage> messages = new ArrayList<>(List.of(
                ChatRequestMessage.system(String.join("\n", systemLines)),
                ChatRequestMessage.user("Original request:\n" + userContent
                        + "\n\nSpecialist notes:\n" + notesBlock(notes)
                        + "\n\nAgreed plan / synthesis:\n" + plan)));

        return toolLoop.run(client, model, null, maxTokens, messages, workspacePath, conversationId, token,
                (op, path, status, detail, checkpointId) ->
                        listener.onWorkspaceOp(conversationId, messageId, op, path, status, detail, checkpointId));
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
