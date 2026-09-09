package com.multiagent.intellij.core.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LemonadeClientTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static JsonNode health(String json) {
        try {
            return M.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void prefersLoadedCtxSizeOverModelMax() {
        JsonNode h = health("""
            {"all_models_loaded":[
              {"model_name":"Qwen2.5-Coder-7B","max_context_window":131072,"recipe_options":{"ctx_size":32768}}
            ]}""");
        assertEquals(OptionalInt.of(32768), LemonadeClient.parseContextWindow(h, "qwen2.5-coder-7b"));
    }

    @Test
    void fallsBackToMaxContextWindowWhenCtxSizeMissing() {
        JsonNode h = health("""
            {"all_models_loaded":[{"model_name":"m","max_context_window":8192,"recipe_options":{}}]}""");
        assertEquals(OptionalInt.of(8192), LemonadeClient.parseContextWindow(h, "m"));
    }

    @Test
    void emptyWhenModelNotLoadedOrPayloadUnusable() {
        JsonNode h = health("""
            {"all_models_loaded":[{"model_name":"other","recipe_options":{"ctx_size":4096}}]}""");
        assertTrue(LemonadeClient.parseContextWindow(h, "m").isEmpty());
        assertTrue(LemonadeClient.parseContextWindow(null, "m").isEmpty());
        assertTrue(LemonadeClient.parseContextWindow(health("{}"), "m").isEmpty());
    }
}
