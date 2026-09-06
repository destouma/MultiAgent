package com.multiagent.desktop.model;

/** Mirrors shared/types.ts SearchResult. */
public record SearchResult(String conversationId, String title, ConversationKind kind, String workspacePath,
                            boolean matchedInTitle, String snippet) {
}
