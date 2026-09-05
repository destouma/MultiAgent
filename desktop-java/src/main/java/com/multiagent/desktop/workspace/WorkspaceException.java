package com.multiagent.desktop.workspace;

/** Mirrors shared/workspace/workspaceService.ts's WorkspaceError. */
public class WorkspaceException extends RuntimeException {
    public WorkspaceException(String message) {
        super(message);
    }
}
