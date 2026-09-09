package com.multiagent.intellij.core.service;

import com.multiagent.intellij.core.model.CheckpointDiff;
import com.multiagent.intellij.core.model.Conversation;
import com.multiagent.intellij.core.model.FileCheckpoint;
import com.multiagent.intellij.core.persistence.ConversationStore;
import com.multiagent.intellij.core.workspace.WorkspaceService;

/**
 * Diff/revert for a single write_file/delete_file checkpoint, mirroring the
 * checkpoints:diff / checkpoints:revert IPC handlers in
 * desktop/electron/ipc/handlers.ts. One-shot revert only - there's no redo stack, same as
 * the Electron app.
 */
public class CheckpointService {
    private final ConversationStore store;
    private final WorkspaceService workspace = new WorkspaceService();

    public CheckpointService(ConversationStore store) {
        this.store = store;
    }

    public CheckpointDiff diff(String checkpointId) {
        FileCheckpoint checkpoint = requireCheckpoint(checkpointId);
        Conversation conversation = requireBoundConversation(checkpoint);
        String before = checkpoint.previousExisted() ? checkpoint.previousContent() : null;
        String after = workspace.tryReadFile(conversation.getWorkspacePath(), checkpoint.relativePath());
        return new CheckpointDiff(checkpoint.relativePath(), before, after);
    }

    /** Restores the file to its pre-op content, or deletes it if the op created it from scratch (and it's still there). */
    public void revert(String checkpointId) {
        FileCheckpoint checkpoint = requireCheckpoint(checkpointId);
        Conversation conversation = requireBoundConversation(checkpoint);

        if (checkpoint.previousExisted()) {
            String content = checkpoint.previousContent() == null ? "" : checkpoint.previousContent();
            workspace.writeFile(conversation.getWorkspacePath(), checkpoint.relativePath(), content);
        } else if (workspace.tryReadFile(conversation.getWorkspacePath(), checkpoint.relativePath()) != null) {
            workspace.deleteFile(conversation.getWorkspacePath(), checkpoint.relativePath());
        }
    }

    private FileCheckpoint requireCheckpoint(String checkpointId) {
        FileCheckpoint checkpoint = store.getCheckpoint(checkpointId);
        if (checkpoint == null) {
            throw new IllegalArgumentException("Checkpoint not found");
        }
        return checkpoint;
    }

    private Conversation requireBoundConversation(FileCheckpoint checkpoint) {
        Conversation conversation = store.getConversation(checkpoint.conversationId());
        if (conversation == null || conversation.getWorkspacePath() == null || conversation.getWorkspacePath().isBlank()) {
            throw new IllegalStateException("This chat no longer has a workspace folder bound to it");
        }
        return conversation;
    }
}
