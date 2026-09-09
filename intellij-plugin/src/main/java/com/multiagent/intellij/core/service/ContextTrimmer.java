package com.multiagent.intellij.core.service;

import com.multiagent.intellij.core.llm.ChatRequestMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps a tool-calling loop's message list from overflowing a small local context window.
 * Tool output (file reads, diffs, git logs) is what piles up round after round - the model
 * rarely needs the full text of a read it already acted on several rounds ago. This keeps
 * the most recent {@link #KEEP_RECENT} tool results verbatim and replaces the body of older,
 * large ones with a one-line stub that still says what was there.
 *
 * <p>Used by {@link ToolLoopRunner} and by {@link OrchestratorService}'s specialist loop.
 * Java-only; the TS app has no equivalent.
 */
final class ContextTrimmer {
    /** Recent tool results left untouched. */
    static final int KEEP_RECENT = 2;
    /** Older tool results are only elided when longer than this (chars). */
    static final int ELIDE_OVER = 600;
    /** How much of the head of an elided result to keep, as a breadcrumb. */
    static final int KEEP_HEAD = 160;

    private ContextTrimmer() {
    }

    /**
     * Elides old, large tool outputs in place. Safe to call every round: it's a no-op until
     * there are more than {@link #KEEP_RECENT} tool results, and never touches system, user,
     * or assistant messages - only {@code tool}-role results and the fallback loop's
     * {@code "Tool results:"} user message. Pairing between an assistant tool-call and its
     * {@code tool} reply is preserved (only the body shrinks, ids stay).
     */
    static void elideOldToolOutput(List<ChatRequestMessage> messages) {
        List<Integer> toolOutputs = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            if (isToolOutput(messages.get(i))) {
                toolOutputs.add(i);
            }
        }
        int elideUpTo = toolOutputs.size() - KEEP_RECENT;
        for (int n = 0; n < elideUpTo; n++) {
            int i = toolOutputs.get(n);
            ChatRequestMessage message = messages.get(i);
            String body = message.content();
            if (body == null || body.length() <= ELIDE_OVER) {
                continue;
            }
            String head = body.substring(0, Math.min(KEEP_HEAD, body.length())).stripTrailing();
            String stub = head + "\n… [" + body.length()
                    + " chars of earlier tool output elided to save context]";
            messages.set(i, new ChatRequestMessage(
                    message.role(), stub, message.toolCalls(), message.toolCallId()));
        }
    }

    private static boolean isToolOutput(ChatRequestMessage message) {
        if ("tool".equals(message.role())) {
            return true;
        }
        return "user".equals(message.role())
                && message.content() != null
                && message.content().startsWith("Tool results:");
    }
}
