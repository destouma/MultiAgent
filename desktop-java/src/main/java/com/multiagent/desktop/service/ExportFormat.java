package com.multiagent.desktop.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiagent.desktop.model.ChatMessage;
import com.multiagent.desktop.model.Conversation;
import com.multiagent.desktop.model.MessageRole;
import com.multiagent.desktop.model.Persona;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Formats a conversation for export, mirroring desktop/electron/services/exportFormat.ts.
 * Image messages ([[MA_IMAGE]] payloads) aren't special-cased here the way the TS version
 * does, since image generation (Phase 3) hasn't been ported yet - there's nothing yet that
 * could produce one in the Java app's own conversations.
 */
public final class ExportFormat {
    private ExportFormat() {
    }

    public static String toMarkdown(Conversation conversation, List<ChatMessage> messages, List<Persona> personas) {
        Map<String, Persona> personaById = new LinkedHashMap<>();
        for (Persona persona : personas) {
            personaById.put(persona.getId(), persona);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(conversation.getTitle()).append("\n\n");
        for (ChatMessage message : messages) {
            sb.append("**").append(roleLabel(message, personaById)).append(":**\n\n");
            sb.append(message.getContent()).append("\n\n");
        }
        return sb.toString();
    }

    private static String roleLabel(ChatMessage message, Map<String, Persona> personaById) {
        if (message.getRole() == MessageRole.USER) {
            return "User";
        }
        Persona persona = message.getPersonaId() != null ? personaById.get(message.getPersonaId()) : null;
        return persona != null ? persona.getName() : "Assistant";
    }

    public static String toJson(Conversation conversation, List<ChatMessage> messages) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("conversation", conversation);
            payload.put("messages", messages);
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize conversation to JSON", e);
        }
    }

    public static String slugifyTitle(String title) {
        String slug = title == null ? "" : title.trim().toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (slug.length() > 60) {
            slug = slug.substring(0, 60);
        }
        return slug.isEmpty() ? "conversation" : slug;
    }
}
