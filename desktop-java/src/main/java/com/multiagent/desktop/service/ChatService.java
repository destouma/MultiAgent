package com.multiagent.desktop.service;

import com.multiagent.desktop.llm.CancellationToken;
import com.multiagent.desktop.llm.ChatRequestMessage;
import com.multiagent.desktop.llm.ErrorCode;
import com.multiagent.desktop.llm.LlmClient;
import com.multiagent.desktop.llm.ProviderException;
import com.multiagent.desktop.model.ChatMessage;
import com.multiagent.desktop.model.Conversation;
import com.multiagent.desktop.model.MessageRole;
import com.multiagent.desktop.model.Persona;
import com.multiagent.desktop.persistence.ConversationStore;
import com.multiagent.desktop.workspace.WorkspaceService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Chat turns for both plain conversations and workspace-bound ones, mirroring
 * desktop/electron/services/chatService.ts's send(). Plain chats stream tokens directly;
 * workspace-bound ones (Conversation.workspacePath set) delegate to ToolLoopRunner, which
 * isn't token-streamed - like the TS version, its whole answer arrives as one onToken call
 * once the tool-calling loop finishes. Per-conversation CancellationToken map means two
 * conversations can generate concurrently without one cancelling the other.
 */
public class ChatService {
    private final ConversationStore store;
    private final WorkspaceService workspace = new WorkspaceService();
    private final ToolLoopRunner toolLoopRunner;
    private final Map<String, CancellationToken> activeTokens = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "chat-service-worker");
        thread.setDaemon(true);
        return thread;
    });

    public ChatService(ConversationStore store) {
        this.store = store;
        this.toolLoopRunner = new ToolLoopRunner(store);
    }

    public interface Listener {
        void onToken(String conversationId, String messageId, String delta);

        void onDone(String conversationId, ChatMessage message);

        void onError(String conversationId, String messageId, ErrorCode code, String message);

        /** status: "running" | "ok" | "error". checkpointId is non-null only for a successful write_file/delete_file. No-op default for callers that don't care (e.g. tests). */
        default void onWorkspaceOp(String conversationId, String messageId, String op, String path,
                                    String status, String detail, String checkpointId) {
        }

        /** Orchestrator-only: phase is "planning" | "specialist" | "synthesizing" | "done". */
        default void onStep(String conversationId, String phase, String personaId, String label) {
        }

        /** Orchestrator-only: fired after each specialist note is persisted, so the UI can show it before the final synthesis arrives. */
        default void onMessagesUpdated(String conversationId) {
        }
    }

    /** Persists the user message, then generates+persists the assistant reply on a background thread. */
    public void send(LlmClient client, Conversation conversation, String content, Persona persona,
                      String model, int maxHistory, Listener listener) {
        store.addMessage(conversation.getId(), MessageRole.USER, content, persona.getId());

        List<ChatMessage> history = store.getMessages(conversation.getId());
        List<ChatRequestMessage> messages = new ArrayList<>();
        messages.add(ChatRequestMessage.system(buildSystemPrompt(persona, conversation)));
        int from = Math.max(0, history.size() - maxHistory);
        for (ChatMessage message : history.subList(from, history.size())) {
            if (message.getRole() == MessageRole.USER || message.getRole() == MessageRole.ASSISTANT) {
                messages.add(ChatRequestMessage.of(message.getRole(), message.getContent()));
            }
        }

        CancellationToken token = new CancellationToken();
        activeTokens.put(conversation.getId(), token);
        String assistantMessageId = UUID.randomUUID().toString();
        StringBuilder buffer = new StringBuilder();
        String workspacePath = conversation.getWorkspacePath();

        executor.submit(() -> {
            try {
                client.ensureModelLoaded(model, status -> { }, token);

                if (workspacePath != null && !workspacePath.isBlank()) {
                    String finalText = toolLoopRunner.run(client, model, messages, workspacePath,
                            conversation.getId(), token,
                            (op, path, status, detail, checkpointId) -> listener.onWorkspaceOp(
                                    conversation.getId(), assistantMessageId, op, path, status, detail, checkpointId));
                    buffer.append(finalText);
                    listener.onToken(conversation.getId(), assistantMessageId, finalText);
                } else {
                    client.streamChat(messages, model, delta -> {
                        buffer.append(delta);
                        listener.onToken(conversation.getId(), assistantMessageId, delta);
                    }, token);
                }

                ChatMessage saved = store.addMessage(conversation.getId(), MessageRole.ASSISTANT,
                        buffer.toString(), persona.getId());
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

    /** Persona prompt, plus (when workspace-bound) the directory tree and tool-usage instructions. */
    private String buildSystemPrompt(Persona persona, Conversation conversation) {
        List<String> parts = new ArrayList<>();
        parts.add(persona.getSystemPrompt());

        String workspacePath = conversation.getWorkspacePath();
        if (workspacePath != null && !workspacePath.isBlank()) {
            String tree;
            try {
                tree = workspace.buildTree(workspacePath);
            } catch (RuntimeException e) {
                tree = e.getMessage();
            }
            parts.add(String.join("\n",
                    "You have a writable workspace folder bound to this chat: " + workspacePath,
                    "You may inspect and modify files inside this folder only.",
                    "Prefer tools when available. If tools are unavailable, emit exact XML actions:",
                    "<list_dir path=\".\" />",
                    "<read_file path=\"relative/path.ext\" />",
                    "<write_file path=\"relative/path.ext\">FULL FILE CONTENT</write_file>",
                    "<delete_file path=\"relative/path.ext\" />",
                    "<generate_image path=\"images/out.png\" prompt=\"a red circle\" size=\"512x512\" />",
                    "After file work, give a short summary of what changed.",
                    "Current workspace tree:",
                    tree));
        }
        return String.join("\n\n", parts);
    }

    public void cancel(String conversationId) {
        CancellationToken token = activeTokens.get(conversationId);
        if (token != null) {
            token.cancel();
        }
    }

    /** Whether this specific conversation has a generation in flight right now - lets the UI show Stop/Send per chat rather than globally. */
    public boolean isActive(String conversationId) {
        return activeTokens.containsKey(conversationId);
    }

    public void shutdown() {
        executor.shutdownNow();
    }
}
