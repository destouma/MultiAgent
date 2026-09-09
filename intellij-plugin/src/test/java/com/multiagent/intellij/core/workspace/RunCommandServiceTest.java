package com.multiagent.intellij.core.workspace;

import com.multiagent.intellij.core.llm.CancellationToken;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunCommandServiceTest {

    private final RunCommandService service = new RunCommandService();
    private static final String JAVA = Path.of(System.getProperty("java.home"), "bin", "java").toString();

    @Test
    void runsACommandAndReturnsItsOutput(@TempDir Path ws) {
        String out = service.executeTool(ws.toString(),
                Map.of("command", JAVA, "args", List.of("-version")), new CancellationToken());
        // `java -version` prints to stderr, which redirectErrorStream folds into the captured output.
        assertTrue(out.toLowerCase().contains("version"), "expected a version banner, got: " + out);
    }

    @Test
    void aNonZeroExitIsReturnedAsTextNotThrown(@TempDir Path ws) {
        String out = service.executeTool(ws.toString(),
                Map.of("command", JAVA, "args", List.of("--definitely-not-a-flag")), new CancellationToken());
        assertTrue(out.startsWith("Command exited with code "), "got: " + out);
    }

    @Test
    void refusesABlockedExecutable(@TempDir Path ws) {
        RunCommandException rm = assertThrows(RunCommandException.class, () -> service.executeTool(
                ws.toString(), Map.of("command", "rm", "args", List.of("-rf", "build")), new CancellationToken()));
        assertTrue(rm.getMessage().contains("not allowed"), rm.getMessage());

        assertThrows(RunCommandException.class, () -> service.executeTool(
                ws.toString(), Map.of("command", "bash", "args", List.of("-c", "echo hi")), new CancellationToken()));
        assertThrows(RunCommandException.class, () -> service.executeTool(
                ws.toString(), Map.of("command", "docker", "args", List.of("ps")), new CancellationToken()));
    }

    @Test
    void refusesShellMetacharactersInASingleStringCommand(@TempDir Path ws) {
        RunCommandException e = assertThrows(RunCommandException.class, () -> service.executeTool(
                ws.toString(), Map.of("command", "npm test && rm -rf ."), new CancellationToken()));
        assertTrue(e.getMessage().contains("shell metacharacters"), e.getMessage());
    }

    @Test
    void requiresACommand(@TempDir Path ws) {
        assertThrows(RunCommandException.class,
                () -> service.executeTool(ws.toString(), Map.of(), new CancellationToken()));
        assertThrows(RunCommandException.class,
                () -> service.executeTool(ws.toString(), Map.of("command", "  "), new CancellationToken()));
    }

    @Test
    void refusesAWorkspaceRelativeScriptThatEscapesTheFolder(@TempDir Path ws) {
        assertThrows(RuntimeException.class, () -> service.executeTool(
                ws.toString(), Map.of("command", "../evil.sh"), new CancellationToken()));
    }

    @Test
    void killsTheProcessWhenTheTokenIsAlreadyCancelled() {
        // Not a @TempDir cwd: a force-killed child can briefly keep it locked on Windows.
        String cwd = System.getProperty("java.io.tmpdir");
        CancellationToken token = new CancellationToken();
        token.cancel();
        RunCommandException e = assertThrows(RunCommandException.class, () -> service.executeTool(
                cwd, Map.of("command", JAVA, "args", List.of("-version")), token));
        assertTrue(e.getMessage().contains("cancelled"), e.getMessage());
    }

    @Test
    void splitsASingleStringCommandWithoutMetacharacters(@TempDir Path ws) {
        // A path with a Windows "\" separator would trip the metachar guard, so use a name only.
        assertThrows(RunCommandException.class, () -> service.executeTool(
                ws.toString(), Map.of("command", "npm run build && echo hi"), new CancellationToken()));
        // A clean two-word command splits and runs (bogus exe -> spawn failure, which is a RunCommandException).
        RunCommandException notFound = assertThrows(RunCommandException.class, () -> service.executeTool(
                ws.toString(), Map.of("command", "definitely-not-installed-xyz --help"), new CancellationToken()));
        assertTrue(notFound.getMessage().contains("installed and on PATH"), notFound.getMessage());
    }

    @Test
    void commandLineRendersCommandPlusArgs() {
        assertEquals("npm run build",
                RunCommandService.commandLine(Map.of("command", "npm", "args", List.of("run", "build"))));
        assertEquals("(empty command)", RunCommandService.commandLine(Map.of()));
        assertEquals("cargo build  (in api)",
                RunCommandService.commandLine(Map.of("command", "cargo", "args", List.of("build"), "cwd", "api")));
    }

    @Test
    void runsInASubdirectoryWhenCwdIsGiven(@TempDir Path ws) throws Exception {
        java.nio.file.Files.createDirectories(ws.resolve("sub"));
        // `java -version` works from any cwd; the point is that a valid subdir cwd is accepted.
        String out = service.executeTool(ws.toString(),
                Map.of("command", JAVA, "args", List.of("-version"), "cwd", "sub"), new CancellationToken());
        assertTrue(out.toLowerCase().contains("version"), out);
    }

    @Test
    void rejectsACwdThatIsNotADirectoryOrEscapesTheWorkspace(@TempDir Path ws) {
        RunCommandException missing = assertThrows(RunCommandException.class, () -> service.executeTool(
                ws.toString(), Map.of("command", JAVA, "args", List.of("-version"), "cwd", "does-not-exist"),
                new CancellationToken()));
        assertTrue(missing.getMessage().contains("not a directory"), missing.getMessage());

        assertThrows(RuntimeException.class, () -> service.executeTool(
                ws.toString(), Map.of("command", JAVA, "args", List.of("-version"), "cwd", "../.."),
                new CancellationToken()));
    }

    @Test
    void commandToolIsAdvertisedOnce() {
        assertEquals(1, RunCommandService.commandTools().size());
        assertEquals("run_command", RunCommandService.commandTools().get(0).name());
        assertFalse(RunCommandService.BLOCKED_EXECUTABLES.isEmpty());
    }
}
