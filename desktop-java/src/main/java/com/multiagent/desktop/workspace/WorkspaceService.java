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
            ".next", "coverage", "__pycache__", ".venv", "venv");

    private static final int MAX_TREE_ENTRIES = 400;
    private static final long MAX_READ_BYTES = 120_000;
    private static final long MAX_WRITE_BYTES = 500_000;

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
        walk(root, root, lines, new int[]{0});
        return String.join("\n", lines);
    }

    private void walk(Path root, Path dir, List<String> lines, int[] count) {
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
                lines.add(rel + "/");
                walk(root, entry, lines, count);
            } else {
                lines.add(rel);
            }
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
        Path target = resolveSafe(workspaceRoot, relativePath);
        if (!Files.isRegularFile(target)) {
            throw new WorkspaceException("File not found: " + relativePath);
        }
        try {
            long size = Files.size(target);
            if (size > MAX_READ_BYTES) {
                throw new WorkspaceException(
                        "File too large to read (" + size + " bytes). Max is " + MAX_READ_BYTES + ".");
            }
            return Files.readString(target, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new WorkspaceException("Failed to read file: " + e.getMessage());
        }
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
            case "read_file" -> readFile(workspaceRoot, stringArg(args, "path", ""));
            case "write_file" -> writeFile(workspaceRoot, stringArg(args, "path", ""), stringArg(args, "content", ""));
            case "delete_file" -> deleteFile(workspaceRoot, stringArg(args, "path", ""));
            case "generate_image" -> throw new WorkspaceException(
                    "generate_image must be executed by ImageService, not WorkspaceService");
            default -> throw new WorkspaceException("Unknown tool: " + name);
        };
    }

    private static String stringArg(Map<String, Object> args, String key, String fallback) {
        Object value = args.get(key);
        return value == null ? fallback : String.valueOf(value);
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
                "Read a UTF-8 text file from the workspace.",
                schema(mapper, Map.of("path", prop(mapper, "string", "Relative file path")), List.of("path"))));

        tools.add(new ToolDefinition("write_file",
                "Create or overwrite a UTF-8 text file in the workspace. Creates parent folders as needed.",
                schema(mapper, Map.of(
                        "path", prop(mapper, "string", "Relative file path"),
                        "content", prop(mapper, "string", "Full file contents to write")),
                        List.of("path", "content"))));

        tools.add(new ToolDefinition("delete_file",
                "Delete a single file in the workspace (not directories).",
                schema(mapper, Map.of("path", prop(mapper, "string", "Relative file path")), List.of("path"))));

        tools.add(new ToolDefinition("generate_image",
                "Generate an image with the local image model and save it into the workspace as a PNG.",
                schema(mapper, Map.of(
                        "prompt", prop(mapper, "string", "Image generation prompt"),
                        "path", prop(mapper, "string", "Relative PNG path to write, e.g. images/hero.png"),
                        "size", prop(mapper, "string", "Optional size like 512x512 or 256x256")),
                        List.of("prompt"))));

        return tools;
    }

    private static JsonNode prop(ObjectMapper mapper, String type, String description) {
        ObjectNode node = mapper.createObjectNode();
        node.put("type", type);
        node.put("description", description);
        return node;
    }

    private static JsonNode schema(ObjectMapper mapper, Map<String, JsonNode> properties, List<String> required) {
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
