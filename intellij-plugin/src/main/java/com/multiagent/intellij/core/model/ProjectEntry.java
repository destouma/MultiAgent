package com.multiagent.intellij.core.model;

/**
 * A pure UI grouping over folders - e.g. "Acme Corp" containing the frontend/backend/mobile
 * repo folders. No shared behavior or shared chat memory between a project's folders; it's
 * one more level in the sidebar tree, nothing more. A folder belongs to at most one project
 * (see FolderEntry.projectId) or none.
 *
 * <p>Unlike FolderEntry (whose path IS its natural key), a project needs a generated id
 * separate from its name, since the name is renameable and folders must keep pointing at
 * the same project across a rename.
 *
 * <p>No TS counterpart - the Electron app has no project concept; this is a Java-only addition.
 */
public record ProjectEntry(String id, String name, long createdAt) {
    /** So a plain ChoiceDialog<ProjectEntry> (or any other place needing a label) shows the name, not the record's default toString. */
    @Override
    public String toString() {
        return name;
    }
}
