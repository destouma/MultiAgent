package com.multiagent.desktop.model;

/** Mirrors shared/types.ts ModelInfo. */
public record ModelInfo(String id, String ownedBy) {
    @Override
    public String toString() {
        return id;
    }
}
