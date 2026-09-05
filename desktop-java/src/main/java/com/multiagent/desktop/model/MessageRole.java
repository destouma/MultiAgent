package com.multiagent.desktop.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum MessageRole {
    USER("user"),
    ASSISTANT("assistant"),
    SYSTEM("system");

    private final String wire;

    MessageRole(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }

    @JsonCreator
    public static MessageRole fromWire(String wire) {
        for (MessageRole role : values()) {
            if (role.wire.equals(wire)) {
                return role;
            }
        }
        throw new IllegalArgumentException("Unknown message role: " + wire);
    }
}
