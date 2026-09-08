package com.multiagent.desktop.workspace;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fallback parser for models without native tool-calling: extracts list_dir, read_file,
 * write_file, delete_file, rename_file, generate_image and the git_* action tags from a
 * completion's raw text content. Mirrors shared/workspace/actionTags.ts (with rename_file
 * and the git_* tags added - not present in the TS original).
 */
public final class ActionTagParser {
    private ActionTagParser() {
    }

    public record ParsedAction(String name, Map<String, String> args) {
    }

    private static final Pattern SELF_CLOSING = Pattern.compile(
            "<(list_dir|delete_file)\\s+path=\"([^\"]+)\"\\s*/>", Pattern.CASE_INSENSITIVE);
    // read_file is its own pattern so it can carry optional offset/limit attributes for ranged reads.
    private static final Pattern READ = Pattern.compile(
            "<read_file\\s+path=\"([^\"]+)\"((?:\\s+[a-zA-Z]+=\"[^\"]*\")*)\\s*/>", Pattern.CASE_INSENSITIVE);
    private static final Pattern WRITE = Pattern.compile(
            "<write_file\\s+path=\"([^\"]+)\"\\s*>([\\s\\S]*?)</write_file>", Pattern.CASE_INSENSITIVE);
    private static final Pattern RENAME = Pattern.compile(
            "<rename_file\\s+path=\"([^\"]+)\"\\s+newPath=\"([^\"]+)\"\\s*/>", Pattern.CASE_INSENSITIVE);
    private static final Pattern IMAGE = Pattern.compile(
            "<generate_image\\s+([^>]+?)\\s*/>", Pattern.CASE_INSENSITIVE);
    // describe_image carries path + question in any order - an attribute bag like GIT.
    private static final Pattern DESCRIBE = Pattern.compile(
            "<describe_image((?:\\s+[a-zA-Z]+=\"[^\"]*\")*)\\s*/>", Pattern.CASE_INSENSITIVE);
    // search_file: path + pattern + optional regex/ignore_case/context/max_matches, any order.
    private static final Pattern SEARCH = Pattern.compile(
            "<search_file((?:\\s+[a-zA-Z_]+=\"[^\"]*\")*)\\s*/>", Pattern.CASE_INSENSITIVE);
    private static final Pattern GIT = Pattern.compile(
            "<(git_status|git_diff|git_log|git_show|git_branch|git_add|git_commit)"
                    + "((?:\\s+[a-zA-Z]+=\"[^\"]*\")*)\\s*/>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ATTR = Pattern.compile("([a-zA-Z_]+)=\"([^\"]*)\"");
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

        Matcher read = READ.matcher(content);
        while (read.find()) {
            Map<String, String> args = new LinkedHashMap<>();
            args.put("path", read.group(1));
            Matcher attr = ATTR.matcher(read.group(2));
            while (attr.find()) {
                String key = attr.group(1).toLowerCase();
                if (key.equals("offset") || key.equals("limit")) {
                    args.put(key, attr.group(2));
                }
            }
            actions.add(new ParsedAction("read_file", args));
        }

        Matcher write = WRITE.matcher(content);
        while (write.find()) {
            actions.add(new ParsedAction("write_file", Map.of("path", write.group(1), "content", write.group(2))));
        }

        Matcher rename = RENAME.matcher(content);
        while (rename.find()) {
            actions.add(new ParsedAction("rename_file", Map.of("path", rename.group(1), "newPath", rename.group(2))));
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

        Matcher git = GIT.matcher(content);
        while (git.find()) {
            Map<String, String> args = new LinkedHashMap<>();
            Matcher attr = ATTR.matcher(git.group(2));
            while (attr.find()) {
                args.put(attr.group(1).toLowerCase(), attr.group(2));
            }
            actions.add(new ParsedAction(git.group(1).toLowerCase(), args));
        }

        Matcher describe = DESCRIBE.matcher(content);
        while (describe.find()) {
            Map<String, String> args = new LinkedHashMap<>();
            Matcher attr = ATTR.matcher(describe.group(1));
            while (attr.find()) {
                args.put(attr.group(1).toLowerCase(), attr.group(2));
            }
            if (args.containsKey("path")) {
                actions.add(new ParsedAction("describe_image", args));
            }
        }

        Matcher search = SEARCH.matcher(content);
        while (search.find()) {
            Map<String, String> args = new LinkedHashMap<>();
            Matcher attr = ATTR.matcher(search.group(1));
            while (attr.find()) {
                args.put(attr.group(1).toLowerCase(), attr.group(2));
            }
            if (args.containsKey("path") && args.containsKey("pattern")) {
                actions.add(new ParsedAction("search_file", args));
            }
        }

        return actions;
    }
}
