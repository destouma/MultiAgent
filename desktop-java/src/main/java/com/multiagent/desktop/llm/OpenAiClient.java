package com.multiagent.desktop.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.multiagent.desktop.llm.streaming.SseLineReader;
import com.multiagent.desktop.model.HealthStatus;
import com.multiagent.desktop.model.ModelInfo;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

/**
 * Client for any generic OpenAI-compatible server: NoLlama, LM Studio, vLLM, real OpenAI,
 * etc. Only relies on the standard /v1 surface, so there's no way to know whether a model
 * is actually loaded in memory - listLoadedModelNames()/supportsLoadStatus() reflect that
 * honestly rather than guessing. Mirrors shared/llm/openAiClient.ts. For Lemonade
 * specifically, which exposes a non-standard /health + /load extension for real load
 * status and explicit preloading, see LemonadeClient, which extends this class.
 */
public class OpenAiClient implements LlmClient {

    protected static final ObjectMapper MAPPER = new ObjectMapper();
    protected static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    protected volatile ProviderSettings settings;

    public OpenAiClient(ProviderSettings settings) {
        this.settings = settings;
    }

    @Override
    public void updateSettings(ProviderSettings settings) {
        this.settings = settings;
    }

    protected String apiBase() {
        String base = settings.baseUrl();
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    protected String apiKeyOrUnused() {
        String key = settings.apiKey();
        return (key == null || key.isBlank()) ? "unused" : key;
    }

    protected HttpRequest.Builder requestBuilder(String path) {
        return HttpRequest.newBuilder()
                .uri(URI.create(apiBase() + path))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKeyOrUnused())
                .timeout(Duration.ofMinutes(5));
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
        HttpRequest request = requestBuilder("/models").GET().build();
        JsonNode root = sendJson(request, null);
        List<ModelInfo> models = new ArrayList<>();
        JsonNode data = root.path("data");
        if (data.isArray()) {
            for (JsonNode item : data) {
                models.add(new ModelInfo(item.path("id").asText(""), item.path("owned_by").asText(null)));
            }
        }
        return models;
    }

    /** Generic OpenAI-compatible servers don't expose any load-status signal. */
    @Override
    public List<String> listLoadedModelNames() {
        return List.of();
    }

    @Override
    public boolean supportsLoadStatus() {
        return false;
    }

    /**
     * Generic OpenAI-compatible servers (NoLlama, LM Studio, vLLM, real OpenAI...) manage
     * model loading themselves with no separate preload endpoint, so this is a no-op; the
     * chat call itself will surface a clear error if the model truly isn't available.
     */
    @Override
    public void ensureModelLoaded(String model, Consumer<String> onStatus, CancellationToken token) {
        String name = model == null ? "" : model.trim();
        if (name.isEmpty()) {
            throw new ProviderException(ErrorCode.MODEL_NOT_LOADED, "No model selected.");
        }
        if (onStatus != null) {
            onStatus.accept(name + " ready");
        }
    }

    @Override
    public void streamChat(List<ChatRequestMessage> messages, String model, Consumer<String> onDelta,
                            CancellationToken token) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", model);
        body.put("stream", true);
        body.set("messages", toMessagesNode(messages));

        HttpRequest request = requestBuilder("/chat/completions")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        InputStream stream = sendStreaming(request, token);
        try {
            SseLineReader.read(stream, data -> {
                JsonNode chunk;
                try {
                    chunk = MAPPER.readTree(data);
                } catch (IOException e) {
                    return;
                }
                String delta = chunk.path("choices").path(0).path("delta").path("content").asText(null);
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
        body.set("messages", toMessagesNode(messages));
        if (tools != null && !tools.isEmpty()) {
            body.set("tools", toToolsNode(tools));
        }

        HttpRequest request = requestBuilder("/chat/completions")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        JsonNode root = sendJson(request, token);
        JsonNode message = root.path("choices").path(0).path("message");
        String content = message.path("content").asText("");

        List<ToolCall> toolCalls = new ArrayList<>();
        JsonNode rawToolCalls = message.path("tool_calls");
        if (rawToolCalls.isArray()) {
            for (JsonNode call : rawToolCalls) {
                JsonNode function = call.path("function");
                if (function.isMissingNode() || function.path("name").asText("").isEmpty()) {
                    continue;
                }
                toolCalls.add(new ToolCall(
                        call.path("id").asText(""),
                        function.path("name").asText(""),
                        function.path("arguments").asText("{}")));
            }
        }
        return new ChatCompletionResult(content, toolCalls);
    }

    /** Deferred to Phase 3 - see ARCHITECTURE plan. */
    @Override
    public boolean supportsImageGeneration() {
        return false;
    }

    private ArrayNode toMessagesNode(List<ChatRequestMessage> messages) {
        ArrayNode array = MAPPER.createArrayNode();
        for (ChatRequestMessage message : messages) {
            ObjectNode node = array.addObject();
            node.put("role", message.role());
            if (message.content() != null) {
                node.put("content", message.content());
            } else {
                node.putNull("content");
            }
            if (message.toolCalls() != null && !message.toolCalls().isEmpty()) {
                ArrayNode callsNode = node.putArray("tool_calls");
                for (ToolCall call : message.toolCalls()) {
                    ObjectNode callNode = callsNode.addObject();
                    callNode.put("id", call.id());
                    callNode.put("type", "function");
                    ObjectNode function = callNode.putObject("function");
                    function.put("name", call.name());
                    function.put("arguments", call.arguments());
                }
            }
            if (message.toolCallId() != null) {
                node.put("tool_call_id", message.toolCallId());
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
