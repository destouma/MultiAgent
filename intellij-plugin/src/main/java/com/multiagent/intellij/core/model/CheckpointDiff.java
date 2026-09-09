package com.multiagent.intellij.core.model;

/** Mirrors shared/types.ts CheckpointDiff. Either side may be null (file didn't exist there). */
public record CheckpointDiff(String path, String before, String after) {
}
