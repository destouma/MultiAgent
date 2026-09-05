package com.multiagent.desktop.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiagent.desktop.llm.CancellationToken;
import com.multiagent.desktop.llm.ChatCompletionResult;
import com.multiagent.desktop.llm.ChatRequestMessage;
import com.multiagent.desktop.llm.LlmClient;
import com.multiagent.desktop.llm.ToolCall;
import com.multiagent.desktop.llm.ToolDefinition;
import com.multiagent.desktop.persistence.ConversationStore;
import com.multiagent.desktop.workspace.ActionTagParser;
import com.multiagent.desktop.workspace.WorkspaceService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The tool-calling agent loop for workspace-bound chats: calls completeChat with the
 * workspace tool definitions, executes any tool calls the model makes (native
 * function-calling first, falling back to XML action tags for models without it),
 * appends results, and repeats up to MAX_TOOL_ROUNDS. Mirrors
 * chatService.ts's runWorkspaceAgent()+runToolAndEmit(). Deliberately its own class
 * (rather than inlined in ChatService) since OrchestratorService (Phase 4) needs the same
 * loop for its specialists - the TS version duplicates this logic between the two
 * services, which this port avoids by sharing it from the start.
 */
public class ToolLoopRunner {
    private static final int MAX_TOOL_ROUNDS = 8;

    private final WorkspaceService workspace = new WorkspaceService();
    private final ConversationStore store;
    private final ObjectMapper mapper = new ObjectMapper();

    public ToolLoopRunner(ConversationStore store) {
        this.store = store;
    }

    public interface ToolOpListener {
        /** checkpointId is non-null only for a successful write_file/delete_file - lets the UI offer View diff/Revert for that op. */
        void onOp(String op, String path, String status, String detail, String checkpointId);
    }

    public String run(LlmClient client, String model, List<ChatRequestMessage> initialMessages,
                       String workspacePath, String conversationId, CancellationToken token,
                       ToolOpListener listener) {
        List<ChatRequestMessage> messages = new ArrayList<>(initialMessages);
        List<ToolDefinition> tools = WorkspaceService.workspaceTools();
        boolean toolsEnabled = true;
        String finalText = "";

        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            token.throwIfCancelled();

            ChatCompletionResult completion;
            try {
                completion = client.completeChat(messages, model, toolsEnabled ? tools : List.of(), token);
            } catch (RuntimeException e) {
                if (toolsEnabled) {
                    toolsEnabled = false;
                    completion = client.completeChat(messages, model, List.of(), token);
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
                    String result = runToolAndEmit(workspacePath, conversationId, call.name(), call.arguments(), listener);
                    messages.add(ChatRequestMessage.tool(call.id(), result));
                }
                continue;
            }

            List<ActionTagParser.ParsedAction> actions = ActionTagParser.parse(completion.content());
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
                    String result = runToolAndEmit(workspacePath, conversationId, action.name(), rawArgs, listener);
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

    private String runToolAndEmit(String workspacePath, String conversationId, String name, String rawArgsJson,
                                   ToolOpListener listener) {
        Map<String, Object> args;
        try {
            args = mapper.readValue(rawArgsJson == null || rawArgsJson.isBlank() ? "{}" : rawArgsJson,
                    new TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            args = Map.of();
        }

        String relPath = args.get("path") != null
                ? String.valueOf(args.get("path"))
                : ("generate_image".equals(name) ? "images/generated.png" : ".");

        if (listener != null) {
            listener.onOp(name, relPath, "running", null, null);
        }

        boolean capturesCheckpoint = "write_file".equals(name) || "delete_file".equals(name);
        String previousContent = capturesCheckpoint ? workspace.tryReadFile(workspacePath, relPath) : null;

        try {
            String result = workspace.executeTool(workspacePath, name, args);
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
}
