package com.multiagent.intellij.core.workspace;

/** Thrown by {@link GitService} for anything git-related that fails - a missing repo, a bad ref, a non-zero git exit. Caught by the tool loop and returned to the model as an "Error: ..." tool result, exactly like {@link WorkspaceException}. */
public class GitException extends RuntimeException {
    public GitException(String message) {
        super(message);
    }
}
