package com.multiagent.desktop.model;

/** Mirrors shared/types.ts HealthStatus. */
public record HealthStatus(boolean ok, String message, Long latencyMs) {
    public static HealthStatus ok(String message, long latencyMs) {
        return new HealthStatus(true, message, latencyMs);
    }

    public static HealthStatus fail(String message, long latencyMs) {
        return new HealthStatus(false, message, latencyMs);
    }
}
