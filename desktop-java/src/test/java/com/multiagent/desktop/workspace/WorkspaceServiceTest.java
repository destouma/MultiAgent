package com.multiagent.desktop.workspace;

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

        String tree = workspace.buildTree(root.toString());

        assertTrue(tree.contains("readme.txt"));
        assertTrue(tree.contains("sub/"));
        assertTrue(!tree.contains(".hidden"));
        assertTrue(!tree.contains("node_modules"));
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
    void workspaceToolsDefinesTheFiveExpectedTools() {
        List<String> names = WorkspaceService.workspaceTools().stream()
                .map(tool -> tool.name())
                .toList();
        assertEquals(List.of("list_dir", "read_file", "write_file", "delete_file", "generate_image"), names);
    }
}
