package com.multiagent.desktop.service;

import com.multiagent.desktop.llm.ChatRequestMessage;

import java.util.List;

/**
 * Rough token estimate (~4 chars/token, a common approximation for English text), used only
 * to warn before a context_exceeded error, not to bill or enforce anything - there's no
 * bundled tokenizer, and it would need to be model-specific anyway across the range of
 * local models this app supports. Mirrors shared/tokenEstimate.ts.
 */
public final class TokenEstimate {
    private static final int WARN_THRESHOLD = 4000;
    private static final int DANGER_THRESHOLD = 8000;

    public enum Level {
        OK, WARN, DANGER
    }

    private TokenEstimate() {
    }

    /** Per-message wire overhead (role, delimiters) - a few tokens each, following OpenAI's own rule of thumb. */
    private static final int MESSAGE_OVERHEAD = 4;
    /** Flat cost of an inline image; the chars/4 rule can't see the base64 bytes. A screenshot-sized VLM input is roughly this. */
    static final int IMAGE_TOKENS = 1200;

    public static int estimateTokens(List<String> texts) {
        long chars = 0;
        for (String text : texts) {
            chars += text == null ? 0 : text.length();
        }
        return (int) Math.ceil(chars / 4.0);
    }

    /** Same chars/4 estimate over a request message list, plus per-message overhead and a flat cost for any inline image. */
    public static int estimateMessages(List<ChatRequestMessage> messages) {
        long chars = 0;
        int extra = 0;
        for (ChatRequestMessage message : messages) {
            chars += message.content() == null ? 0 : message.content().length();
            extra += MESSAGE_OVERHEAD;
            if (message.image() != null) {
                extra += IMAGE_TOKENS;
            }
        }
        return (int) Math.ceil(chars / 4.0) + extra;
    }

    public static Level levelFor(int estimatedTokens) {
        if (estimatedTokens >= DANGER_THRESHOLD) {
            return Level.DANGER;
        }
        if (estimatedTokens >= WARN_THRESHOLD) {
            return Level.WARN;
        }
        return Level.OK;
    }
}
