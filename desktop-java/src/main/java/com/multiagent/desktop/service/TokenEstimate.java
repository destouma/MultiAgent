package com.multiagent.desktop.service;

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

    public static int estimateTokens(List<String> texts) {
        long chars = 0;
        for (String text : texts) {
            chars += text == null ? 0 : text.length();
        }
        return (int) Math.ceil(chars / 4.0);
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
