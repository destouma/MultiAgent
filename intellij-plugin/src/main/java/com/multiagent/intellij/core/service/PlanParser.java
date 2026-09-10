package com.multiagent.intellij.core.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the orchestrator's specialist-selection response (expected to be a JSON object
 * embedded in the completion text). Falls back to a single default specialist if the
 * model didn't return valid, well-formed JSON. Mirrors
 * desktop/electron/services/planParser.ts exactly.
 */
public final class PlanParser {
    private PlanParser() {
    }

    public record PlanResult(List<String> specialists, String rationale) {
    }

    private static final Pattern JSON_BLOCK = Pattern.compile("\\{[\\s\\S]*\\}");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static PlanResult parsePlan(String raw, List<String> allowedIds) {
        if (raw == null) {
            return fallback(allowedIds);
        }
        Matcher matcher = JSON_BLOCK.matcher(raw);
        if (!matcher.find()) {
            return fallback(allowedIds);
        }

        try {
            JsonNode node = MAPPER.readTree(matcher.group());
            Set<String> ids = new LinkedHashSet<>();
            JsonNode specialistsNode = node.path("specialists");
            if (specialistsNode.isArray()) {
                for (JsonNode item : specialistsNode) {
                    String id = item.asText();
                    if (allowedIds.contains(id)) {
                        ids.add(id);
                    }
                }
            }
            List<String> limited = new ArrayList<>(ids);
            if (limited.size() > 3) {
                limited = limited.subList(0, 3);
            }

            String rationale = node.path("rationale").asText("").trim();
            if (rationale.isEmpty()) {
                rationale = "Plan selected by orchestrator";
            }

            return new PlanResult(limited, rationale);
        } catch (Exception e) {
            return fallback(allowedIds);
        }
    }

    private static PlanResult fallback(List<String> allowedIds) {
        if (allowedIds == null || allowedIds.isEmpty()) {
            return new PlanResult(List.of(), "Default plan");
        }
        return new PlanResult(List.of(allowedIds.get(0)), "Default plan");
    }
}
