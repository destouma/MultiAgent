package com.multiagent.desktop.ui.viewmodel;

/**
 * One tool-activity line for the UI, mirroring shared/types.ts WorkspaceOpEvent (minus the
 * IPC envelope). checkpointId is non-null only for a successful write_file/delete_file -
 * that's what lets ChatThread offer View diff/Revert for that specific line.
 */
public record WorkspaceOpEntry(String op, String path, String status, String detail, String checkpointId) {
}
