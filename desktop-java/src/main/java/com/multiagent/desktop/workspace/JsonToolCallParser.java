package com.multiagent.desktop.workspace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Second fallback for models without native tool-calling support on the server side, for
 * when the model also ignores the app's own {@code <write_file>}-style XML tags (see
 * {@link ActionTagParser}) and instead prints the JSON shape it was fine-tuned to emit for
 * "tool calling" - {@code {"name": "write_file", "arguments": {"path": ..., "content": ...}}}
 * - as plain text, sometimes inside a ```json fence or Hermes-style {@code <tool_call>}
 * tags. Qwen2.5-Coder (used with Lemonade/llama.cpp servers that don't grammar-constrain
 * tool output into the OpenAI {@code tool_calls} response field) is the common real case.
 *
 * <p>Recognizes only the app's known workspace tool names, so ordinary JSON the model
 * prints for unrelated reasons (e.g. an example API payload in an answer) is never
 * mistaken for an action. Produces the same {@link ActionTagParser.ParsedAction} shape so
 * {@code ToolLoopRunner} can run both fallbacks' results through one path.
 */
public final class JsonToolCallParser {
    private static final Set<String> KNOWN_TOOLS = Set.of(
            "list_dir", "read_file", "write_file", "delete_file", "rename_file", "generate_image",
            "git_status", "git_diff", "git_log", "git_show", "git_branch", "git_add", "git_commit");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonToolCallParser() {
    }

    public static List<ActionTagParser.ParsedAction> parse(String content) {
        List<ActionTagParser.ParsedAction> actions = new ArrayList<>();
        if (content == null || content.isBlank()) {
            return actions;
        }

        int i = 0;
        while (i < content.length()) {
            int brace = content.indexOf('{', i);
            if (brace < 0) {
                break;
            }
            if (!isNameKeyNext(content, brace + 1)) {
                i = brace + 1;
                continue;
            }
            int end = matchingBrace(content, brace);
            if (end < 0) {
                i = brace + 1;
                continue;
            }
            ActionTagParser.ParsedAction action = tryParse(content.substring(brace, end + 1));
            if (action != null) {
                actions.add(action);
            }
            i = end + 1;
        }
        return actions;
    }

    private static boolean isNameKeyNext(String content, int from) {
        int j = from;
        while (j < content.length() && Character.isWhitespace(content.charAt(j))) {
            j++;
        }
        return content.startsWith("\"name\"", j);
    }

    /** Finds the index of the '}' that closes the '{' at openIndex, ignoring braces inside JSON string values (e.g. source code content with literal braces). */
    private static int matchingBrace(String content, int openIndex) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int k = openIndex; k < content.length(); k++) {
            char c = content.charAt(k);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return k;
                }
            }
        }
        return -1;
    }

    private static ActionTagParser.ParsedAction tryParse(String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            String name = root.path("name").asText(null);
            if (name == null || !KNOWN_TOOLS.contains(name)) {
                return null;
            }
            JsonNode argumentsNode = root.path("arguments");
            if (argumentsNode.isTextual()) {
                // Some models double-encode: "arguments" is itself a JSON string, not an object.
                argumentsNode = MAPPER.readTree(argumentsNode.asText());
            }
            if (!argumentsNode.isObject()) {
                return null;
            }
            Map<String, String> args = new LinkedHashMap<>();
            argumentsNode.fields().forEachRemaining(
                    entry -> args.put(entry.getKey(), entry.getValue().isTextual()
                            ? entry.getValue().asText() : entry.getValue().toString()));
            return new ActionTagParser.ParsedAction(name, args);
        } catch (Exception e) {
            return null;
        }
    }
}
