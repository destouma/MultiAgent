package com.multiagent.desktop.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/**
 * Opt-in capture of every raw LLM HTTP exchange (request line + body, response status +
 * body / accumulated SSE, duration, error) so "weird behaviour with the server" can be
 * inspected instead of guessed at. Off by default; toggled by the "Debug: log raw API
 * traffic" checkbox in Settings (persisted as {@code AppSettings.debugLogging}) and pushed
 * here at startup by {@code App}.
 *
 * <p>Two sinks: an in-memory {@link #entries()} list the Debug panel binds to (capped, most
 * recent last) and an append-only JSONL file next to the config (one line per exchange) for
 * attaching to a bug report. Both are best-effort - a logging failure must never break or
 * slow a real request, so every path here swallows its own errors.
 */
public final class DebugLog {

    /** In-memory ring: enough to see a full conversation's worth of calls, not so much it leaks memory over a long session. */
    private static final int MAX_ENTRIES = 500;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    // Mutated only via runOnFxThread(), so a plain observable list is safe; the Debug panel
    // binds to the unmodifiable view.
    private static final ObservableList<Entry> ENTRIES = FXCollections.observableArrayList();
    private static final ObservableList<Entry> ENTRIES_VIEW =
            FXCollections.unmodifiableObservableList(ENTRIES);

    private static volatile boolean enabled;
    private static volatile Path logFile;

    private DebugLog() {
    }

    /** One captured request/response pair. Timestamps are epoch millis; {@code status}/{@code error} may be null. */
    public record Entry(long startedAtMillis, String method, String url, String requestBody,
                        Integer status, String responseBody, long durationMillis, String error) {
    }

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /** Called once at startup with {@code %APPDATA%/MultiAgentJava/api-debug.log} (or the XDG fallback). */
    public static void setLogFile(Path path) {
        logFile = path;
    }

    public static Path logFile() {
        return logFile;
    }

    /** Live, read-only view for the Debug panel. Mutated on the FX thread. */
    public static ObservableList<Entry> entries() {
        return ENTRIES_VIEW;
    }

    public static void clear() {
        runOnFxThread(ENTRIES::clear);
    }

    /**
     * Opens a capture for one exchange. Always returns a handle; when logging is disabled the
     * handle's {@code succeed}/{@code fail} are no-ops, so call sites need no {@code if} guard.
     */
    public static Exchange begin(String method, String url, String requestBody) {
        return enabled ? new Exchange(method, url, requestBody) : Exchange.DISABLED;
    }

    /** Mutable per-call handle; exactly one of {@link #succeed} / {@link #fail} should be called, once. */
    public static final class Exchange {
        private static final Exchange DISABLED = new Exchange();

        private final boolean active;
        private final long startNanos;
        private final long startedAtMillis;
        private final String method;
        private final String url;
        private final String requestBody;
        private boolean done;

        private Exchange() {
            this.active = false;
            this.startNanos = 0;
            this.startedAtMillis = 0;
            this.method = null;
            this.url = null;
            this.requestBody = null;
        }

        private Exchange(String method, String url, String requestBody) {
            this.active = true;
            this.startNanos = System.nanoTime();
            this.startedAtMillis = System.currentTimeMillis();
            this.method = method;
            this.url = url;
            this.requestBody = requestBody;
        }

        public void succeed(Integer status, String responseBody) {
            finish(status, responseBody, null);
        }

        public void fail(Throwable error) {
            finish(null, null, error == null ? "(unknown error)"
                    : error.getClass().getSimpleName() + ": " + error.getMessage());
        }

        private void finish(Integer status, String responseBody, String error) {
            if (!active || done) {
                return;
            }
            done = true;
            long durationMillis = (System.nanoTime() - startNanos) / 1_000_000L;
            record(new Entry(startedAtMillis, method, url, requestBody, status, responseBody,
                    durationMillis, error));
        }
    }

    private static void record(Entry entry) {
        try {
            runOnFxThread(() -> {
                ENTRIES.add(entry);
                while (ENTRIES.size() > MAX_ENTRIES) {
                    ENTRIES.remove(0);
                }
            });
            appendToFile(entry);
        } catch (RuntimeException ignored) {
            // Debug logging must never take down a real request.
        }
    }

    private static void appendToFile(Entry entry) {
        Path target = logFile;
        if (target == null) {
            return;
        }
        try {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("time", Instant.ofEpochMilli(entry.startedAtMillis()).toString());
            node.put("method", entry.method());
            node.put("url", entry.url());
            node.put("durationMs", entry.durationMillis());
            if (entry.status() != null) {
                node.put("status", entry.status());
            }
            if (entry.requestBody() != null) {
                node.put("request", entry.requestBody());
            }
            if (entry.responseBody() != null) {
                node.put("response", entry.responseBody());
            }
            if (entry.error() != null) {
                node.put("error", entry.error());
            }
            Files.createDirectories(target.getParent());
            Files.writeString(target, node.toString() + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException ignored) {
            // Best-effort: a read-only dir or a serialization hiccup shouldn't surface to the user.
        }
    }

    private static void runOnFxThread(Runnable action) {
        try {
            if (Platform.isFxApplicationThread()) {
                action.run();
            } else {
                Platform.runLater(action);
            }
        } catch (IllegalStateException headless) {
            // No JavaFX runtime (unit tests): keep the file sink working, skip the observable list.
        }
    }
}
