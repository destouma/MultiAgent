package com.multiagent.intellij.core.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * (de)serializes {@code Conversation.specialistModels} - a JSON object mapping a specialist
 * persona id to the model that conversation should run it on, e.g.
 * {@code {"coder":"Qwen2.5-Coder-7B"}}. Bad or empty input round-trips to an empty map / "".
 */
public final class SpecialistModels {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SpecialistModels() {
    }

    public static Map<String, String> parse(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, String> parsed = MAPPER.readValue(json, new TypeReference<Map<String, String>>() { });
            Map<String, String> clean = new LinkedHashMap<>();
            parsed.forEach((k, v) -> {
                if (k != null && !k.isBlank() && v != null && !v.isBlank()) {
                    clean.put(k, v);
                }
            });
            return clean;
        } catch (Exception e) {
            return Map.of();
        }
    }

    public static String write(Map<String, String> map) {
        if (map == null || map.isEmpty()) {
            return "";
        }
        try {
            return MAPPER.writeValueAsString(map);
        } catch (Exception e) {
            return "";
        }
    }
}
