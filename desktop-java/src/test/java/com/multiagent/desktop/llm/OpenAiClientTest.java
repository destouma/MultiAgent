package com.multiagent.desktop.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.multiagent.desktop.model.ImageAttachment;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiClientTest {

    private final OpenAiClient client = new OpenAiClient(new ProviderSettings("http://localhost:1/v1", "k"));

    @Test
    void plainMessageSerializesContentAsAString() {
        JsonNode msg = client.toMessagesNode(List.of(ChatRequestMessage.user("hello"))).get(0);
        assertTrue(msg.get("content").isTextual());
        assertEquals("hello", msg.get("content").asText());
    }

    @Test
    void imageMessageSerializesContentAsAPartsArrayWithADataUrl() {
        ChatRequestMessage m = ChatRequestMessage.userWithImage("what is this?",
                new ImageAttachment("shot.png", "image/png", new byte[]{1, 2, 3, 4}));
        JsonNode content = client.toMessagesNode(List.of(m)).get(0).get("content");

        assertTrue(content.isArray());
        assertEquals(2, content.size());
        assertEquals("text", content.get(0).get("type").asText());
        assertEquals("what is this?", content.get(0).get("text").asText());
        assertEquals("image_url", content.get(1).get("type").asText());
        assertTrue(content.get(1).get("image_url").get("url").asText()
                .startsWith("data:image/png;base64,"));
    }
}
