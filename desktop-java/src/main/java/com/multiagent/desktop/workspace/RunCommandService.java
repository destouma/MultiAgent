package com.multiagent.desktop.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.multiagent.desktop.llm.CancellationToken;
import com.multiagent.desktop.llm.ToolDefinition;

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
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Phase 1 of "let the agent build / run things": one {@code run_command} tool that spawns a
 * single process with the working directory pinned to the chat's workspace folder and hands
 * back its combined output + exit code. Stack-agnostic - it runs whatever executable the
 * model names ({@code cargo}, {@code npm}, {@code mvn}, {@code dotnet}, {@code ./gradlew},
 * {@code pytest}, …); the model supplies the toolchain knowledge, this class just runs it.
 *
 * <p>Guardrails, so this isn't a "run anything" hole:
 * <ul>
 *   <li><b>argv only</b> - {@code ProcessBuilder} with an argument array, never a shell
 *       string, so {@code &&}, {@code ;}, {@code |}, {@code $(…)}, backticks can't chain
 *       commands. A single-string {@code command} is whitespace-split and rejected if it
 *       contains shell metacharacters.
 *   <li><b>blocklist</b> - clearly destructive utilities, raw shells (which would defeat the
 *       argv-only design) and {@code docker}/{@code podman} (whole-host blast radius - its
 *       own future design) are refused outright.
 *   <li><b>cwd locked</b> to the workspace root; a {@code ./}-relative script is checked with
 *       {@link WorkspaceService#resolveSafe} so it can't point outside.
 *   <li><b>approval</b> - every call is gated by the same {@code ActionApprover} dialog as
 *       {@code write_file} (see {@code ToolLoopRunner}); this class assumes that already ran.
 *   <li><b>timeout + output cap</b> - killed (process tree) after {@code timeout_seconds}
 *       (default 120, max 600) or on cancellation; output truncated at {@value #MAX_OUTPUT_CHARS} chars.
 * </ul>
 *
 * <p>A non-zero exit is <em>not</em> an error here - a failing build/test is exactly the
 * signal the model needs, so it's returned as text ("Command exited with code N…").
 *
 * <p>No TS counterpart - Java-only. Long-running processes (dev servers) are Phase 2.
 */
public class RunCommandService {
    public static final String TOOL_NAME = "run_command";

    private static final long DEFAULT_TIMEOUT_SECONDS = 120;
    private static final long MAX_TIMEOUT_SECONDS = 600;
    private static final int MAX_OUTPUT_CHARS = 20_000;
    private static final long POLL_MILLIS = 250;

    /**
     * Executable basenames (lowercased, any {@code .exe}/{@code .cmd}/{@code .bat}/{@code .ps1}
     * suffix stripped) that {@code run_command} refuses before spawning anything.
     */
    public static final Set<String> BLOCKED_EXECUTABLES = Set.of(
            // destructive / privileged
            "rm", "rmdir", "del", "erase", "format", "mkfs", "dd", "shred", "chown", "chmod",
            "sudo", "su", "doas", "runas", "shutdown", "reboot", "halt", "poweroff", "diskpart",
            "reg", "regedit", "bcdedit", "netsh", "mount", "umount",
            // raw shells - argv-only is the whole point; naming a shell would smuggle one back in
            "sh", "bash", "zsh", "fish", "csh", "ksh", "dash", "ash", "tcsh",
            "cmd", "command", "powershell", "pwsh", "wsl", "busybox",
            // arbitrary-exec wrappers
            "env", "eval", "exec", "nice", "nohup", "setsid", "timeout", "watch", "xargs", "find",
            // network reach - separate trust decision (cf. the HTTP fetch tool idea)
            "ssh", "scp", "sftp", "rsync", "telnet", "nc", "ncat", "netcat", "socat", "ftp",
            // whole-host blast radius - deferred to its own design
            "docker", "podman", "docker-compose", "nerdctl", "kubectl", "helm");

    /**
     * Characters that let one shell command chain/redirect/spawn another. If any appear in a
     * whitespace-split single-string {@code command}, it's refused (the model must use the
     * structured {@code command} + {@code args} form). Globs/{@code ~}/{@code !}/{@code #} are
     * intentionally NOT here - with no shell in the picture they reach the program literally.
     */
    private static final Pattern SHELL_META = Pattern.compile("[&|;<>`$(){}\\n\\r]");

    private final WorkspaceService workspace = new WorkspaceService();

    /**
     * Runs the command described by {@code args} ({@code command}, optional {@code args[]},
     * optional {@code cwd} - a subdirectory of the workspace to run in - and optional
     * {@code timeout_seconds}). Returns the combined stdout/stderr, prefixed with an exit-code
     * line when the command failed. Throws {@link RunCommandException} for a blocked/invalid
     * command, a bad {@code cwd}, or a spawn failure.
     */
    public String executeTool(String workspaceRoot, Map<String, Object> args, CancellationToken token) {
        Path root = requireDir(workspaceRoot);
        Map<String, Object> a = args == null ? Map.of() : args;
        Path dir = resolveCwd(root, a.get("cwd"));
        List<String> argv = buildArgv(root, a);
        long timeoutSeconds = clampTimeout(a.get("timeout_seconds"));
        return run(dir, argv, timeoutSeconds, token);
    }

    /** The {@code cwd} arg, resolved to an existing directory inside the workspace; the workspace root when blank. */
    private Path resolveCwd(Path root, Object rawCwd) {
        String cwd = rawCwd == null ? "" : String.valueOf(rawCwd).trim();
        if (cwd.isEmpty() || cwd.equals(".") || cwd.equals("./")) {
            return root;
        }
        Path dir = workspace.resolveSafe(root.toString(), cwd); // throws if it escapes the workspace
        if (!Files.isDirectory(dir)) {
            throw new RunCommandException("cwd \"" + cwd + "\" is not a directory in the workspace "
                    + "(list_dir to find where the project actually lives).");
        }
        return dir;
    }

    /** The command line as a single display string, for the approval prompt and the op line. */
    public static String commandLine(Map<String, Object> args) {
        if (args == null) {
            return "(empty command)";
        }
        List<String> parts = new ArrayList<>();
        Object command = args.get("command");
        if (command != null) {
            parts.add(String.valueOf(command));
        }
        for (String arg : toStringList(args.get("args"))) {
            parts.add(arg);
        }
        String joined = String.join(" ", parts).trim();
        Object cwd = args.get("cwd");
        if (cwd != null && !String.valueOf(cwd).isBlank() && !String.valueOf(cwd).trim().equals(".")) {
            joined = joined + "  (in " + String.valueOf(cwd).trim() + ")";
        }
        return joined.isEmpty() ? "(empty command)" : joined;
    }

    private List<String> buildArgv(Path root, Map<String, Object> args) {
        Object rawCommand = args.get("command");
        if (rawCommand == null || String.valueOf(rawCommand).isBlank()) {
            throw new RunCommandException("run_command requires a non-empty \"command\"");
        }
        String command = String.valueOf(rawCommand).trim();
        List<String> extra = toStringList(args.get("args"));

        List<String> argv = new ArrayList<>();
        if (extra.isEmpty() && command.chars().anyMatch(Character::isWhitespace)) {
            // Lenient: the model put the whole command line in "command". Split on whitespace,
            // but only if it's plainly a command + flags - no shell metacharacters.
            if (SHELL_META.matcher(command).find()) {
                throw new RunCommandException("Refusing a command with shell metacharacters: \"" + command
                        + "\". Pass the executable in \"command\" and each argument separately in \"args\".");
            }
            argv.addAll(List.of(command.split("\\s+")));
        } else {
            argv.add(command);
            argv.addAll(extra);
        }

        String executable = argv.get(0);
        String basename = executable.replace('\\', '/');
        basename = basename.substring(basename.lastIndexOf('/') + 1).toLowerCase();
        for (String suffix : List.of(".exe", ".cmd", ".bat", ".ps1", ".com")) {
            if (basename.endsWith(suffix)) {
                basename = basename.substring(0, basename.length() - suffix.length());
                break;
            }
        }
        if (BLOCKED_EXECUTABLES.contains(basename)) {
            throw new RunCommandException("\"" + basename + "\" is not allowed via run_command "
                    + "(destructive, a raw shell, network, or container tooling). "
                    + "Run the project's own build/test tool directly instead.");
        }
        // A workspace-relative script (./build.sh, scripts/x): confirm it stays inside the folder.
        if (executable.startsWith("./") || executable.startsWith("../") || executable.startsWith(".\\")
                || executable.startsWith("..\\")) {
            workspace.resolveSafe(root.toString(), executable); // throws if it escapes
        }
        return argv;
    }

    private String run(Path root, List<String> argv, long timeoutSeconds, CancellationToken token) {
        ProcessBuilder builder = new ProcessBuilder(argv);
        builder.directory(root.toFile());
        builder.redirectErrorStream(true);
        builder.environment().put("CI", "1"); // nudge tools into non-interactive mode

        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new RunCommandException("Could not run \"" + argv.get(0) + "\" - is it installed and on PATH? ("
                    + e.getMessage() + ")");
        }
        try {
            process.getOutputStream().close(); // no stdin; anything reading it gets EOF at once
        } catch (IOException ignored) {
            // closing an already-broken pipe is not interesting
        }

        CompletableFuture<String> output = CompletableFuture.supplyAsync(() -> readAll(process.getInputStream()));

        if (token != null && token.isCancelled()) {
            killTree(process);
            output.cancel(true);
            throw new RunCommandException(String.join(" ", argv) + " was cancelled and killed");
        }

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        boolean cancelled = false;
        boolean timedOut = false;
        try {
            while (process.isAlive()) {
                if (token != null && token.isCancelled()) {
                    cancelled = true;
                    break;
                }
                if (System.nanoTime() >= deadline) {
                    timedOut = true;
                    break;
                }
                process.waitFor(POLL_MILLIS, TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cancelled = true;
        }

        if (cancelled || timedOut) {
            killTree(process);
            output.cancel(true);
            String reason = timedOut
                    ? "timed out after " + timeoutSeconds + "s and was killed"
                    : "was cancelled and killed";
            throw new RunCommandException(String.join(" ", argv) + " " + reason);
        }

        String text;
        try {
            text = output.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            text = "(could not read command output: " + e.getMessage() + ")";
        }
        String capped = text.length() > MAX_OUTPUT_CHARS
                ? text.substring(0, MAX_OUTPUT_CHARS) + "\n... (output truncated)"
                : text;
        capped = capped.strip();

        int exit = process.exitValue();
        if (exit != 0) {
            return "Command exited with code " + exit + ".\n\n" + (capped.isEmpty() ? "(no output)" : capped);
        }
        return capped.isEmpty() ? "(command produced no output; exit 0)" : capped;
    }

    private static void killTree(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        try {
            // Give the OS a moment to actually reap it, so a caller's cwd (e.g. a @TempDir)
            // isn't still locked when this returns.
            process.waitFor(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Path requireDir(String workspaceRoot) {
        if (workspaceRoot == null || workspaceRoot.isBlank()) {
            throw new RunCommandException("No workspace folder is bound to this chat");
        }
        Path root = Path.of(workspaceRoot).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new RunCommandException("Workspace folder does not exist: " + workspaceRoot);
        }
        return root;
    }

    private static long clampTimeout(Object raw) {
        if (raw == null) {
            return DEFAULT_TIMEOUT_SECONDS;
        }
        long value;
        if (raw instanceof Number n) {
            value = n.longValue();
        } else {
            try {
                value = Long.parseLong(String.valueOf(raw).trim());
            } catch (NumberFormatException e) {
                return DEFAULT_TIMEOUT_SECONDS;
            }
        }
        return Math.max(1, Math.min(value, MAX_TIMEOUT_SECONDS));
    }

    @SuppressWarnings("unchecked")
    private static List<String> toStringList(Object raw) {
        if (raw == null) {
            return List.of();
        }
        if (raw instanceof List<?> list) {
            List<String> out = new ArrayList<>(list.size());
            for (Object item : list) {
                if (item != null) {
                    out.add(String.valueOf(item));
                }
            }
            return out;
        }
        // Some models send args as a JSON-array string.
        String s = String.valueOf(raw).trim();
        if (s.startsWith("[") && s.endsWith("]")) {
            try {
                return toStringList(new ObjectMapper().readValue(s, List.class));
            } catch (IOException ignored) {
                // fall through
            }
        }
        return s.isEmpty() ? List.of() : List.of(s);
    }

    private static String readAll(InputStream stream) {
        try (stream) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** OpenAI-format tool definition - appended to the tool list by {@code ToolLoopRunner}. */
    public static List<ToolDefinition> commandTools() {
        ObjectMapper mapper = new ObjectMapper();

        ObjectNode argsProp = mapper.createObjectNode();
        argsProp.put("type", "array");
        argsProp.put("description", "Arguments, one string per element - e.g. [\"run\", \"build\"] or "
                + "[\"test\", \"--no-color\"]. Do NOT put flags in \"command\".");
        argsProp.putObject("items").put("type", "string");

        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.set("command", WorkspaceService.prop(mapper, "string",
                "The executable only, e.g. \"npm\", \"cargo\", \"mvn\", \"dotnet\", \"pytest\", \"./gradlew\". "
                        + "No shell operators, pipes or redirects."));
        props.set("args", argsProp);
        props.set("cwd", WorkspaceService.prop(mapper, "string",
                "Optional subdirectory of the workspace to run in (e.g. \"frontend\" or \"packages/api\" for a "
                        + "monorepo). Defaults to the workspace root - the bound folder IS the project root, so "
                        + "usually leave this out."));
        props.set("timeout_seconds", WorkspaceService.prop(mapper, "integer",
                "Max seconds to let it run before it's killed (default 120, max 600)."));
        schema.putArray("required").add("command");

        return List.of(new ToolDefinition(TOOL_NAME,
                "Run one build/test/lint/run command in the workspace folder and return its combined "
                        + "output and exit code. Use the project's own toolchain (read the project files first "
                        + "to know which). Runs a single process - no shell, no pipes, no chaining, no cd; the "
                        + "working directory is always the workspace root. Requires user approval. A non-zero "
                        + "exit code is returned as text, not an error - read it and fix the cause.",
                schema));
    }
}
