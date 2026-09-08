package com.multiagent.desktop.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.multiagent.desktop.llm.streaming.SseLineReader;
import com.multiagent.desktop.model.HealthStatus;
import com.multiagent.desktop.model.ModelInfo;
import com.multiagent.desktop.service.DebugLog;

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
        JsonNode root = sendJson(request, "GET", apiBase() + "/models", null, null);
        List<ModelInfo> models = new ArrayList<>();
        JsonNode data = root.path("data");
        if (data.isArray()) {
            for (JsonNode item : data) {
                models.add(new ModelInfo(item.path("id").asText(""), item.path("owned_by").asText(null),
                        contextLengthOf(item)));
            }
        }
        return models;
    }

    /** First of the context-window fields various OpenAI-compatible servers use, if any. */
    private static Integer contextLengthOf(JsonNode modelEntry) {
        for (String field : List.of("max_context_window", "max_model_len", "context_length",
                "context_window", "max_position_embeddings")) {
            JsonNode value = modelEntry.get(field);
            if (value != null && value.isIntegralNumber() && value.asInt() > 0) {
                return value.asInt();
            }
        }
        return null;
    }

    @Override
    public java.util.OptionalInt contextWindow(String model) {
        try {
            return listModels().stream()
                    .filter(m -> m.id().equalsIgnoreCase(model) && m.contextLength() != null)
                    .mapToInt(ModelInfo::contextLength)
                    .findFirst();
        } catch (RuntimeException e) {
            return java.util.OptionalInt.empty();
        }
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
        streamChat(messages, model, 0, onDelta, token);
    }

    /** Shared /chat/completions request body. Package-private for OpenAiClientTest. */
    ObjectNode chatBody(List<ChatRequestMessage> messages, String model, List<ToolDefinition> tools,
                         int maxTokens, boolean stream) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", model);
        body.put("stream", stream);
        body.set("messages", toMessagesNode(messages));
        if (tools != null && !tools.isEmpty()) {
            body.set("tools", toToolsNode(tools));
        }
        if (maxTokens > 0) {
            body.put("max_tokens", maxTokens);
        }
        return body;
    }

    @Override
    public void streamChat(List<ChatRequestMessage> messages, String model, int maxTokens,
                            Consumer<String> onDelta, CancellationToken token) {
        ObjectNode body = chatBody(messages, model, null, maxTokens, true);

        HttpRequest request = requestBuilder("/chat/completions")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        DebugLog.Exchange exchange = DebugLog.begin("POST", apiBase() + "/chat/completions",
                redactBase64(body.toString()));
        StringBuilder rawStream = new StringBuilder();

        InputStream stream;
        try {
            stream = sendStreaming(request, token);
        } catch (RuntimeException e) {
            exchange.fail(e);
            throw e;
        }
        try {
            SseLineReader.read(stream, data -> {
                rawStream.append(data).append('\n');
                JsonNode chunk;
                try {
                    chunk = MAPPER.readTree(data);
                } catch (IOException e) {
                    return;
                }
                // Some OpenAI-compatible servers (llama.cpp / Lemonade) return HTTP 200 and
                // then report failures - e.g. "request exceeds the available context size" -
                // as an {"error": ...} frame inside the stream. Without this the frame is
                // dropped, the stream just ends, and the app shows an empty reply with no
                // error at all.
                JsonNode error = chunk.get("error");
                if (error != null && !error.isNull()) {
                    throw ProviderException.classify(new IOException(errorText(error)));
                }
                String delta = chunk.path("choices").path(0).path("delta").path("content").asText(null);
                if (delta != null && !delta.isEmpty()) {
                    onDelta.accept(delta);
                }
            }, token);
            exchange.succeed(200, rawStream.toString());
        } catch (IOException e) {
            exchange.fail(e);
            throw ProviderException.classify(e);
        } catch (RuntimeException e) {
            exchange.fail(e);
            throw e;
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
        return completeChat(messages, model, tools, 0, token);
    }

    @Override
    public ChatCompletionResult completeChat(List<ChatRequestMessage> messages, String model,
                                              List<ToolDefinition> tools, int maxTokens,
                                              CancellationToken token) {
        ObjectNode body = chatBody(messages, model, tools, maxTokens, false);

        HttpRequest request = requestBuilder("/chat/completions")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        JsonNode root = sendJson(request, "POST", apiBase() + "/chat/completions",
                redactBase64(body.toString()), token);
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

    @Override
    public String describeImage(String model, String question, byte[] imageBytes, String mimeType,
                                 CancellationToken token) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", model);
        body.put("stream", false);
        ArrayNode messages = body.putArray("messages");
        ObjectNode userMessage = messages.addObject();
        userMessage.put("role", "user");
        ArrayNode parts = userMessage.putArray("content");
        parts.addObject().put("type", "text").put("text", question);
        String dataUrl = "data:" + mimeType + ";base64,"
                + java.util.Base64.getEncoder().encodeToString(imageBytes);
        parts.addObject().put("type", "image_url").putObject("image_url").put("url", dataUrl);

        HttpRequest request = requestBuilder("/chat/completions")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        // Redacted log body - the real payload carries a multi-MB base64 image.
        JsonNode root = sendJson(request, "POST", apiBase() + "/chat/completions",
                "<image: \"" + question + "\", " + imageBytes.length + " bytes, " + mimeType + ">", token);
        return root.path("choices").path(0).path("message").path("content").asText("");
    }

    /** Deferred to Phase 3 - see ARCHITECTURE plan. */
    @Override
    public boolean supportsImageGeneration() {
        return false;
    }

    /** Collapses long base64 image payloads in a request body so DebugLog doesn't store multi-MB lines. */
    private static String redactBase64(String json) {
        return json.replaceAll("(data:[^;\"]+;base64,)[A-Za-z0-9+/=]{48,}", "$1<elided>");
    }

    // Package-private for OpenAiClientTest (multimodal content serialization).
    ArrayNode toMessagesNode(List<ChatRequestMessage> messages) {
        ArrayNode array = MAPPER.createArrayNode();
        for (ChatRequestMessage message : messages) {
            ObjectNode node = array.addObject();
            node.put("role", message.role());
            if (message.image() != null) {
                // Multimodal user turn: content becomes an OpenAI parts array.
                ArrayNode parts = node.putArray("content");
                if (message.content() != null && !message.content().isEmpty()) {
                    parts.addObject().put("type", "text").put("text", message.content());
                }
                String dataUrl = "data:" + message.image().mimeType() + ";base64,"
                        + java.util.Base64.getEncoder().encodeToString(message.image().bytes());
                parts.addObject().put("type", "image_url").putObject("image_url").put("url", dataUrl);
            } else if (message.content() != null) {
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

    private JsonNode sendJson(HttpRequest request, String logMethod, String logUrl, String logBody,
                              CancellationToken token) {
        DebugLog.Exchange exchange = DebugLog.begin(logMethod, logUrl, logBody);
        HttpResponse<String> response;
        try {
            response = send(request, HttpResponse.BodyHandlers.ofString(), token);
        } catch (RuntimeException e) {
            exchange.fail(e);
            throw e;
        }
        exchange.succeed(response.statusCode(), response.body());
        checkStatus(response.statusCode(), response.body());
        JsonNode root;
        try {
            root = MAPPER.readTree(response.body());
        } catch (IOException e) {
            throw new ProviderException(ErrorCode.UNKNOWN, "Malformed response from server", e);
        }
        // A 2xx response whose body is actually an error object (llama.cpp / Lemonade do this
        // for context-size failures) - treat it as the failure it is instead of returning an
        // empty completion.
        JsonNode error = root.get("error");
        if (error != null && !error.isNull()) {
            throw ProviderException.classify(new IOException(errorText(error)));
        }
        return root;
    }

    /** Pulls a human string out of an OpenAI-style {@code error} node, which may be a bare string or {message, ...}. */
    private static String errorText(JsonNode error) {
        if (error.isTextual()) {
            return error.asText();
        }
        String message = error.path("message").asText("");
        return message.isEmpty() ? error.toString() : message;
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
