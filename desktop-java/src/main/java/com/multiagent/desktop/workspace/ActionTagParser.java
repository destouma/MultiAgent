package com.multiagent.desktop.workspace;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fallback parser for models without native tool-calling: extracts <list_dir/>,
 * <read_file/>, <write_file>, <delete_file/>, and <generate_image/> action tags from a
 * completion's raw text content. Mirrors shared/workspace/actionTags.ts.
 */
public final class ActionTagParser {
    private ActionTagParser() {
    }

    public record ParsedAction(String name, Map<String, String> args) {
    }

    private static final Pattern SELF_CLOSING = Pattern.compile(
            "<(list_dir|read_file|delete_file)\\s+path=\"([^\"]+)\"\\s*/>", Pattern.CASE_INSENSITIVE);
    private static final Pattern WRITE = Pattern.compile(
            "<write_file\\s+path=\"([^\"]+)\"\\s*>([\\s\\S]*?)</write_file>", Pattern.CASE_INSENSITIVE);
    private static final Pattern IMAGE = Pattern.compile(
            "<generate_image\\s+([^>]+?)\\s*/>", Pattern.CASE_INSENSITIVE);
    private static final Pattern PROMPT_ATTR = Pattern.compile("prompt=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern PATH_ATTR = Pattern.compile("path=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern SIZE_ATTR = Pattern.compile("size=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);

    public static List<ParsedAction> parse(String content) {
        List<ParsedAction> actions = new ArrayList<>();
        if (content == null || content.isEmpty()) {
            return actions;
        }

        Matcher selfClosing = SELF_CLOSING.matcher(content);
        while (selfClosing.find()) {
            actions.add(new ParsedAction(selfClosing.group(1).toLowerCase(), Map.of("path", selfClosing.group(2))));
        }

        Matcher write = WRITE.matcher(content);
        while (write.find()) {
            actions.add(new ParsedAction("write_file", Map.of("path", write.group(1), "content", write.group(2))));
        }

        Matcher image = IMAGE.matcher(content);
        while (image.find()) {
            String attrs = image.group(1);
            Matcher promptMatcher = PROMPT_ATTR.matcher(attrs);
            String prompt = promptMatcher.find() ? promptMatcher.group(1) : "";
            if (prompt.isEmpty()) {
                continue;
            }
            Matcher pathMatcher = PATH_ATTR.matcher(attrs);
            String path = pathMatcher.find() ? pathMatcher.group(1) : "images/generated.png";
            Matcher sizeMatcher = SIZE_ATTR.matcher(attrs);

            Map<String, String> args = new LinkedHashMap<>();
            args.put("prompt", prompt);
            args.put("path", path);
            if (sizeMatcher.find()) {
                args.put("size", sizeMatcher.group(1));
            }
            actions.add(new ParsedAction("generate_image", args));
        }

        return actions;
    }
}
