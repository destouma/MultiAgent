package com.multiagent.desktop.ui.components;

import java.util.Map;

/**
 * Maps a fenced code block's language tag (the word right after ```` ``` ````, e.g.
 * "python" in ```` ```python ````) to a file extension, for ChatThread's Download button.
 * Unrecognized or blank tags fall back to a plain ".txt" - saving something is always
 * better than refusing because the model didn't tag the fence.
 */
final class CodeBlockExtensions {
    private static final Map<String, String> BY_LANGUAGE = Map.ofEntries(
            Map.entry("python", "py"), Map.entry("py", "py"),
            Map.entry("javascript", "js"), Map.entry("js", "js"),
            Map.entry("typescript", "ts"), Map.entry("ts", "ts"),
            Map.entry("tsx", "tsx"), Map.entry("jsx", "jsx"),
            Map.entry("java", "java"),
            Map.entry("c", "c"),
            Map.entry("cpp", "cpp"), Map.entry("c++", "cpp"),
            Map.entry("csharp", "cs"), Map.entry("c#", "cs"), Map.entry("cs", "cs"),
            Map.entry("go", "go"), Map.entry("golang", "go"),
            Map.entry("rust", "rs"), Map.entry("rs", "rs"),
            Map.entry("ruby", "rb"), Map.entry("rb", "rb"),
            Map.entry("php", "php"),
            Map.entry("kotlin", "kt"), Map.entry("kt", "kt"),
            Map.entry("swift", "swift"),
            Map.entry("html", "html"),
            Map.entry("css", "css"), Map.entry("scss", "scss"),
            Map.entry("json", "json"),
            Map.entry("xml", "xml"),
            Map.entry("yaml", "yaml"), Map.entry("yml", "yaml"),
            Map.entry("sql", "sql"),
            Map.entry("sh", "sh"), Map.entry("bash", "sh"), Map.entry("shell", "sh"),
            Map.entry("powershell", "ps1"), Map.entry("ps1", "ps1"),
            Map.entry("markdown", "md"), Map.entry("md", "md"),
            Map.entry("toml", "toml"),
            Map.entry("ini", "ini"),
            Map.entry("r", "r"),
            Map.entry("scala", "scala"),
            Map.entry("perl", "pl"),
            Map.entry("lua", "lua"),
            Map.entry("dart", "dart"));

    private static final String DEFAULT_EXTENSION = "txt";

    private CodeBlockExtensions() {
    }

    static String extensionFor(String language) {
        if (language == null || language.isBlank()) {
            return DEFAULT_EXTENSION;
        }
        return BY_LANGUAGE.getOrDefault(language.trim().toLowerCase(), DEFAULT_EXTENSION);
    }
}
