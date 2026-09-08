package com.multiagent.desktop.model;

/** Mirrors shared/types.ts FolderEntry, plus one Java-only addition: projectId (nullable - see ProjectEntry). */
public record FolderEntry(String path, long addedAt, String projectId) {
    @Override
    public String toString() {
        return path;
    }
}
