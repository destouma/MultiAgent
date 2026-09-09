package com.multiagent.desktop.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiagent.desktop.action.ActionApprover;
import com.multiagent.desktop.action.PendingAction;
import com.multiagent.desktop.llm.CancellationToken;
import com.multiagent.desktop.llm.ChatCompletionResult;
import com.multiagent.desktop.llm.ChatRequestMessage;
import com.multiagent.desktop.llm.LlmClient;
import com.multiagent.desktop.llm.ToolCall;
import com.multiagent.desktop.llm.ToolDefinition;
import com.multiagent.desktop.persistence.ConversationStore;
import com.multiagent.desktop.workspace.ActionTagParser;
import com.multiagent.desktop.workspace.GitService;
import com.multiagent.desktop.workspace.JsonToolCallParser;
import com.multiagent.desktop.workspace.RunCommandService;
import com.multiagent.desktop.workspace.WorkspaceService;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The tool-calling agent loop for workspace-bound chats: calls completeChat with the
 * workspace tool definitions, executes any tool calls the model makes (native
 * function-calling first, falling back to XML action tags for models without it),
 * appends results, and repeats up to MAX_TOOL_ROUNDS. Mirrors
 * chatService.ts's runWorkspaceAgent()+runToolAndEmit(). Deliberately its own class
 * (rather than inlined in ChatService) since OrchestratorService (Phase 4) needs the same
 * loop for its specialists - the TS version duplicates this logic between the two
 * services, which this port avoids by sharing it from the start.
 *
 * <p>Mutating tools (write_file/delete_file/rename_file and git_add/git_commit - not the
 * read-only ones) are gated behind an {@link ActionApprover} before they run, mirroring how
 * a coding agent asks before touching disk. A null approver auto-approves - that's a testing
 * convenience only (see ChatServiceWorkspaceSharingTest etc.), never the real app's behavior;
 * App.java always installs a real dialog-backed approver.
 */
public class ToolLoopRunner {
    private static final int MAX_TOOL_ROUNDS = 6;
    // File mutators, GitService.MUTATING_TOOLS, and run_command - kept explicit here so the gate is readable at a glance.
    private static final Set<String> MUTATING_TOOLS = Set.of(
            "write_file", "delete_file", "rename_file", "git_add", "git_commit", RunCommandService.TOOL_NAME);

    private final WorkspaceService workspace = new WorkspaceService();
    private final GitService git = new GitService();
    private final RunCommandService runCommand = new RunCommandService();
    private final ConversationStore store;
    private final ObjectMapper mapper = new ObjectMapper();
    private volatile ActionApprover approver;

    public ToolLoopRunner(ConversationStore store) {
        this.store = store;
    }

    public void setActionApprover(ActionApprover approver) {
        this.approver = approver;
    }

    public interface ToolOpListener {
        /** checkpointId is non-null only for a successful write_file/delete_file - lets the UI offer View diff/Revert for that op. */
        void onOp(String op, String path, String status, String detail, String checkpointId);
    }

    public String run(LlmClient client, String model, String visionModel, int maxTokens,
                       List<ChatRequestMessage> initialMessages,
                       String workspacePath, String conversationId, CancellationToken token,
                       ToolOpListener listener) {
        List<ChatRequestMessage> messages = new ArrayList<>(initialMessages);
        List<ToolDefinition> tools = new ArrayList<>(WorkspaceService.workspaceTools());
        tools.addAll(GitService.gitTools());
        tools.addAll(RunCommandService.commandTools());
        boolean visionEnabled = visionModel != null && !visionModel.isBlank();
        if (visionEnabled) {
            tools.add(WorkspaceService.visionTool());
        }
        boolean toolsEnabled = true;
        String finalText = "";

        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            token.throwIfCancelled();
            ContextTrimmer.elideOldToolOutput(messages);

            ChatCompletionResult completion;
            try {
                completion = client.completeChat(messages, model, toolsEnabled ? tools : List.of(), maxTokens, token);
            } catch (RuntimeException e) {
                if (toolsEnabled) {
                    toolsEnabled = false;
                    completion = client.completeChat(messages, model, List.of(), maxTokens, token);
                } else {
                    throw e;
                }
            }

            List<ToolCall> nativeCalls = completion.toolCalls();
            if (nativeCalls != null && !nativeCalls.isEmpty()) {
                String assistantContent = completion.content() != null && !completion.content().isEmpty()
                        ? completion.content() : null;
                messages.add(ChatRequestMessage.assistantWithToolCalls(assistantContent, nativeCalls));

                for (ToolCall call : nativeCalls) {
                    String result = runToolAndEmit(client, visionModel, workspacePath, conversationId,
                            call.name(), call.arguments(), token, listener);
                    messages.add(ChatRequestMessage.tool(call.id(), result));
                }
                continue;
            }

            List<ActionTagParser.ParsedAction> actions = new ArrayList<>(ActionTagParser.parse(completion.content()));
            if (actions.isEmpty()) {
                // Second fallback: some tool-tuned models (Qwen2.5-Coder is the common case
                // with Lemonade/llama.cpp servers) ignore this app's own XML tags and print
                // the {"name": ..., "arguments": {...}} JSON shape they were fine-tuned to
                // produce instead - see JsonToolCallParser's javadoc.
                actions.addAll(JsonToolCallParser.parse(completion.content()));
            }
            if (!actions.isEmpty()) {
                messages.add(ChatRequestMessage.assistant(completion.content() == null ? "" : completion.content()));

                List<String> results = new ArrayList<>();
                for (ActionTagParser.ParsedAction action : actions) {
                    String rawArgs;
                    try {
                        rawArgs = mapper.writeValueAsString(action.args());
                    } catch (Exception e) {
                        rawArgs = "{}";
                    }
                    String result = runToolAndEmit(client, visionModel, workspacePath, conversationId,
                            action.name(), rawArgs, token, listener);
                    results.add("[" + action.name() + "] " + result);
                }

                messages.add(ChatRequestMessage.user(
                        "Tool results:\n" + String.join("\n\n", results)
                                + "\n\nContinue. If more file work is needed, emit more actions. "
                                + "Otherwise summarize for the user."));
                continue;
            }

            finalText = completion.content() == null ? "" : completion.content().trim();
            break;
        }

        return finalText.isEmpty() ? "Finished workspace operations." : finalText;
    }

    private String runToolAndEmit(LlmClient client, String visionModel, String workspacePath,
                                   String conversationId, String name, String rawArgsJson,
                                   CancellationToken token, ToolOpListener listener) {
        Map<String, Object> args;
        try {
            args = mapper.readValue(rawArgsJson == null || rawArgsJson.isBlank() ? "{}" : rawArgsJson,
                    new TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            args = Map.of();
        }

        String relPath;
        if (RunCommandService.TOOL_NAME.equals(name)) {
            relPath = RunCommandService.commandLine(args); // the op line / prompt shows the command, not a path
        } else if (args.get("path") != null) {
            relPath = String.valueOf(args.get("path"));
        } else if ("generate_image".equals(name)) {
            relPath = "images/generated.png";
        } else if (name.startsWith("git_")) {
            relPath = ""; // git tools act on the repo, not a single path
        } else {
            relPath = ".";
        }

        if (listener != null) {
            listener.onOp(name, relPath, "running", null, null);
        }

        if ("describe_image".equals(name)) {
            try {
                String result = describeImage(client, visionModel, workspacePath, relPath,
                        String.valueOf(args.getOrDefault("question", "Describe this image.")), token);
                if (listener != null) {
                    listener.onOp(name, relPath, "ok",
                            result.length() > 240 ? result.substring(0, 240) : result, null);
                }
                return result;
            } catch (RuntimeException e) {
                String message = e.getMessage() != null ? e.getMessage() : e.toString();
                if (listener != null) {
                    listener.onOp(name, relPath, "error", message, null);
                }
                return "Error: " + message;
            }
        }

        if (MUTATING_TOOLS.contains(name) && approver != null) {
            PendingAction action = describeAction(workspacePath, name, relPath, args);
            if (!approver.approve(action)) {
                String message = "User declined this action.";
                if (listener != null) {
                    listener.onOp(name, relPath, "error", message, null);
                }
                return "Error: " + message;
            }
        }

        boolean capturesCheckpoint = "write_file".equals(name) || "delete_file".equals(name);
        String previousContent = capturesCheckpoint ? workspace.tryReadFile(workspacePath, relPath) : null;

        try {
            String result;
            if (RunCommandService.TOOL_NAME.equals(name)) {
                result = runCommand.executeTool(workspacePath, args, token);
            } else if (name.startsWith("git_")) {
                result = git.executeTool(workspacePath, name, args);
            } else {
                result = workspace.executeTool(workspacePath, name, args);
            }
            String checkpointId = capturesCheckpoint
                    ? store.addCheckpoint(conversationId, relPath, previousContent, previousContent != null).id()
                    : null;
            if (listener != null) {
                listener.onOp(name, relPath, "ok", result.length() > 240 ? result.substring(0, 240) : result,
                        checkpointId);
            }
            return result;
        } catch (RuntimeException e) {
            String message = e.getMessage() != null ? e.getMessage() : e.toString();
            if (listener != null) {
                listener.onOp(name, relPath, "error", message, null);
            }
            return "Error: " + message;
        }
    }

    /**
     * describe_image: read the workspace image (sandboxed + capped), make sure the vision
     * model is loaded, then one-shot ask it. Read-only - no approver, no checkpoint.
     */
    private String describeImage(LlmClient client, String visionModel, String workspacePath,
                                  String relPath, String question, CancellationToken token) {
        if (visionModel == null || visionModel.isBlank()) {
            return "No vision model is configured for this server (Settings -> Edit server -> Vision model).";
        }
        byte[] bytes = workspace.readImageBytes(workspacePath, relPath);
        String mime = WorkspaceService.guessImageMime(relPath);
        client.ensureModelLoaded(visionModel, status -> { }, token);
        String answer = client.describeImage(visionModel, question, bytes, mime, token);
        if (VisionResponses.looksLikeRefusal(answer)) {
            return "Error: the vision model could not read \"" + relPath
                    + "\" (it returned a generic \"I can't see images\" reply - the image may be too small "
                    + "or an unsupported form, or the server dropped it).";
        }
        return answer.strip();
    }

    /** Human-readable preview of a mutating tool call, shown to the user before it runs. */
    private PendingAction describeAction(String workspacePath, String name, String relPath, Map<String, Object> args) {
        return switch (name) {
            case "write_file" -> {
                String content = String.valueOf(args.getOrDefault("content", ""));
                int bytes = content.getBytes(StandardCharsets.UTF_8).length;
                String preview = content.length() > 2000 ? content.substring(0, 2000) + "\n... (truncated)" : content;
                yield new PendingAction("file", "Write " + relPath + " (" + bytes + " bytes)", preview);
            }
            case "delete_file" -> {
                String existing = workspace.tryReadFile(workspacePath, relPath);
                String preview = existing != null
                        ? (existing.length() > 2000 ? existing.substring(0, 2000) + "\n... (truncated)" : existing)
                        : null;
                yield new PendingAction("file", "Delete " + relPath, preview);
            }
            case "rename_file" -> {
                String newPath = String.valueOf(args.getOrDefault("newPath", ""));
                yield new PendingAction("file", "Rename " + relPath + " -> " + newPath);
            }
            case "run_command" -> {
                String cmd = RunCommandService.commandLine(args);
                Object cwd = args.get("cwd");
                String dir = cwd != null && !String.valueOf(cwd).isBlank() && !String.valueOf(cwd).trim().equals(".")
                        ? workspacePath + java.io.File.separator + String.valueOf(cwd).trim()
                        : workspacePath;
                yield new PendingAction("command", "Run: " + cmd,
                        "Working directory: " + dir + "\n\n$ " + cmd);
            }
            case "git_add" -> new PendingAction("git", "git add " + (relPath.isBlank() ? "." : relPath));
            case "git_commit" -> {
                String message = String.valueOf(args.getOrDefault("message", ""));
                boolean all = Boolean.parseBoolean(String.valueOf(args.getOrDefault("all", "false")));
                String firstLine = message.isBlank() ? "(no message)" : message.lines().findFirst().orElse(message);
                yield new PendingAction("git", "git commit" + (all ? " -a" : "") + ": " + firstLine,
                        message.isBlank() ? null : message);
            }
            default -> new PendingAction("file", name + " " + relPath);
        };
    }
}
