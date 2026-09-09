package com.multiagent.intellij.core.workspace;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceServiceTest {
    private final WorkspaceService workspace = new WorkspaceService();
    private Path root;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws IOException {
        root = tempDir;
        Files.writeString(root.resolve("readme.txt"), "hello");
        Files.createDirectories(root.resolve("sub"));
        Files.writeString(root.resolve("sub").resolve("nested.txt"), "nested content");
    }

    @Test
    void resolveSafeAllowsPathsInsideTheRoot() {
        Path resolved = workspace.resolveSafe(root.toString(), "sub/nested.txt");
        assertEquals(root.resolve("sub").resolve("nested.txt").normalize(), resolved);
    }

    @Test
    void resolveSafeRejectsDotDotTraversal() {
        assertThrows(WorkspaceException.class, () -> workspace.resolveSafe(root.toString(), "../outside.txt"));
    }

    @Test
    void resolveSafeRejectsDeeplyNestedDotDotTraversal() {
        assertThrows(WorkspaceException.class,
                () -> workspace.resolveSafe(root.toString(), "sub/../../outside.txt"));
    }

    @Test
    void resolveSafeRejectsASymlinkInsideTheWorkspacePointingOutside(@TempDir Path outsideDir) throws IOException {
        Path outsideFile = outsideDir.resolve("secret.txt");
        Files.writeString(outsideFile, "top secret");
        Path link = root.resolve("escape-link");
        try {
            Files.createSymbolicLink(link, outsideFile);
        } catch (IOException | UnsupportedOperationException e) {
            // Creating symlinks needs elevated privileges on some Windows configurations
            // (no Developer Mode, no SeCreateSymbolicLinkPrivilege) - skip rather than fail
            // the suite over an environment limitation unrelated to the code under test.
            Assumptions.assumeTrue(false, "Symlink creation not permitted in this environment: " + e.getMessage());
            return;
        }
        assertThrows(WorkspaceException.class, () -> workspace.resolveSafe(root.toString(), "escape-link"));
    }

    @Test
    void readFileReturnsContentForAnExistingFile() {
        assertEquals("hello", workspace.readFile(root.toString(), "readme.txt"));
    }

    @Test
    void readFileThrowsForAMissingFile() {
        assertThrows(WorkspaceException.class, () -> workspace.readFile(root.toString(), "missing.txt"));
    }

    @Test
    void readFileWithOffsetAndLimitReturnsOnlyThatLineRange() throws IOException {
        Files.writeString(root.resolve("many.txt"), "l1\nl2\nl3\nl4\nl5\nl6\nl7\nl8\nl9\nl10\n");

        String window = workspace.readFile(root.toString(), "many.txt", 3, 4);

        assertTrue(window.startsWith("(lines 3-6 of 10)\n"), window);
        assertTrue(window.contains("l3\nl4\nl5\nl6\n"), window);
        assertTrue(!window.contains("l2"), window);
        assertTrue(!window.contains("l7"), window);
    }

    @Test
    void readFileWithOffsetPastTheEndSaysSo() throws IOException {
        Files.writeString(root.resolve("short.txt"), "only\ntwo\n");
        String result = workspace.readFile(root.toString(), "short.txt", 50, 10);
        assertTrue(result.contains("past the end"), result);
    }

    @Test
    void readFileWithoutOffsetOrLimitStillReturnsTheWholeFile() {
        assertEquals("hello", workspace.readFile(root.toString(), "readme.txt", null, null));
    }

    @Test
    void executeToolPassesOffsetAndLimitThroughToReadFile() throws IOException {
        Files.writeString(root.resolve("many.txt"), "a\nb\nc\nd\ne\n");
        String window = workspace.executeTool(root.toString(), "read_file",
                Map.of("path", "many.txt", "offset", 2, "limit", 2));
        assertTrue(window.startsWith("(lines 2-3 of 5)\n"), window);
        assertTrue(window.contains("b\nc\n"), window);
    }

    @Test
    void tryReadFileReturnsNullInsteadOfThrowingForAMissingFile() {
        assertEquals(null, workspace.tryReadFile(root.toString(), "missing.txt"));
        assertEquals("hello", workspace.tryReadFile(root.toString(), "readme.txt"));
    }

    @Test
    void writeFileCreatesParentDirectoriesAsNeeded() {
        String result = workspace.writeFile(root.toString(), "a/b/c.txt", "content");
        assertTrue(result.startsWith("Wrote a/b/c.txt"));
        assertEquals("content", workspace.readFile(root.toString(), "a/b/c.txt"));
    }

    @Test
    void deleteFileRefusesToDeleteADirectory() {
        assertThrows(WorkspaceException.class, () -> workspace.deleteFile(root.toString(), "sub"));
    }

    @Test
    void deleteFileRemovesAnExistingFile() {
        workspace.deleteFile(root.toString(), "readme.txt");
        assertThrows(WorkspaceException.class, () -> workspace.readFile(root.toString(), "readme.txt"));
    }

    @Test
    void buildTreeSkipsDotfilesAndIgnoredDirectories() throws IOException {
        Files.writeString(root.resolve(".hidden"), "secret");
        Files.createDirectories(root.resolve("node_modules"));
        Files.writeString(root.resolve("node_modules").resolve("pkg.json"), "{}");
        Files.createDirectories(root.resolve("target").resolve("classes"));
        Files.writeString(root.resolve("target").resolve("classes").resolve("App.class"), "x");

        String tree = workspace.buildTree(root.toString());

        assertTrue(tree.contains("readme.txt"));
        assertTrue(tree.contains("sub/"));
        assertTrue(!tree.contains(".hidden"));
        assertTrue(!tree.contains("node_modules"));
        assertTrue(!tree.contains("target"), "build-output dirs should be pruned from the tree");
    }

    @Test
    void buildTreeStopsAtTheDepthCapAndMarksFoldersWithMoreInside() throws IOException {
        // root/deep/one/two/three.txt - deeper than MAX_TREE_DEPTH (2).
        Files.createDirectories(root.resolve("deep").resolve("one").resolve("two"));
        Files.writeString(root.resolve("deep").resolve("one").resolve("two").resolve("three.txt"), "x");

        String tree = workspace.buildTree(root.toString());

        assertTrue(tree.contains("deep/"), tree);
        assertTrue(tree.contains("deep/one/"), tree);
        assertTrue(tree.contains("deep/one/ …"), "a folder with hidden contents should be marked with …\n" + tree);
        assertTrue(!tree.contains("two"), "contents below the depth cap must not be listed\n" + tree);
        assertTrue(!tree.contains("three.txt"), tree);
    }

    @Test
    void executeToolDispatchesToTheMatchingOperation() {
        String result = workspace.executeTool(root.toString(), "read_file", Map.of("path", "readme.txt"));
        assertEquals("hello", result);
    }

    @Test
    void executeToolRejectsGenerateImageSinceItBelongsToImageService() {
        assertThrows(WorkspaceException.class,
                () -> workspace.executeTool(root.toString(), "generate_image", Map.of("prompt", "a cat")));
    }

    @Test
    void executeToolRejectsAnUnknownToolName() {
        assertThrows(WorkspaceException.class,
                () -> workspace.executeTool(root.toString(), "does_not_exist", Map.of()));
    }

    @Test
    void workspaceToolsDefinesTheExpectedTools() {
        List<String> names = WorkspaceService.workspaceTools().stream()
                .map(tool -> tool.name())
                .toList();
        assertEquals(List.of("list_dir", "read_file", "search_file", "write_file", "delete_file",
                "rename_file", "generate_image"), names);
    }

    @Test
    void searchFileReturnsMatchingLinesWithNumbersAndHonoursIgnoreCase() throws IOException {
        Files.writeString(root.resolve("log.txt"),
                "line one\nan ERROR here\nline three\nanother error line\nline five\n");

        String hits = workspace.searchFile(root.toString(), "log.txt", "error", false, false, 0, 40);
        assertTrue(hits.contains(">     4: another error line"), hits);
        assertTrue(!hits.contains("ERROR here")); // case-sensitive: "error" != "ERROR"

        String both = workspace.searchFile(root.toString(), "log.txt", "error", false, true, 0, 40);
        assertTrue(both.contains(">     2: an ERROR here"), both);
        assertTrue(both.contains(">     4: another error line"), both);
    }

    @Test
    void searchFileRegexContextGapSeparatorAndMaxMatches() throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 30; i++) {
            sb.append(i % 10 == 0 ? "match " + i + "\n" : "filler " + i + "\n"); // matches at 10, 20, 30
        }
        Files.writeString(root.resolve("big.txt"), sb.toString());

        String hits = workspace.searchFile(root.toString(), "big.txt", "^match \\d+$", true, false, 1, 2);
        assertTrue(hits.contains(">    10: match 10"), hits);
        assertTrue(hits.contains("      9: filler 9"), hits);  // context before
        assertTrue(hits.contains("     11: filler 11"), hits); // context after
        assertTrue(hits.contains("--"), hits);                 // gap between the 10-block and 20-block
        assertTrue(hits.contains("max_matches=2"), hits);      // stopped before match 30
    }

    @Test
    void searchFileRejectsEscapesMissingFilesAndBadRegex() {
        assertThrows(WorkspaceException.class,
                () -> workspace.searchFile(root.toString(), "../x.txt", "a", false, false, 0, 40));
        assertThrows(WorkspaceException.class,
                () -> workspace.searchFile(root.toString(), "nope.txt", "a", false, false, 0, 40));
        assertThrows(WorkspaceException.class,
                () -> workspace.searchFile(root.toString(), "readme.txt", "[", true, false, 0, 40));
        assertThrows(WorkspaceException.class,
                () -> workspace.searchFile(root.toString(), "readme.txt", "", false, false, 0, 40));
    }

    @Test
    void searchFileReportsNoMatchesCleanly() {
        String r = workspace.searchFile(root.toString(), "readme.txt", "zzz-not-here", false, false, 0, 40);
        assertTrue(r.startsWith("No matches"), r);
    }

    @Test
    void visionToolIsSeparateAndRequiresPathAndQuestion() {
        var tool = WorkspaceService.visionTool();
        assertEquals("describe_image", tool.name());
        var required = tool.parametersSchema().path("required");
        assertEquals(2, required.size());
        assertTrue(required.toString().contains("path"));
        assertTrue(required.toString().contains("question"));
    }

    @Test
    void guessImageMimeMapsKnownExtensionsAndRejectsOthers() {
        assertEquals("image/png", WorkspaceService.guessImageMime("a/b.PNG"));
        assertEquals("image/jpeg", WorkspaceService.guessImageMime("shot.jpg"));
        assertEquals("image/jpeg", WorkspaceService.guessImageMime("shot.jpeg"));
        assertEquals("image/gif", WorkspaceService.guessImageMime("x.gif"));
        assertEquals("image/webp", WorkspaceService.guessImageMime("x.webp"));
        assertThrows(WorkspaceException.class, () -> WorkspaceService.guessImageMime("notes.txt"));
    }

    @Test
    void readImageBytesReadsAWorkspaceImageAndRejectsEscapesAndNonImages() throws IOException {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0, 1, 2, 3};
        Files.write(root.resolve("shot.png"), png);
        assertEquals(png.length, workspace.readImageBytes(root.toString(), "shot.png").length);

        assertThrows(WorkspaceException.class,
                () -> workspace.readImageBytes(root.toString(), "../shot.png"));
        assertThrows(WorkspaceException.class,
                () -> workspace.readImageBytes(root.toString(), "readme.txt"));
        assertThrows(WorkspaceException.class,
                () -> workspace.readImageBytes(root.toString(), "missing.png"));
    }

    @Test
    void renameFileMovesTheFileToTheNewPath() {
        String result = workspace.renameFile(root.toString(), "readme.txt", "renamed.txt");
        assertTrue(result.contains("readme.txt"));
        assertTrue(result.contains("renamed.txt"));
        assertEquals("hello", workspace.readFile(root.toString(), "renamed.txt"));
        assertThrows(WorkspaceException.class, () -> workspace.readFile(root.toString(), "readme.txt"));
    }

    @Test
    void renameFileCreatesDestinationParentDirectories() {
        workspace.renameFile(root.toString(), "readme.txt", "docs/renamed.txt");
        assertEquals("hello", workspace.readFile(root.toString(), "docs/renamed.txt"));
    }

    @Test
    void renameFileRefusesToOverwriteAnExistingDestination() {
        assertThrows(WorkspaceException.class,
                () -> workspace.renameFile(root.toString(), "readme.txt", "sub/nested.txt"));
    }

    @Test
    void renameFileThrowsForAMissingSource() {
        assertThrows(WorkspaceException.class,
                () -> workspace.renameFile(root.toString(), "missing.txt", "new.txt"));
    }

    @Test
    void renameFileRejectsEscapingTheWorkspaceEitherSide() {
        assertThrows(WorkspaceException.class,
                () -> workspace.renameFile(root.toString(), "../outside.txt", "new.txt"));
        assertThrows(WorkspaceException.class,
                () -> workspace.renameFile(root.toString(), "readme.txt", "../outside.txt"));
    }
}
