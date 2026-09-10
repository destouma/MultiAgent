package com.multiagent.intellij.core.service;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpecialistModelsTest {

    @Test
    void roundTrips() {
        Map<String, String> in = new LinkedHashMap<>();
        in.put("coder", "Qwen2.5-Coder-7B");
        in.put("critic", "some-strong-model");
        assertEquals(in, SpecialistModels.parse(SpecialistModels.write(in)));
    }

    @Test
    void emptyMapWritesToBlankAndBlankParsesToEmpty() {
        assertEquals("", SpecialistModels.write(Map.of()));
        assertEquals("", SpecialistModels.write(null));
        assertTrue(SpecialistModels.parse("").isEmpty());
        assertTrue(SpecialistModels.parse(null).isEmpty());
    }

    @Test
    void badJsonAndBlankEntriesAreDropped() {
        assertTrue(SpecialistModels.parse("not json {").isEmpty());
        assertEquals(Map.of("coder", "m"),
                SpecialistModels.parse("{\"coder\":\"m\",\"critic\":\"\",\"\":\"x\"}"));
    }
}
