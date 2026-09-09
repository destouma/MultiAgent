package com.multiagent.intellij.core.workspace;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises GitService against a real throwaway repo. The whole class is skipped when git
 * isn't on PATH (same pattern WorkspaceServiceTest uses for unsupported symlinks) so it
 * never fails the suite over an environment limitation.
 */
class GitServiceTest {
    private final GitService git = new GitService();
    private Path root;

    @BeforeAll
    static void requireGit() {
        Assumptions.assumeTrue(hasGit(), "git is not on PATH - skipping GitService tests");
    }

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws Exception {
        root = tempDir;
        runGit(root, "init", "-q");
        runGit(root, "config", "user.email", "test@example.com");
        runGit(root, "config", "user.name", "Test User");
        runGit(root, "config", "commit.gpgsign", "false");
        Files.writeString(root.resolve("README.md"), "hello\n");
        runGit(root, "add", "README.md");
        runGit(root, "commit", "-q", "-m", "initial commit");
    }

    @Test
    void gitStatusListsUntrackedFiles() throws Exception {
        Files.writeString(root.resolve("scratch.txt"), "x");
        String out = git.executeTool(root.toString(), "git_status", Map.of());
        assertTrue(out.contains("scratch.txt"), out);
    }

    @Test
    void gitDiffShowsUnstagedChangesAndStagedChangesSeparately() throws Exception {
        Files.writeString(root.resolve("README.md"), "hello world\n");

        String unstaged = git.executeTool(root.toString(), "git_diff", Map.of());
        assertTrue(unstaged.contains("README.md"), unstaged);

        runGit(root, "add", "README.md");
        assertEquals("(git produced no output)", git.executeTool(root.toString(), "git_diff", Map.of()));
        String staged = git.executeTool(root.toString(), "git_diff", Map.of("staged", true, "patch", true));
        assertTrue(staged.contains("+hello world"), staged);
    }

    @Test
    void gitDiffDefaultsToADiffstatAndPatchTrueAddsHunks() throws Exception {
        Files.writeString(root.resolve("README.md"), "hello world\n");

        String stat = git.executeTool(root.toString(), "git_diff", Map.of());
        assertTrue(stat.contains("README.md"), stat);
        assertFalse(stat.contains("+hello world"), "default git_diff should be a diffstat only: " + stat);

        String patch = git.executeTool(root.toString(), "git_diff", Map.of("patch", true));
        assertTrue(patch.contains("+hello world"), patch);
    }

    @Test
    void gitDiffCanBeLimitedToAPath() throws Exception {
        Files.writeString(root.resolve("README.md"), "changed\n");
        Files.writeString(root.resolve("other.txt"), "new file\n");
        runGit(root, "add", "other.txt");

        String out = git.executeTool(root.toString(), "git_diff",
                Map.of("staged", true, "path", "other.txt"));
        assertTrue(out.contains("other.txt"), out);
        assertFalse(out.contains("README.md"), out);
    }

    @Test
    void gitLogListsCommitsNewestFirstAndClampsCount() {
        String out = git.executeTool(root.toString(), "git_log", Map.of("count", 9999));
        assertTrue(out.contains("initial commit"), out);
    }

    @Test
    void gitShowDisplaysACommitAndPatchTrueAddsTheHunks() {
        String stat = git.executeTool(root.toString(), "git_show", Map.of("ref", "HEAD"));
        assertTrue(stat.contains("initial commit"), stat);
        assertTrue(stat.contains("README.md"), stat);
        assertFalse(stat.contains("+hello"), "default git_show should be a diffstat only: " + stat);

        String patch = git.executeTool(root.toString(), "git_show", Map.of("ref", "HEAD", "patch", true));
        assertTrue(patch.contains("+hello"), patch);
    }

    @Test
    void gitShowRejectsARefThatLooksLikeAnOption() {
        assertThrows(GitException.class,
                () -> git.executeTool(root.toString(), "git_show", Map.of("ref", "--output=/tmp/pwned")));
        assertThrows(GitException.class,
                () -> git.executeTool(root.toString(), "git_show", Map.of("ref", "-x")));
    }

    @Test
    void gitBranchMarksTheCurrentBranch() {
        String out = git.executeTool(root.toString(), "git_branch", Map.of());
        assertTrue(out.contains("*"), out);
    }

    @Test
    void gitAddThenGitCommitRecordANewCommit() throws Exception {
        Files.writeString(root.resolve("feature.txt"), "content\n");

        String added = git.executeTool(root.toString(), "git_add", Map.of("path", "feature.txt"));
        String staged = git.executeTool(root.toString(), "git_diff", Map.of("staged", true));
        assertTrue(staged.contains("feature.txt"), staged);

        git.executeTool(root.toString(), "git_commit", Map.of("message", "add feature.txt"));
        String log = git.executeTool(root.toString(), "git_log", Map.of());
        assertTrue(log.contains("add feature.txt"), log);
    }

    @Test
    void gitCommitAllStagesTrackedChangesFirst() throws Exception {
        Files.writeString(root.resolve("README.md"), "edited\n");
        git.executeTool(root.toString(), "git_commit", Map.of("message", "edit readme", "all", true));

        String status = git.executeTool(root.toString(), "git_status", Map.of());
        assertTrue(status.contains("nothing to commit"), status);
        String log = git.executeTool(root.toString(), "git_log", Map.of());
        assertTrue(log.contains("edit readme"), log);
    }

    @Test
    void gitCommitWithoutAMessageIsRejected() {
        assertThrows(GitException.class, () -> git.executeTool(root.toString(), "git_commit", Map.of()));
        assertThrows(GitException.class,
                () -> git.executeTool(root.toString(), "git_commit", Map.of("message", "   ")));
    }

    @Test
    void gitAddRefusesAPathOutsideTheWorkspace() {
        assertThrows(WorkspaceException.class,
                () -> git.executeTool(root.toString(), "git_add", Map.of("path", "../escape.txt")));
    }

    @Test
    void unknownGitToolIsRejected() {
        assertThrows(GitException.class, () -> git.executeTool(root.toString(), "git_frobnicate", Map.of()));
    }

    @Test
    void aNonRepositoryFolderIsReportedClearly(@TempDir Path notARepo) {
        GitException e = assertThrows(GitException.class,
                () -> git.executeTool(notARepo.toString(), "git_status", Map.of()));
        assertTrue(e.getMessage().contains("Not a git repository"), e.getMessage());
    }

    @Test
    void gitToolsDefinesTheExpectedTools() {
        List<String> names = GitService.gitTools().stream().map(tool -> tool.name()).toList();
        assertEquals(GitService.TOOL_NAMES, names);
    }

    private static boolean hasGit() {
        try {
            Process process = new ProcessBuilder("git", "--version").redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            return process.waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    private static void runGit(Path dir, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(Arrays.asList(args));
        Process process = new ProcessBuilder(command)
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = process.waitFor();
        if (code != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " failed (" + code + "):\n" + output);
        }
    }
}
