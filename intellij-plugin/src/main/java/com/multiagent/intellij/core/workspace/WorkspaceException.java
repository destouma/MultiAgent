package com.multiagent.intellij.core.workspace;

/** Mirrors shared/workspace/workspaceService.ts's WorkspaceError. */
public class WorkspaceException extends RuntimeException {
    public WorkspaceException(String message) {
        super(message);
    }
}
