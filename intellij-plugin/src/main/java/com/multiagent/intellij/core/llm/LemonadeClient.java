package com.multiagent.intellij.core.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.multiagent.intellij.core.service.DebugLog;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Client for Lemonade Server specifically. Inherits the standard OpenAI chat/completion
 * behavior from OpenAiClient and adds Lemonade's non-standard /health + /load extension on
 * top, for real load-status reporting and explicit model preloading before inference.
 * Mirrors shared/llm/lemonadeClient.ts.
 */
public class LemonadeClient extends OpenAiClient {

    private static final long MODEL_LOAD_TIMEOUT_MS = 10 * 60 * 1000L;
    private static final long MODEL_POLL_INTERVAL_MS = 1500L;

    // Optimistic until a probe proves the server is unreachable, so the UI doesn't flash
    // "unknown" before the first check completes.
    private volatile boolean loadStatusSupported = true;

    public LemonadeClient(ProviderSettings settings) {
        super(settings);
    }

    private JsonNode tryGetServerHealth(CancellationToken token) {
        DebugLog.Exchange exchange = DebugLog.begin("GET", apiBase() + "/health", null);
        try {
            HttpRequest request = requestBuilder("/health").GET().build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            exchange.succeed(response.statusCode(), response.body());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return null;
            }
            return MAPPER.readTree(response.body());
        } catch (IOException | InterruptedException e) {
            exchange.fail(e);
            return null;
        }
    }

    @Override
    public List<String> listLoadedModelNames() {
        JsonNode health = tryGetServerHealth(null);
        JsonNode loaded = health == null ? null : health.path("all_models_loaded");
        loadStatusSupported = loaded != null && loaded.isArray();
        if (!loadStatusSupported) {
            return List.of();
        }
        Set<String> names = new LinkedHashSet<>();
        for (JsonNode entry : loaded) {
            String name = entry.path("model_name").asText("").trim();
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        return new ArrayList<>(names);
    }

    @Override
    public boolean supportsLoadStatus() {
        return loadStatusSupported;
    }

    /**
     * Lemonade's loaded context window: /health -> all_models_loaded[] matched by model_name,
     * preferring recipe_options.ctx_size (what the model is actually loaded with) over
     * max_context_window (the model's theoretical max). Falls back to the /models field.
     */
    @Override
    public java.util.OptionalInt contextWindow(String model) {
        java.util.OptionalInt fromHealth = parseContextWindow(tryGetServerHealth(null), model);
        return fromHealth.isPresent() ? fromHealth : super.contextWindow(model);
    }

    /** Package-private + static for LemonadeClientTest: pulls the loaded ctx_size (else max_context_window) for {@code model} out of a /health payload. */
    static java.util.OptionalInt parseContextWindow(JsonNode health, String model) {
        JsonNode loaded = health == null ? null : health.path("all_models_loaded");
        if (loaded == null || !loaded.isArray() || model == null) {
            return java.util.OptionalInt.empty();
        }
        for (JsonNode entry : loaded) {
            if (!model.equalsIgnoreCase(entry.path("model_name").asText(""))) {
                continue;
            }
            int ctx = entry.path("recipe_options").path("ctx_size").asInt(0);
            if (ctx <= 0) {
                ctx = entry.path("max_context_window").asInt(0);
            }
            if (ctx > 0) {
                return java.util.OptionalInt.of(ctx);
            }
        }
        return java.util.OptionalInt.empty();
    }

    private void loadModel(String model, CancellationToken token) {
        String requestBody = MAPPER.createObjectNode().put("model_name", model).toString();
        DebugLog.Exchange exchange = DebugLog.begin("POST", apiBase() + "/load", requestBody);
        try {
            HttpRequest request = requestBuilder("/load")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            exchange.succeed(response.statusCode(), response.body());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ProviderException(ErrorCode.MODEL_NOT_LOADED,
                        "Failed to load model \"" + model + "\" (" + response.statusCode() + "): "
                                + response.body());
            }
        } catch (IOException | InterruptedException e) {
            exchange.fail(e);
            throw ProviderException.classify(e);
        }
    }

    /** Lemonade needs an explicit /load call and polling before a model is ready for inference. */
    @Override
    public void ensureModelLoaded(String model, Consumer<String> onStatus, CancellationToken token) {
        String name = model == null ? "" : model.trim();
        if (name.isEmpty()) {
            throw new ProviderException(ErrorCode.MODEL_NOT_LOADED, "No model selected.");
        }

        if (onStatus != null) {
            onStatus.accept("Checking if " + name + " is loaded...");
        }

        JsonNode health = tryGetServerHealth(token);
        JsonNode loaded = health == null ? null : health.path("all_models_loaded");
        if (loaded == null || !loaded.isArray()) {
            if (onStatus != null) {
                onStatus.accept(name + " ready");
            }
            return;
        }

        if (isLoaded(listLoadedModelNames(), name)) {
            if (onStatus != null) {
                onStatus.accept(name + " is ready");
            }
            return;
        }

        if (onStatus != null) {
            onStatus.accept("Loading " + name + "...");
        }
        loadModel(name, token);

        if (isLoaded(listLoadedModelNames(), name)) {
            if (onStatus != null) {
                onStatus.accept(name + " is ready");
            }
            return;
        }

        long deadline = System.currentTimeMillis() + MODEL_LOAD_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (token != null && token.isCancelled()) {
                throw new ProviderException(ErrorCode.CANCELLED, "Generation cancelled");
            }
            if (onStatus != null) {
                onStatus.accept("Waiting for " + name + " to become available...");
            }
            sleep(MODEL_POLL_INTERVAL_MS, token);
            if (isLoaded(listLoadedModelNames(), name)) {
                if (onStatus != null) {
                    onStatus.accept(name + " is ready");
                }
                return;
            }
        }

        throw new ProviderException(ErrorCode.MODEL_NOT_LOADED,
                "Timed out waiting for model \"" + name + "\" to load. Check the server and try again.");
    }

    private static boolean isLoaded(List<String> names, String model) {
        return names.stream().anyMatch(name -> name.equalsIgnoreCase(model));
    }

    private static void sleep(long ms, CancellationToken token) {
        try {
            Thread.sleep(Duration.ofMillis(ms));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderException(ErrorCode.CANCELLED, "Generation cancelled", e);
        }
        if (token != null && token.isCancelled()) {
            throw new ProviderException(ErrorCode.CANCELLED, "Generation cancelled");
        }
    }
}
