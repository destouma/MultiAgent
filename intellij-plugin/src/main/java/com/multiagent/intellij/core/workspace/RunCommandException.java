package com.multiagent.intellij.core.workspace;

/** Thrown by {@link RunCommandService} for a blocked/invalid command or a spawn failure. */
public class RunCommandException extends RuntimeException {
    public RunCommandException(String message) {
        super(message);
    }
}
