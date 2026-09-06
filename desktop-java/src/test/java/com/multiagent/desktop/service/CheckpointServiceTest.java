package com.multiagent.desktop.service;

import com.multiagent.desktop.model.CheckpointDiff;
import com.multiagent.desktop.model.Conversation;
import com.multiagent.desktop.model.ConversationKind;
import com.multiagent.desktop.model.FileCheckpoint;
import com.multiagent.desktop.persistence.ConversationStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CheckpointServiceTest {
    private ConversationStore store;
    private CheckpointService checkpoints;
    private Conversation conversation;
    private Path workspace;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws IOException {
        workspace = Files.createDirectories(tempDir.resolve("workspace"));
        store = new ConversationStore(tempDir.resolve("chats.db"));
        checkpoints = new CheckpointService(store);
        conversation = store.createConversation("chat", ConversationKind.CHAT, workspace.toString());
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    @Test
    void diffShowsBeforeAndAfterForAModifiedFile() throws IOException {
        FileCheckpoint checkpoint = store.addCheckpoint(conversation.getId(), "notes.txt", "old content", true);
        Files.writeString(workspace.resolve("notes.txt"), "new content");

        CheckpointDiff diff = checkpoints.diff(checkpoint.id());

        assertEquals("notes.txt", diff.path());
        assertEquals("old content", diff.before());
        assertEquals("new content", diff.after());
    }

    @Test
    void diffShowsNullBeforeWhenTheOpCreatedTheFile() throws IOException {
        FileCheckpoint checkpoint = store.addCheckpoint(conversation.getId(), "new.txt", null, false);
        Files.writeString(workspace.resolve("new.txt"), "brand new file");

        CheckpointDiff diff = checkpoints.diff(checkpoint.id());

        assertNull(diff.before());
        assertEquals("brand new file", diff.after());
    }

    @Test
    void revertRestoresThePreviousContentForAModifiedFile() throws IOException {
        FileCheckpoint checkpoint = store.addCheckpoint(conversation.getId(), "notes.txt", "old content", true);
        Files.writeString(workspace.resolve("notes.txt"), "new content");

        checkpoints.revert(checkpoint.id());

        assertEquals("old content", Files.readString(workspace.resolve("notes.txt")));
    }

    @Test
    void revertDeletesTheFileWhenTheOriginalOpCreatedIt() throws IOException {
        FileCheckpoint checkpoint = store.addCheckpoint(conversation.getId(), "new.txt", null, false);
        Files.writeString(workspace.resolve("new.txt"), "brand new file");

        checkpoints.revert(checkpoint.id());

        assertFalse(Files.exists(workspace.resolve("new.txt")));
    }

    @Test
    void revertOnAnAlreadyDeletedCreatedFileIsANoOp() {
        FileCheckpoint checkpoint = store.addCheckpoint(conversation.getId(), "gone.txt", null, false);
        // File was never actually written in this test, or was already removed by a later op.
        checkpoints.revert(checkpoint.id());
        assertFalse(Files.exists(workspace.resolve("gone.txt")));
    }

    @Test
    void diffThrowsForAnUnknownCheckpointId() {
        assertThrows(IllegalArgumentException.class, () -> checkpoints.diff("does-not-exist"));
    }

    @Test
    void diffThrowsWhenTheConversationNoLongerHasAWorkspaceBound() {
        Conversation noWorkspace = store.createConversation("no workspace chat", ConversationKind.CHAT, null);
        FileCheckpoint checkpoint = store.addCheckpoint(noWorkspace.getId(), "notes.txt", "old", true);
        assertThrows(IllegalStateException.class, () -> checkpoints.diff(checkpoint.id()));
    }
}
