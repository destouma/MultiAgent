package com.multiagent.intellij.core.action;

/**
 * A mutating operation an agent loop wants to perform, described generically enough to
 * cover more than just file ops - "category" is a free-form tag ("file" today, "git" or
 * similar later) so new action types can plug into the same approval pipeline without
 * this type needing to change. summary is a one-line description shown as the prompt's
 * headline; detail is an optional longer preview (file content, a diff, a full command
 * line) shown in an expandable area.
 */
public record PendingAction(String category, String summary, String detail) {
    public PendingAction(String category, String summary) {
        this(category, summary, null);
    }
}
