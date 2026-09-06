package com.multiagent.desktop.model;

/** Mirrors shared/types.ts FileCheckpoint. previousContent is null when the write/delete created the file (previousExisted == false). */
public record FileCheckpoint(String id, String conversationId, String relativePath, String previousContent,
                              boolean previousExisted, long createdAt) {
}
