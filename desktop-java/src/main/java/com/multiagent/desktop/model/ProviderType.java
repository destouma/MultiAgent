package com.multiagent.desktop.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum ProviderType {
    OPENAI("openai"),
    LEMONADE("lemonade"),
    OLLAMA("ollama");

    private final String wire;

    ProviderType(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }

    @JsonCreator
    public static ProviderType fromWire(String wire) {
        for (ProviderType type : values()) {
            if (type.wire.equals(wire)) {
                return type;
            }
        }
        return LEMONADE;
    }
}
