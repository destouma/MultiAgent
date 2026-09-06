package com.multiagent.desktop.model;

/** Mirrors shared/types.ts FolderEntry. */
public record FolderEntry(String path, long addedAt) {
    @Override
    public String toString() {
        return path;
    }
}
