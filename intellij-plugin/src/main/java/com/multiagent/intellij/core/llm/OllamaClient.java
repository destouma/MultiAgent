package com.multiagent.intellij.core.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.multiagent.intellij.core.llm.streaming.NdjsonLineReader;
import com.multiagent.intellij.core.model.HealthStatus;
import com.multiagent.intellij.core.model.ModelInfo;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

/**
 * Client for the native Ollama API (/api/chat, /api/tags, /api/ps, /api/generate) - a
 * distinct wire format from OpenAI's: NDJSON streaming instead of SSE, a different
 * model-list shape, no id on tool calls (synthesized here so the shared tool-loop logic,
 * which correlates results by id, works the same as it does against OpenAI-compatible
 * servers), tool-call arguments as a JSON object instead of a string, and no
 * image-generation endpoint at all. Mirrors shared/llm/ollamaClient.ts. Deliberately
 * standalone (doesn't extend OpenAiClient, unlike LemonadeClient) since the wire format
 * genuinely differs at almost every call, not just in one or two extension points.
 */
public class OllamaClient implements LlmClient {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private volatile ProviderSettings settings;
    // Optimistic until a probe proves this server doesn't implement /api/ps.
    private volatile boolean loadStatusSupported = true;

    public OllamaClient(ProviderSettings settings) {
        this.settings = settings;
    }

    @Override
    public void updateSettings(ProviderSettings settings) {
        this.settings = settings;
    }

    @Override
    public boolean supportsImageGeneration() {
        return false;
    }

    private String apiBase() {
        String base = settings.baseUrl();
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    /** Unlike the OpenAI-compatible clients, no Authorization header at all when no key is set - a plain local Ollama server expects none. */
    private HttpRequest.Builder requestBuilder(String path) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(apiBase() + path))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMinutes(5));
        String key = settings.apiKey();
        if (key != null && !key.isBlank()) {
            builder.header("Authorization", "Bearer " + key);
        }
        return builder;
    }

    @Override
    public HealthStatus checkHealth() {
        long started = System.currentTimeMillis();
        try {
            listModels();
            return HealthStatus.ok("Connected", System.currentTimeMillis() - started);
        } catch (Exception error) {
            return HealthStatus.fail(ProviderException.classify(error).getMessage(),
                    System.currentTimeMillis() - started);
        }
    }

    @Override
    public List<ModelInfo> listModels() {
        HttpRequest request = requestBuilder("/api/tags").GET().build();
        JsonNode root = sendJson(request, null);
        List<ModelInfo> models = new ArrayList<>();
        for (JsonNode entry : root.path("models")) {
            String id = modelIdOf(entry);
            if (!id.isEmpty()) {
                models.add(new ModelInfo(id, "ollama"));
            }
        }
        return models;
    }

    @Override
    public List<String> listLoadedModelNames() {
        try {
            HttpRequest request = requestBuilder("/api/ps").GET().build();
            JsonNode root = sendJson(request, null);
            Set<String> names = new LinkedHashSet<>();
            for (JsonNode entry : root.path("models")) {
                String id = modelIdOf(entry);
                if (!id.isEmpty()) {
                    names.add(id);
                }
            }
            loadStatusSupported = true;
            return new ArrayList<>(names);
        } catch (RuntimeException e) {
            loadStatusSupported = false;
            return List.of();
        }
    }

    @Override
    public boolean supportsLoadStatus() {
        return loadStatusSupported;
    }

    private static String modelIdOf(JsonNode entry) {
        String model = entry.path("model").asText("");
        return !model.isEmpty() ? model : entry.path("name").asText("");
    }

    /**
     * Ollama loads models on demand; there's no separate preload endpoint in the public
     * API. This confirms the model is actually pulled, then fires a no-prompt /api/generate
     * call to trigger loading, mirroring the "load before first use" UX the app already has
     * for Lemonade rather than surprising the user with a silent multi-minute wait on their
     * first message.
     */
    @Override
    public void ensureModelLoaded(String model, Consumer<String> onStatus, CancellationToken token) {
        String name = model == null ? "" : model.trim();
        if (name.isEmpty()) {
            throw new ProviderException(ErrorCode.MODEL_NOT_LOADED, "No model selected.");
        }
        if (onStatus != null) {
            onStatus.accept("Checking if " + name + " is loaded...");
        }

        if (isLoaded(listLoadedModelNames(), name)) {
            if (onStatus != null) {
                onStatus.accept(name + " is ready");
            }
            return;
        }

        List<ModelInfo> known = listModels();
        if (known.stream().noneMatch(m -> m.id().equalsIgnoreCase(name))) {
            throw new ProviderException(ErrorCode.MODEL_NOT_LOADED,
                    "Model \"" + name + "\" was not found on this Ollama server. Pull it first: ollama pull " + name);
        }

        if (onStatus != null) {
            onStatus.accept("Loading " + name + "...");
        }
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", name);
        body.put("keep_alive", "5m");
        HttpRequest request = requestBuilder("/api/generate")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        sendJson(request, token);

        if (onStatus != null) {
            onStatus.accept(name + " ready");
        }
    }

    private static boolean isLoaded(List<String> names, String model) {
        return names.stream().anyMatch(name -> name.equalsIgnoreCase(model));
    }

    @Override
    public void streamChat(List<ChatRequestMessage> messages, String model, Consumer<String> onDelta,
                            CancellationToken token) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", model);
        body.put("stream", true);
        body.set("messages", toOllamaMessages(messages));

        HttpRequest request = requestBuilder("/api/chat")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        InputStream stream = sendStreaming(request, token);
        try {
            NdjsonLineReader.read(stream, data -> {
                JsonNode chunk;
                try {
                    chunk = MAPPER.readTree(data);
                } catch (IOException e) {
                    return;
                }
                if (chunk.hasNonNull("error")) {
                    throw new ProviderException(ErrorCode.UNKNOWN, chunk.path("error").asText());
                }
                String delta = chunk.path("message").path("content").asText(null);
                if (delta != null && !delta.isEmpty()) {
                    onDelta.accept(delta);
                }
            }, token);
        } catch (IOException e) {
            throw ProviderException.classify(e);
        } finally {
            try {
                stream.close();
            } catch (IOException ignored) {
                // best-effort
            }
        }
    }

    @Override
    public ChatCompletionResult completeChat(List<ChatRequestMessage> messages, String model,
                                              List<ToolDefinition> tools, CancellationToken token) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", model);
        body.put("stream", false);
        body.set("messages", toOllamaMessages(messages));
        if (tools != null && !tools.isEmpty()) {
            body.set("tools", toToolsNode(tools));
        }

        HttpRequest request = requestBuilder("/api/chat")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        JsonNode root = sendJson(request, token);
        if (root.hasNonNull("error")) {
            throw new ProviderException(ErrorCode.UNKNOWN, root.path("error").asText());
        }
        JsonNode message = root.path("message");
        String content = message.path("content").asText("");

        List<ToolCall> toolCalls = new ArrayList<>();
        JsonNode rawToolCalls = message.path("tool_calls");
        if (rawToolCalls.isArray()) {
            for (JsonNode call : rawToolCalls) {
                JsonNode function = call.path("function");
                String name = function.path("name").asText("");
                if (name.isEmpty()) {
                    continue;
                }
                // Ollama doesn't assign tool-call ids and sends arguments as a JSON object,
                // not a string - synthesize an id and re-serialize so ToolCall's shape (and
                // ToolLoopRunner's id-correlation) matches every other provider.
                String arguments;
                try {
                    arguments = MAPPER.writeValueAsString(function.path("arguments"));
                } catch (IOException e) {
                    arguments = "{}";
                }
                toolCalls.add(new ToolCall(UUID.randomUUID().toString(), name, arguments));
            }
        }
        return new ChatCompletionResult(content, toolCalls);
    }

    /**
     * Ollama wants tool_calls.function.arguments as a JSON *object*, not a string like every
     * other provider on this app's wire - the one real translation this method does, beyond
     * the plain role/content mapping every message otherwise gets. tool-role messages (a
     * tool result) pass through as plain {role, content} same as OpenAI's shape, minus a
     * tool_call_id - Ollama correlates by message order, not by id.
     */
    private ArrayNode toOllamaMessages(List<ChatRequestMessage> messages) {
        ArrayNode array = MAPPER.createArrayNode();
        for (ChatRequestMessage message : messages) {
            ObjectNode node = array.addObject();
            node.put("role", message.role());
            node.put("content", message.content() != null ? message.content() : "");

            if ("assistant".equals(message.role()) && message.toolCalls() != null && !message.toolCalls().isEmpty()) {
                ArrayNode callsNode = node.putArray("tool_calls");
                for (ToolCall call : message.toolCalls()) {
                    ObjectNode callNode = callsNode.addObject();
                    ObjectNode function = callNode.putObject("function");
                    function.put("name", call.name());
                    JsonNode argsNode;
                    try {
                        argsNode = MAPPER.readTree(call.arguments() == null || call.arguments().isBlank()
                                ? "{}" : call.arguments());
                    } catch (IOException e) {
                        argsNode = MAPPER.createObjectNode();
                    }
                    function.set("arguments", argsNode);
                }
            }
        }
        return array;
    }

    private ArrayNode toToolsNode(List<ToolDefinition> tools) {
        ArrayNode array = MAPPER.createArrayNode();
        for (ToolDefinition tool : tools) {
            ObjectNode node = array.addObject();
            node.put("type", "function");
            ObjectNode function = node.putObject("function");
            function.put("name", tool.name());
            function.put("description", tool.description());
            function.set("parameters", tool.parametersSchema());
        }
        return array;
    }

    private JsonNode sendJson(HttpRequest request, CancellationToken token) {
        HttpResponse<String> response = send(request, HttpResponse.BodyHandlers.ofString(), token);
        checkStatus(response.statusCode(), response.body());
        try {
            return MAPPER.readTree(response.body());
        } catch (IOException e) {
            throw new ProviderException(ErrorCode.UNKNOWN, "Malformed response from server", e);
        }
    }

    private InputStream sendStreaming(HttpRequest request, CancellationToken token) {
        HttpResponse<InputStream> response = send(request, HttpResponse.BodyHandlers.ofInputStream(), token);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String body = readAll(response.body());
            checkStatus(response.statusCode(), body);
        }
        return response.body();
    }

    private <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler,
                                      CancellationToken token) {
        CompletableFuture<HttpResponse<T>> future = HTTP.sendAsync(request, handler);
        if (token != null) {
            token.onCancel(() -> future.cancel(true));
        }
        try {
            return future.get();
        } catch (CancellationException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderException(ErrorCode.CANCELLED, "Generation cancelled", e);
        } catch (ExecutionException e) {
            throw ProviderException.classify(e.getCause() != null ? e.getCause() : e);
        }
    }

    private void checkStatus(int statusCode, String body) {
        if (statusCode >= 200 && statusCode < 300) {
            return;
        }
        throw ProviderException.classify(new IOException(
                "Server responded " + statusCode + (body != null && !body.isBlank() ? ": " + body : "")));
    }

    private String readAll(InputStream in) {
        try (in) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }
}
