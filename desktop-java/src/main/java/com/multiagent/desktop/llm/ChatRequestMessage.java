package com.multiagent.desktop.llm;

import com.multiagent.desktop.model.MessageRole;

import java.util.List;

/**
 * One message in an outgoing chat request. Wire roles are "system"/"user"/"assistant"/
 * "tool" - a superset of MessageRole (which only covers persisted roles) since tool-loop
 * messages (Phase 2) are never persisted to ConversationStore, only sent to the provider.
 */
public record ChatRequestMessage(String role, String content, List<ToolCall> toolCalls, String toolCallId) {

    public static ChatRequestMessage of(MessageRole role, String content) {
        return new ChatRequestMessage(role.wire(), content, null, null);
    }

    public static ChatRequestMessage system(String content) {
        return new ChatRequestMessage("system", content, null, null);
    }

    public static ChatRequestMessage user(String content) {
        return new ChatRequestMessage("user", content, null, null);
    }

    public static ChatRequestMessage assistant(String content) {
        return new ChatRequestMessage("assistant", content, null, null);
    }

    /** An assistant turn that issued native tool calls; content may be null/empty. */
    public static ChatRequestMessage assistantWithToolCalls(String content, List<ToolCall> toolCalls) {
        return new ChatRequestMessage("assistant", content, toolCalls, null);
    }

    /** The result of executing one tool call, addressed back to it by id. */
    public static ChatRequestMessage tool(String toolCallId, String content) {
        return new ChatRequestMessage("tool", content, null, toolCallId);
    }
}
