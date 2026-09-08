package com.multiagent.desktop.workspace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.multiagent.desktop.llm.ToolDefinition;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Sandboxes all file ops under a workspace root, mirroring
 * shared/workspace/workspaceService.ts exactly, including its two-layer escape check:
 * first a plain path-relative check, then a symlink-resolved (realpath) check walking up
 * to the nearest existing ancestor - a symlink inside the workspace pointing outside it
 * would pass the first check alone. This is the security-critical piece of Phase 2; any
 * change here needs the same scrutiny as the TS original got.
 */
public class WorkspaceService {
    private static final Set<String> IGNORE_DIRS = Set.of(
            "node_modules", ".git", "dist", "dist-electron", "release",
            ".next", "coverage", "__pycache__", ".venv", "venv",
            // Build output / IDE metadata - noise in the tree, and huge in real repos.
            "target", "build", ".gradle", ".mvn", ".idea", ".vscode", ".settings",
            ".tox", ".mypy_cache", ".pytest_cache", ".cache");

    // These are sized for small local context windows (some Lemonade setups are 4-8k tokens).
    // The tree goes in the system prompt every turn; keep it shallow and let the model
    // list_dir into deeper folders. A whole-file read is capped low too - the model pages
    // large files with read_file offset/limit. Diverges deliberately from the TS original's
    // looser limits (shared/workspace/workspaceService.ts).
    private static final int MAX_TREE_ENTRIES = 200;
    private static final int MAX_TREE_DEPTH = 2;
    private static final long MAX_READ_BYTES = 40_000;
    private static final long MAX_WRITE_BYTES = 500_000;

    // Ranged reads (offset/limit): defaults and hard ceilings.
    private static final int DEFAULT_RANGE_LINES = 160;
    private static final int MAX_RANGE_LINES = 2_000;
    private static final long MAX_RANGED_FILE_BYTES = 10_000_000;

    // describe_image: the raw image is base64-inflated (+33%) into one request and then into
    // the model's (small) context, so keep it modest.
    private static final long MAX_IMAGE_BYTES = 4_000_000;

    public Path resolveSafe(String workspaceRoot, String relativePath) {
        Path root = Path.of(workspaceRoot).toAbsolutePath().normalize();
        String rel = (relativePath == null || relativePath.isBlank()) ? "." : relativePath;
        Path target = root.resolve(rel).normalize();

        if (!isWithin(root, target)) {
            throw new WorkspaceException("Path escapes the workspace folder");
        }

        // Path.normalize() alone doesn't follow symlinks: a link inside the workspace
        // that points outside it would otherwise pass the check above. Walk up to the
        // nearest existing ancestor and compare real (symlink-resolved) paths.
        Path realRoot;
        try {
            realRoot = root.toRealPath();
        } catch (IOException e) {
            throw new WorkspaceException("Workspace folder does not exist");
        }

        Path existingAncestor = target;
        while (!Files.exists(existingAncestor)) {
            Path parent = existingAncestor.getParent();
            if (parent == null || parent.equals(existingAncestor)) {
                break;
            }
            existingAncestor = parent;
        }

        Path realExistingAncestor;
        try {
            realExistingAncestor = existingAncestor.toRealPath();
        } catch (IOException e) {
            throw new WorkspaceException("Workspace folder does not exist");
        }

        if (!isWithin(realRoot, realExistingAncestor)) {
            throw new WorkspaceException("Path escapes the workspace folder");
        }

        return target;
    }

    private boolean isWithin(Path root, Path candidate) {
        if (candidate.equals(root)) {
            return true;
        }
        try {
            Path relative = root.relativize(candidate);
            String asString = relative.toString();
            return !asString.equals("..") && !asString.startsWith(".." + java.io.File.separator);
        } catch (IllegalArgumentException e) {
            // e.g. different drive letters on Windows - definitely not "within".
            return false;
        }
    }

    public String buildTree(String workspaceRoot) {
        Path root = Path.of(workspaceRoot).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new WorkspaceException("Workspace folder does not exist");
        }

        List<String> lines = new ArrayList<>();
        Path name = root.getFileName();
        lines.add((name != null ? name.toString() : root.toString()) + "/");
        walk(root, root, lines, new int[]{0}, 1);
        return String.join("\n", lines);
    }

    private void walk(Path root, Path dir, List<String> lines, int[] count, int depth) {
        if (count[0] >= MAX_TREE_ENTRIES) {
            return;
        }
        List<Path> entries;
        try (Stream<Path> stream = Files.list(dir)) {
            entries = stream
                    .sorted((a, b) -> {
                        boolean aDir = Files.isDirectory(a);
                        boolean bDir = Files.isDirectory(b);
                        if (aDir != bDir) {
                            return aDir ? -1 : 1;
                        }
                        return a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString());
                    })
                    .toList();
        } catch (IOException e) {
            return;
        }

        for (Path entry : entries) {
            if (count[0] >= MAX_TREE_ENTRIES) {
                lines.add("… (truncated)");
                return;
            }
            String entryName = entry.getFileName().toString();
            boolean isDir = Files.isDirectory(entry);
            if (entryName.startsWith(".") && !entryName.equals(".env.example")) {
                continue;
            }
            if (isDir && IGNORE_DIRS.contains(entryName)) {
                continue;
            }
            count[0] += 1;
            String rel = root.relativize(entry).toString().replace('\\', '/');
            if (isDir) {
                if (depth < MAX_TREE_DEPTH) {
                    lines.add(rel + "/");
                    walk(root, entry, lines, count, depth + 1);
                } else {
                    // At the depth cap: list the folder but don't descend. The trailing "…"
                    // tells the model to list_dir into it if it needs to see the contents.
                    lines.add(rel + "/" + (isNonEmptyDir(entry) ? " …" : ""));
                }
            } else {
                lines.add(rel);
            }
        }
    }

    private boolean isNonEmptyDir(Path dir) {
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.findFirst().isPresent();
        } catch (IOException e) {
            return false;
        }
    }

    public String listDir(String workspaceRoot, String relativePath) {
        Path target = resolveSafe(workspaceRoot, relativePath);
        if (!Files.isDirectory(target)) {
            throw new WorkspaceException("Not a directory: " + relativePath);
        }
        try (Stream<Path> stream = Files.list(target)) {
            return stream
                    .filter(p -> !(Files.isDirectory(p) && IGNORE_DIRS.contains(p.getFileName().toString())))
                    .map(p -> (Files.isDirectory(p) ? "dir" : "file") + "\t" + p.getFileName())
                    .collect(Collectors.joining("\n"));
        } catch (IOException e) {
            throw new WorkspaceException("Failed to list directory: " + e.getMessage());
        }
    }

    public String readFile(String workspaceRoot, String relativePath) {
        return readFile(workspaceRoot, relativePath, null, null);
    }

    /**
     * Reads a UTF-8 text file. With {@code startLine}/{@code maxLines} both null this is a
     * whole-file read capped at {@link #MAX_READ_BYTES} (unchanged behaviour). Passing either
     * one returns just that window of lines, prefixed with a {@code (lines X-Y of N)} header -
     * the way to pull a slice out of a file that's too big to read whole on a small context.
     */
    public String readFile(String workspaceRoot, String relativePath, Integer startLine, Integer maxLines) {
        Path target = resolveSafe(workspaceRoot, relativePath);
        if (!Files.isRegularFile(target)) {
            throw new WorkspaceException("File not found: " + relativePath);
        }
        boolean ranged = startLine != null || maxLines != null;
        try {
            long size = Files.size(target);
            if (!ranged) {
                if (size > MAX_READ_BYTES) {
                    throw new WorkspaceException(
                            "File too large to read (" + size + " bytes). Max is " + MAX_READ_BYTES
                                    + ". Pass offset/limit to read a range of lines instead.");
                }
                return Files.readString(target, StandardCharsets.UTF_8);
            }
            if (size > MAX_RANGED_FILE_BYTES) {
                throw new WorkspaceException("File too large even for a ranged read (" + size + " bytes).");
            }
            return readLineRange(target, startLine, maxLines);
        } catch (IOException e) {
            throw new WorkspaceException("Failed to read file: " + e.getMessage());
        }
    }

    private String readLineRange(Path target, Integer startLine, Integer maxLines) throws IOException {
        int from = startLine == null ? 1 : Math.max(1, startLine);
        int count = maxLines == null
                ? DEFAULT_RANGE_LINES
                : Math.max(1, Math.min(maxLines, MAX_RANGE_LINES));

        List<String> all;
        try (Stream<String> lines = Files.lines(target, StandardCharsets.UTF_8)) {
            all = lines.toList();
        }
        int total = all.size();
        if (from > total) {
            return "(file has " + total + " line" + (total == 1 ? "" : "s") + "; offset " + from + " is past the end)";
        }
        int end = Math.min(total, from - 1 + count);

        StringBuilder out = new StringBuilder();
        out.append("(lines ").append(from).append('-').append(end).append(" of ").append(total).append(")\n");
        long bytes = 0;
        for (int i = from - 1; i < end; i++) {
            String line = all.get(i);
            bytes += line.length() + 1L;
            if (bytes > MAX_READ_BYTES) {
                out.append("... (range truncated at ").append(MAX_READ_BYTES).append(" bytes)");
                return out.toString();
            }
            out.append(line).append('\n');
        }
        return out.toString();
    }

    /**
     * Reads a workspace image file as raw bytes for the describe_image tool. Same sandboxing
     * as {@link #readFile} (via {@link #resolveSafe}), a dedicated size cap, and an extension
     * allowlist so it can't be pointed at arbitrary binaries.
     */
    public byte[] readImageBytes(String workspaceRoot, String relativePath) {
        Path target = resolveSafe(workspaceRoot, relativePath);
        if (!Files.isRegularFile(target)) {
            throw new WorkspaceException("Image not found: " + relativePath);
        }
        guessImageMime(relativePath); // throws WorkspaceException for an unsupported extension
        try {
            long size = Files.size(target);
            if (size > MAX_IMAGE_BYTES) {
                throw new WorkspaceException("Image too large (" + size + " bytes). Max is "
                        + MAX_IMAGE_BYTES + " bytes.");
            }
            return Files.readAllBytes(target);
        } catch (IOException e) {
            throw new WorkspaceException("Failed to read image: " + e.getMessage());
        }
    }

    /** Maps a path's extension to an image MIME type; throws for anything not in the allowlist. */
    public static String guessImageMime(String path) {
        String lower = path.toLowerCase();
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".gif")) {
            return "image/gif";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        throw new WorkspaceException("Unsupported image type: " + path
                + " (allowed: .png .jpg .jpeg .gif .webp)");
    }

    /** Like readFile, but returns null instead of throwing - used for checkpoint snapshots where "doesn't exist" is expected. */
    public String tryReadFile(String workspaceRoot, String relativePath) {
        try {
            return readFile(workspaceRoot, relativePath);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public String writeFile(String workspaceRoot, String relativePath, String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_WRITE_BYTES) {
            throw new WorkspaceException("Write too large. Max is " + MAX_WRITE_BYTES + " bytes.");
        }
        Path target = resolveSafe(workspaceRoot, relativePath);
        try {
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            Files.write(target, bytes);
        } catch (IOException e) {
            throw new WorkspaceException("Failed to write file: " + e.getMessage());
        }
        return "Wrote " + relativePath.replace('\\', '/') + " (" + bytes.length + " bytes)";
    }

    /** New capability (not in the TS original): renames/moves a file within the workspace. Refuses to overwrite an existing destination. */
    public String renameFile(String workspaceRoot, String relativePath, String newRelativePath) {
        Path source = resolveSafe(workspaceRoot, relativePath);
        Path destination = resolveSafe(workspaceRoot, newRelativePath);
        if (!Files.isRegularFile(source)) {
            throw new WorkspaceException("File not found: " + relativePath);
        }
        if (Files.exists(destination)) {
            throw new WorkspaceException("A file already exists at: " + newRelativePath);
        }
        try {
            if (destination.getParent() != null) {
                Files.createDirectories(destination.getParent());
            }
            Files.move(source, destination);
        } catch (IOException e) {
            throw new WorkspaceException("Failed to rename file: " + e.getMessage());
        }
        return "Renamed " + relativePath.replace('\\', '/') + " to " + newRelativePath.replace('\\', '/');
    }

    public String deleteFile(String workspaceRoot, String relativePath) {
        Path target = resolveSafe(workspaceRoot, relativePath);
        if (!Files.exists(target)) {
            throw new WorkspaceException("Path not found: " + relativePath);
        }
        if (Files.isDirectory(target)) {
            throw new WorkspaceException("Deleting directories is not allowed");
        }
        try {
            Files.delete(target);
        } catch (IOException e) {
            throw new WorkspaceException("Failed to delete file: " + e.getMessage());
        }
        return "Deleted " + relativePath.replace('\\', '/');
    }

    public String executeTool(String workspaceRoot, String name, Map<String, Object> args) {
        return switch (name) {
            case "list_dir" -> listDir(workspaceRoot, stringArg(args, "path", "."));
            case "read_file" -> readFile(workspaceRoot, stringArg(args, "path", ""),
                    intArg(args, "offset"), intArg(args, "limit"));
            case "write_file" -> writeFile(workspaceRoot, stringArg(args, "path", ""), stringArg(args, "content", ""));
            case "delete_file" -> deleteFile(workspaceRoot, stringArg(args, "path", ""));
            case "rename_file" -> renameFile(workspaceRoot, stringArg(args, "path", ""), stringArg(args, "newPath", ""));
            case "generate_image" -> throw new WorkspaceException(
                    "generate_image must be executed by ImageService, not WorkspaceService");
            default -> throw new WorkspaceException("Unknown tool: " + name);
        };
    }

    private static String stringArg(Map<String, Object> args, String key, String fallback) {
        Object value = args.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    /** Reads an int-ish tool arg (JSON numbers arrive as Integer/Long/Double; models sometimes send a string). Null/blank/garbage -> null. */
    private static Integer intArg(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** OpenAI-format tool definitions for list_dir/read_file/write_file/delete_file/generate_image. */
    public static List<ToolDefinition> workspaceTools() {
        ObjectMapper mapper = new ObjectMapper();
        List<ToolDefinition> tools = new ArrayList<>();

        tools.add(new ToolDefinition("list_dir",
                "List files and directories under a relative path in the workspace.",
                schema(mapper, Map.of("path", prop(mapper, "string", "Relative directory path. Use \".\" for workspace root.")),
                        List.of())));

        tools.add(new ToolDefinition("read_file",
                "Read a UTF-8 text file from the workspace. Omit offset/limit to read the whole file; "
                        + "pass them to read only a line range (needed for files too large to read whole).",
                schema(mapper, Map.of(
                        "path", prop(mapper, "string", "Relative file path"),
                        "offset", prop(mapper, "integer", "1-based line to start at (optional)"),
                        "limit", prop(mapper, "integer", "Max lines to return from offset (optional, default 200)")),
                        List.of("path"))));

        tools.add(new ToolDefinition("write_file",
                "Create or overwrite a UTF-8 text file in the workspace. Creates parent folders as needed.",
                schema(mapper, Map.of(
                        "path", prop(mapper, "string", "Relative file path"),
                        "content", prop(mapper, "string", "Full file contents to write")),
                        List.of("path", "content"))));

        tools.add(new ToolDefinition("delete_file",
                "Delete a single file in the workspace (not directories).",
                schema(mapper, Map.of("path", prop(mapper, "string", "Relative file path")), List.of("path"))));

        tools.add(new ToolDefinition("rename_file",
                "Rename or move a single file within the workspace. Fails if the destination already exists.",
                schema(mapper, Map.of(
                        "path", prop(mapper, "string", "Current relative file path"),
                        "newPath", prop(mapper, "string", "New relative file path")),
                        List.of("path", "newPath"))));

        tools.add(new ToolDefinition("generate_image",
                "Generate an image with the local image model and save it into the workspace as a PNG.",
                schema(mapper, Map.of(
                        "prompt", prop(mapper, "string", "Image generation prompt"),
                        "path", prop(mapper, "string", "Relative PNG path to write, e.g. images/hero.png"),
                        "size", prop(mapper, "string", "Optional size like 512x512 or 256x256")),
                        List.of("prompt"))));

        return tools;
    }

    /**
     * The describe_image tool - kept out of {@link #workspaceTools()} (which has an
     * exact-list test) so {@code ToolLoopRunner} can add it only when the active server has a
     * vision model configured.
     */
    public static ToolDefinition visionTool() {
        ObjectMapper mapper = new ObjectMapper();
        return new ToolDefinition("describe_image",
                "Ask the configured vision model about an image file in the workspace. Returns "
                        + "the vision model's text answer. Use for screenshots, diagrams, photos, "
                        + "scanned pages, UI mockups.",
                schema(mapper, Map.of(
                        "path", prop(mapper, "string", "Relative path to a .png/.jpg/.jpeg/.gif/.webp image in the workspace"),
                        "question", prop(mapper, "string", "What to ask about the image, e.g. \"transcribe all text\" or \"what error is shown\"")),
                        List.of("path", "question")));
    }

    // Package-private (not private) so GitService can build its own tool schemas the same way.
    static JsonNode prop(ObjectMapper mapper, String type, String description) {
        ObjectNode node = mapper.createObjectNode();
        node.put("type", type);
        node.put("description", description);
        return node;
    }

    static JsonNode schema(ObjectMapper mapper, Map<String, JsonNode> properties, List<String> required) {
        ObjectNode node = mapper.createObjectNode();
        node.put("type", "object");
        ObjectNode propsNode = node.putObject("properties");
        properties.forEach(propsNode::set);
        if (!required.isEmpty()) {
            var requiredNode = node.putArray("required");
            required.forEach(requiredNode::add);
        }
        return node;
    }
}
