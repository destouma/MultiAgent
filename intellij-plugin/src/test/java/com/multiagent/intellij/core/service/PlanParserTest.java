package com.multiagent.intellij.core.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanParserTest {
    private static final List<String> ALLOWED = List.of("researcher", "coder", "critic");

    @Test
    void parsesAWellFormedPlan() {
        PlanParser.PlanResult result = PlanParser.parsePlan(
                "{\"specialists\":[\"researcher\",\"coder\"],\"rationale\":\"needs research and code\"}", ALLOWED);
        assertEquals(List.of("researcher", "coder"), result.specialists());
        assertEquals("needs research and code", result.rationale());
    }

    @Test
    void extractsJsonEvenWhenSurroundedByProseOrMarkdown() {
        PlanParser.PlanResult result = PlanParser.parsePlan(
                "Sure, here's my plan:\n```json\n{\"specialists\":[\"critic\"],\"rationale\":\"needs review\"}\n```",
                ALLOWED);
        assertEquals(List.of("critic"), result.specialists());
    }

    @Test
    void filtersOutIdsNotInTheAllowedList() {
        PlanParser.PlanResult result = PlanParser.parsePlan(
                "{\"specialists\":[\"researcher\",\"not-a-real-specialist\"],\"rationale\":\"r\"}", ALLOWED);
        assertEquals(List.of("researcher"), result.specialists());
    }

    @Test
    void dedupesRepeatedIdsAndCapsAtThree() {
        PlanParser.PlanResult result = PlanParser.parsePlan(
                "{\"specialists\":[\"researcher\",\"researcher\",\"coder\",\"critic\"],\"rationale\":\"r\"}", ALLOWED);
        assertEquals(3, result.specialists().size());
        assertTrue(result.specialists().containsAll(List.of("researcher", "coder", "critic")));
    }

    @Test
    void fallsBackToADefaultPlanWhenThereIsNoJsonAtAll() {
        PlanParser.PlanResult result = PlanParser.parsePlan("I'll just answer directly, no plan needed.", ALLOWED);
        assertEquals(List.of("researcher"), result.specialists());
        assertEquals("Default plan", result.rationale());
    }

    @Test
    void fallsBackToADefaultPlanForMalformedJson() {
        PlanParser.PlanResult result = PlanParser.parsePlan("{\"specialists\": [oops}", ALLOWED);
        assertEquals(List.of("researcher"), result.specialists());
    }

    @Test
    void usesADefaultRationaleWhenTheModelOmitsOne() {
        PlanParser.PlanResult result = PlanParser.parsePlan("{\"specialists\":[\"coder\"]}", ALLOWED);
        assertEquals("Plan selected by orchestrator", result.rationale());
    }

    @Test
    void noJsonFallbackUsesTheFirstAllowedIdNotAHardcodedOne() {
        PlanParser.PlanResult result = PlanParser.parsePlan("no plan here", List.of("security", "tester"));
        assertEquals(List.of("security"), result.specialists());
    }

    @Test
    void noJsonWithNoAllowedIdsYieldsAnEmptyPlan() {
        PlanParser.PlanResult result = PlanParser.parsePlan("no plan here", List.of());
        assertTrue(result.specialists().isEmpty());
    }

    @Test
    void handlesAnEmptySpecialistsArray() {
        PlanParser.PlanResult result = PlanParser.parsePlan("{\"specialists\":[],\"rationale\":\"trivial request\"}",
                ALLOWED);
        assertTrue(result.specialists().isEmpty());
    }
}
