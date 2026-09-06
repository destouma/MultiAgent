package com.multiagent.desktop.service;

import com.multiagent.desktop.llm.ChatRequestMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextTrimmerTest {

    private static String big(String tag) {
        return tag + " " + "x".repeat(ContextTrimmer.ELIDE_OVER + 50);
    }

    @Test
    void doesNothingWhenThereAreOnlyAFewToolResults() {
        List<ChatRequestMessage> messages = new ArrayList<>(List.of(
                ChatRequestMessage.system("sys"),
                ChatRequestMessage.user("do the thing"),
                ChatRequestMessage.tool("c1", big("first")),
                ChatRequestMessage.tool("c2", big("second"))));
        List<ChatRequestMessage> before = List.copyOf(messages);

        ContextTrimmer.elideOldToolOutput(messages);

        assertEquals(before, messages);
    }

    @Test
    void elidesEveryToolResultExceptTheMostRecentTwo() {
        List<ChatRequestMessage> messages = new ArrayList<>(List.of(
                ChatRequestMessage.system("sys"),
                ChatRequestMessage.user("go"),
                ChatRequestMessage.tool("c1", big("oldest")),
                ChatRequestMessage.tool("c2", big("middle")),
                ChatRequestMessage.tool("c3", big("recent")),
                ChatRequestMessage.tool("c4", big("newest"))));

        ContextTrimmer.elideOldToolOutput(messages);

        assertTrue(messages.get(2).content().startsWith("oldest "), messages.get(2).content());
        assertTrue(messages.get(2).content().contains("elided to save context"), messages.get(2).content());
        assertTrue(messages.get(3).content().contains("elided to save context"));
        // The two most recent tool results are untouched.
        assertTrue(messages.get(4).content().equals(big("recent")));
        assertTrue(messages.get(5).content().equals(big("newest")));
        // Ids are kept so the assistant tool-call <-> tool-reply pairing survives.
        assertEquals("c1", messages.get(2).toolCallId());
    }

    @Test
    void leavesShortOldToolResultsAlone() {
        List<ChatRequestMessage> messages = new ArrayList<>(List.of(
                ChatRequestMessage.tool("c1", "small result"),
                ChatRequestMessage.tool("c2", big("two")),
                ChatRequestMessage.tool("c3", big("three")),
                ChatRequestMessage.tool("c4", big("four"))));

        ContextTrimmer.elideOldToolOutput(messages);

        assertEquals("small result", messages.get(0).content());
    }

    @Test
    void neverTouchesSystemUserOrAssistantMessages() {
        ChatRequestMessage sys = ChatRequestMessage.system(big("system"));
        ChatRequestMessage usr = ChatRequestMessage.user(big("user"));
        ChatRequestMessage asst = ChatRequestMessage.assistant(big("assistant"));
        List<ChatRequestMessage> messages = new ArrayList<>(List.of(
                sys, usr, asst,
                ChatRequestMessage.tool("c1", big("t1")),
                ChatRequestMessage.tool("c2", big("t2")),
                ChatRequestMessage.tool("c3", big("t3"))));

        ContextTrimmer.elideOldToolOutput(messages);

        assertSame(sys, messages.get(0));
        assertSame(usr, messages.get(1));
        assertSame(asst, messages.get(2));
        assertTrue(messages.get(3).content().contains("elided to save context"));
    }

    @Test
    void treatsTheFallbackLoopsToolResultsUserMessageAsToolOutput() {
        List<ChatRequestMessage> messages = new ArrayList<>(List.of(
                ChatRequestMessage.user("Tool results:\n" + big("dump one")),
                ChatRequestMessage.user("Tool results:\n" + big("dump two")),
                ChatRequestMessage.user("Tool results:\n" + big("dump three"))));

        ContextTrimmer.elideOldToolOutput(messages);

        assertTrue(messages.get(0).content().contains("elided to save context"), messages.get(0).content());
        assertTrue(messages.get(1).content().equals("Tool results:\n" + big("dump two")));
        assertTrue(messages.get(2).content().equals("Tool results:\n" + big("dump three")));
    }
}
