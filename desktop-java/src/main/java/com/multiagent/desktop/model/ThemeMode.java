package com.multiagent.desktop.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum ThemeMode {
    LIGHT("light"),
    DARK("dark"),
    TERMINAL("terminal");

    private final String wire;

    ThemeMode(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }

    @JsonCreator
    public static ThemeMode fromWire(String wire) {
        for (ThemeMode mode : values()) {
            if (mode.wire.equals(wire)) {
                return mode;
            }
        }
        return LIGHT;
    }
}
