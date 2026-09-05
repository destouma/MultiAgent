package com.multiagent.desktop.service;

import com.multiagent.desktop.model.ChatMessage;
import com.multiagent.desktop.model.Conversation;
import com.multiagent.desktop.model.ConversationKind;
import com.multiagent.desktop.model.MessageRole;
import com.multiagent.desktop.model.Persona;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExportFormatTest {
    private final Conversation conversation = new Conversation("c1", "My Chat", 0, 0, null,
            ConversationKind.CHAT, null, null);
    private final Persona general = new Persona("general", "General", "desc", "prompt", "#000");

    @Test
    void markdownStartsWithATitleHeadingAndLabelsEachMessageByRole() {
        List<ChatMessage> messages = List.of(
                new ChatMessage("m1", "c1", MessageRole.USER, "hello", null, 0),
                new ChatMessage("m2", "c1", MessageRole.ASSISTANT, "hi there", "general", 0));

        String markdown = ExportFormat.toMarkdown(conversation, messages, List.of(general));

        assertTrue(markdown.startsWith("# My Chat\n\n"));
        assertTrue(markdown.contains("**User:**\n\nhello"));
        assertTrue(markdown.contains("**General:**\n\nhi there"));
    }

    @Test
    void markdownFallsBackToAssistantWhenThePersonaIsUnknown() {
        List<ChatMessage> messages = List.of(
                new ChatMessage("m1", "c1", MessageRole.ASSISTANT, "reply", "ghost-persona", 0));

        String markdown = ExportFormat.toMarkdown(conversation, messages, List.of(general));

        assertTrue(markdown.contains("**Assistant:**\n\nreply"));
    }

    @Test
    void jsonRoundTripsConversationAndMessages() {
        List<ChatMessage> messages = List.of(new ChatMessage("m1", "c1", MessageRole.USER, "hello", null, 0));

        String json = ExportFormat.toJson(conversation, messages);

        assertTrue(json.contains("\"title\" : \"My Chat\""));
        assertTrue(json.contains("\"content\" : \"hello\""));
    }

    @Test
    void slugifyTitleLowercasesAndReplacesNonAlphanumericsWithHyphens() {
        assertEquals("fix-the-login-bug", ExportFormat.slugifyTitle("Fix the LOGIN bug!!"));
    }

    @Test
    void slugifyTitleTrimsLeadingAndTrailingHyphens() {
        assertEquals("abc", ExportFormat.slugifyTitle("---abc---"));
    }

    @Test
    void slugifyTitleFallsBackToConversationWhenEmpty() {
        assertEquals("conversation", ExportFormat.slugifyTitle("   "));
    }

    @Test
    void slugifyTitleCapsAt60Characters() {
        String longTitle = "a".repeat(100);
        assertEquals(60, ExportFormat.slugifyTitle(longTitle).length());
    }
}
