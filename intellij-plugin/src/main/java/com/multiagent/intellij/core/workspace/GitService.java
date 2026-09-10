package com.multiagent.intellij.core.workspace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiagent.intellij.core.llm.ToolDefinition;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * Runs a small, fixed allowlist of read-mostly git subcommands with the working directory
 * pinned to the chat's workspace folder. Deliberately NOT a "run any git command" bridge:
 * each tool name maps to one hand-built argv (never a shell string), so a model can't
 * smuggle in flags like {@code --upload-pack=}, {@code -c core.fsmonitor=...}, or shell
 * metacharacters. Ref arguments are checked against {@link #REF} and rejected if they look
 * like an option; every path argument goes through {@link WorkspaceService#resolveSafe}
 * before it reaches git, so a pathspec can't point outside the workspace.
 *
 * <p>Two of the seven tools mutate the repo - {@code git_add} and {@code git_commit}
 * ({@link #MUTATING_TOOLS}) - and are gated behind the same
 * {@link com.multiagent.intellij.core.action.ActionApprover} as {@code write_file}/{@code delete_file}
 * (see {@code ToolLoopRunner}). The other five are read-only and safe to hand to the
 * orchestrator's specialists.
 *
 * <p>No TS counterpart - the Electron app has no git integration; this is a Java-only addition.
 */
public class GitService {
    /** Every git tool name, in the order {@link #gitTools()} defines them. */
    public static final List<String> TOOL_NAMES = List.of(
            "git_status", "git_diff", "git_log", "git_show", "git_branch", "git_add", "git_commit");

    /** The subset that changes the repo and therefore needs user approval before it runs. */
    public static final Set<String> MUTATING_TOOLS = Set.of("git_add", "git_commit");

    /** The subset that only inspects the repo - safe for the orchestrator's read-only specialists. */
    public static final Set<String> READ_ONLY_TOOLS = Set.of(
            "git_status", "git_diff", "git_log", "git_show", "git_branch");

    private static final long TIMEOUT_SECONDS = 20;
    // Sized for small local context windows. git_diff/git_show default to a diffstat, so a
    // full patch only reaches this cap when the model explicitly asks for patch=true.
    private static final int MAX_OUTPUT_CHARS = 20_000;
    private static final int DEFAULT_LOG_COUNT = 15;
    private static final int MAX_LOG_COUNT = 100;
    private static final int MAX_REF_LENGTH = 200;

    /** A git ref/revision that is safe to pass as a positional arg: no leading dash, no whitespace, no shell-ish characters. Covers HEAD, branch/tag names, {@code HEAD~2}, {@code a1b2c3d}, {@code main..dev}, {@code @{upstream}}. */
    private static final Pattern REF = Pattern.compile("[A-Za-z0-9._/@~^{}-]+");

    private final WorkspaceService workspace = new WorkspaceService();

    public String executeTool(String workspaceRoot, String name, Map<String, Object> args) {
        Path root = requireGitRepo(workspaceRoot);
        Map<String, Object> a = args == null ? Map.of() : args;
        return switch (name) {
            case "git_status" -> run(root, List.of("status"));
            case "git_diff" -> run(root, diffArgs(root, a));
            case "git_log" -> run(root, logArgs(root, a));
            case "git_show" -> run(root, showArgs(a));
            case "git_branch" -> run(root, List.of("branch", "--all", "-vv"));
            case "git_add" -> run(root, List.of("add", "--", pathspec(root, requireArg(a, "path"))));
            case "git_commit" -> run(root, commitArgs(a));
            default -> throw new GitException("Unknown git tool: " + name);
        };
    }

    private List<String> diffArgs(Path root, Map<String, Object> args) {
        List<String> out = new ArrayList<>();
        out.add("diff");
        // Default to a diffstat only - a full patch of a big change can blow a small
        // context window. The model asks for hunks explicitly with patch=true.
        out.add("--stat");
        if (boolArg(args, "patch")) {
            out.add("--patch");
        }
        if (boolArg(args, "staged")) {
            out.add("--staged");
        }
        String commit = strArg(args, "commit");
        if (commit != null && !commit.isBlank()) {
            out.add(ref(commit, null));
        }
        String path = strArg(args, "path");
        if (path != null && !path.isBlank()) {
            out.add("--");
            out.add(pathspec(root, path));
        }
        return out;
    }

    private List<String> showArgs(Map<String, Object> args) {
        List<String> out = new ArrayList<>();
        out.add("show");
        out.add("--stat");
        if (boolArg(args, "patch")) {
            out.add("--patch");
        }
        out.add(ref(strArg(args, "ref"), "HEAD"));
        return out;
    }

    private List<String> logArgs(Path root, Map<String, Object> args) {
        int count = Math.max(1, Math.min(intArg(args, "count", DEFAULT_LOG_COUNT), MAX_LOG_COUNT));
        List<String> out = new ArrayList<>(List.of(
                "log", "--max-count=" + count, "--date=short", "--pretty=format:%h %ad %an  %s"));
        String path = strArg(args, "path");
        if (path != null && !path.isBlank()) {
            out.add("--");
            out.add(pathspec(root, path));
        }
        return out;
    }

    private List<String> commitArgs(Map<String, Object> args) {
        String message = strArg(args, "message");
        if (message == null || message.isBlank()) {
            throw new GitException("git_commit requires a non-empty \"message\"");
        }
        List<String> out = new ArrayList<>(List.of("commit", "-m", message));
        if (boolArg(args, "all")) {
            out.add("--all");
        }
        return out;
    }

    private Path requireGitRepo(String workspaceRoot) {
        if (workspaceRoot == null || workspaceRoot.isBlank()) {
            throw new GitException("No workspace folder is bound to this chat");
        }
        Path root = Path.of(workspaceRoot).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new GitException("Workspace folder does not exist: " + workspaceRoot);
        }
        // .git is a directory in a normal clone, a file in a worktree/submodule - accept either.
        Path dotGit = root.resolve(".git");
        if (!Files.isDirectory(dotGit) && !Files.isRegularFile(dotGit)) {
            throw new GitException("Not a git repository: " + root + " - run 'git init' there first");
        }
        return root;
    }

    /** Validates a caller-supplied ref; returns {@code fallback} when blank (pass null to require one). */
    private String ref(String value, String fallback) {
        if (value == null || value.isBlank()) {
            if (fallback == null) {
                throw new GitException("A git ref is required here");
            }
            return fallback;
        }
        String trimmed = value.trim();
        if (trimmed.length() > MAX_REF_LENGTH || trimmed.startsWith("-") || !REF.matcher(trimmed).matches()) {
            throw new GitException("Refusing suspicious git ref: " + value);
        }
        return trimmed;
    }

    /** Confirms the pathspec stays inside the workspace, then returns it relative to the repo root ("." for the root itself). */
    private String pathspec(Path root, String path) {
        Path resolved = workspace.resolveSafe(root.toString(), path); // throws if it escapes the workspace
        String rel = root.relativize(resolved).toString().replace('\\', '/');
        return rel.isEmpty() ? "." : rel;
    }

    private String run(Path root, List<String> gitArgs) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("--no-pager");
        command.add("-c");
        command.add("color.ui=false");
        command.addAll(gitArgs);

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(root.toFile());
        builder.redirectErrorStream(true);
        Map<String, String> env = builder.environment();
        env.put("GIT_TERMINAL_PROMPT", "0"); // never block on a credential prompt
        env.put("GIT_OPTIONAL_LOCKS", "0");
        env.put("GIT_PAGER", "cat");
        env.put("GIT_EDITOR", "true"); // -m is always passed, but belt-and-suspenders against an editor popping
        env.remove("GIT_DIR");
        env.remove("GIT_WORK_TREE");

        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new GitException("Could not run git - is it installed and on PATH? (" + e.getMessage() + ")");
        }

        try {
            process.getOutputStream().close(); // no stdin - anything reading it gets EOF at once
        } catch (IOException ignored) {
            // closing an already-broken pipe is not interesting
        }

        CompletableFuture<String> output = CompletableFuture.supplyAsync(() -> readAll(process.getInputStream()));

        boolean finished;
        try {
            finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            output.cancel(true);
            throw new GitException("Interrupted while waiting for git");
        }
        if (!finished) {
            process.destroyForcibly();
            output.cancel(true);
            throw new GitException("git timed out after " + TIMEOUT_SECONDS + "s: git " + String.join(" ", gitArgs));
        }

        String text;
        try {
            text = output.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GitException("Interrupted while reading git output");
        } catch (TimeoutException e) {
            throw new GitException("Timed out reading git output");
        } catch (ExecutionException e) {
            throw new GitException("Failed to read git output: " + e.getCause().getMessage());
        }

        String capped = text.length() > MAX_OUTPUT_CHARS
                ? text.substring(0, MAX_OUTPUT_CHARS) + "\n... (output truncated)"
                : text;
        int exit = process.exitValue();
        if (exit != 0) {
            String body = capped.isBlank() ? "(no output)" : capped.strip();
            throw new GitException("git exited " + exit + ":\n" + body);
        }
        return capped.isBlank() ? "(git produced no output)" : capped.strip();
    }

    private static String readAll(InputStream stream) {
        try (stream) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String strArg(Map<String, Object> args, String key) {
        Object value = args.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static String requireArg(Map<String, Object> args, String key) {
        String value = strArg(args, key);
        if (value == null || value.isBlank()) {
            throw new GitException("Missing required argument \"" + key + "\"");
        }
        return value;
    }

    private static boolean boolArg(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value instanceof Boolean b) {
            return b;
        }
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private static int intArg(Map<String, Object> args, String key, int fallback) {
        Object value = args.get(key);
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** OpenAI-format tool definitions for the git_* tools - appended to WorkspaceService.workspaceTools() by ToolLoopRunner. */
    public static List<ToolDefinition> gitTools() {
        ObjectMapper mapper = new ObjectMapper();
        List<ToolDefinition> tools = new ArrayList<>();

        tools.add(new ToolDefinition("git_status",
                "Show the working tree status (staged, unstaged and untracked files) for the workspace repo.",
                WorkspaceService.schema(mapper, Map.of(), List.of())));

        tools.add(new ToolDefinition("git_diff",
                "Show changed files as a diffstat. By default: unstaged changes, summary only. Set patch=true "
                        + "for the full hunks, staged=true for the index, or pass commit (a ref or A..B range) to "
                        + "diff against that.",
                WorkspaceService.schema(mapper, Map.of(
                        "patch", WorkspaceService.prop(mapper, "boolean", "Include the full patch hunks, not just the diffstat"),
                        "staged", WorkspaceService.prop(mapper, "boolean", "Diff the staged changes (the index) instead of the working tree"),
                        "commit", WorkspaceService.prop(mapper, "string", "Optional ref or range to diff against, e.g. \"HEAD~1\" or \"main..dev\""),
                        "path", WorkspaceService.prop(mapper, "string", "Optional path to limit the diff to, relative to the repo root")),
                        List.of())));

        tools.add(new ToolDefinition("git_log",
                "List recent commits (hash, date, author, subject), newest first.",
                WorkspaceService.schema(mapper, Map.of(
                        "count", WorkspaceService.prop(mapper, "integer", "How many commits to show (default 15, max 100)"),
                        "path", WorkspaceService.prop(mapper, "string", "Optional path to limit history to, relative to the repo root")),
                        List.of())));

        tools.add(new ToolDefinition("git_show",
                "Show a single commit: its metadata and a diffstat. Set patch=true to also include the full patch.",
                WorkspaceService.schema(mapper, Map.of(
                        "ref", WorkspaceService.prop(mapper, "string", "The commit to show (default HEAD), e.g. \"HEAD~2\" or a short hash"),
                        "patch", WorkspaceService.prop(mapper, "boolean", "Include the full patch hunks, not just the diffstat")),
                        List.of())));

        tools.add(new ToolDefinition("git_branch",
                "List local and remote branches with their upstream and last-commit summary; marks the current branch.",
                WorkspaceService.schema(mapper, Map.of(), List.of())));

        tools.add(new ToolDefinition("git_add",
                "Stage a path (file or directory, relative to the repo root; use \".\" for everything). Requires user approval.",
                WorkspaceService.schema(mapper, Map.of(
                        "path", WorkspaceService.prop(mapper, "string", "Path to stage, relative to the repo root. \".\" stages all changes.")),
                        List.of("path"))));

        tools.add(new ToolDefinition("git_commit",
                "Record a commit with the given message. Set all=true to also stage every tracked modified file first "
                        + "(like 'git commit -a'). Requires user approval.",
                WorkspaceService.schema(mapper, Map.of(
                        "message", WorkspaceService.prop(mapper, "string", "The commit message"),
                        "all", WorkspaceService.prop(mapper, "boolean", "Stage all tracked modified/deleted files before committing")),
                        List.of("message"))));

        return tools;
    }
}
