package com.multiagent.intellij.core.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Phase 1 only creates/handles CHAT; IMAGE and ORCHESTRATOR are recognized for schema forward-compat (Phases 3-4). */
public enum ConversationKind {
    CHAT("chat"),
    IMAGE("image"),
    ORCHESTRATOR("orchestrator");

    private final String wire;

    ConversationKind(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }

    @JsonCreator
    public static ConversationKind fromWire(String wire) {
        if ("image".equals(wire)) return IMAGE;
        if ("orchestrator".equals(wire)) return ORCHESTRATOR;
        return CHAT;
    }
}
