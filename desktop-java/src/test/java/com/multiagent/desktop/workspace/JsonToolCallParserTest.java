package com.multiagent.desktop.workspace;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the exact real-world failure this parser exists for: Qwen2.5-Coder (and other
 * Hermes-style tool-tuned models) printing a {"name":..., "arguments":{...}} JSON tool call
 * as plain text instead of using the server's native tool_calls field or this app's XML tags.
 */
class JsonToolCallParserTest {

    @Test
    void parsesABareJsonToolCall() {
        String content = "Sure! Here's the command:\n\n"
                + "{\n  \"name\": \"write_file\",\n  \"arguments\": {\n    \"path\": \"src/HelloWorld.java\",\n"
                + "    \"content\": \"public class HelloWorld {\\n}\"\n  }\n}\n\nCopy and paste this.";
        List<ActionTagParser.ParsedAction> actions = JsonToolCallParser.parse(content);
        assertEquals(1, actions.size());
        assertEquals("write_file", actions.get(0).name());
        assertEquals("src/HelloWorld.java", actions.get(0).args().get("path"));
        assertEquals("public class HelloWorld {\n}", actions.get(0).args().get("content"));
    }

    @Test
    void parsesADescribeImageJsonToolCall() {
        String content = "{\"name\": \"describe_image\", \"arguments\": {\"path\": \"ui/mock.png\", "
                + "\"question\": \"list every button\"}}";
        List<ActionTagParser.ParsedAction> actions = JsonToolCallParser.parse(content);
        assertEquals(1, actions.size());
        assertEquals("describe_image", actions.get(0).name());
        assertEquals("ui/mock.png", actions.get(0).args().get("path"));
        assertEquals("list every button", actions.get(0).args().get("question"));
    }

    @Test
    void parsesAJsonToolCallInsideAMarkdownFence() {
        String content = "```json\n{\"name\": \"read_file\", \"arguments\": {\"path\": \"a.txt\"}}\n```";
        List<ActionTagParser.ParsedAction> actions = JsonToolCallParser.parse(content);
        assertEquals(1, actions.size());
        assertEquals("read_file", actions.get(0).name());
        assertEquals("a.txt", actions.get(0).args().get("path"));
    }

    @Test
    void parsesAJsonToolCallInsideHermesStyleToolCallTags() {
        String content = "<tool_call>\n{\"name\": \"delete_file\", \"arguments\": {\"path\": \"old.txt\"}}\n</tool_call>";
        List<ActionTagParser.ParsedAction> actions = JsonToolCallParser.parse(content);
        assertEquals(1, actions.size());
        assertEquals("delete_file", actions.get(0).name());
        assertEquals("old.txt", actions.get(0).args().get("path"));
    }

    @Test
    void handlesDoubleEncodedArgumentsThatAreThemselvesAJsonString() {
        String content = "{\"name\": \"rename_file\", \"arguments\": \"{\\\"path\\\": \\\"old.txt\\\", \\\"newPath\\\": \\\"new.txt\\\"}\"}";
        List<ActionTagParser.ParsedAction> actions = JsonToolCallParser.parse(content);
        assertEquals(1, actions.size());
        assertEquals("rename_file", actions.get(0).name());
        assertEquals("old.txt", actions.get(0).args().get("path"));
        assertEquals("new.txt", actions.get(0).args().get("newPath"));
    }

    @Test
    void ignoresJsonThatDoesNotNameAKnownTool() {
        String content = "{\"name\": \"some_unrelated_function\", \"arguments\": {\"path\": \"a.txt\"}}";
        assertTrue(JsonToolCallParser.parse(content).isEmpty());
    }

    @Test
    void ignoresPlainJsonWithNoNameField() {
        assertTrue(JsonToolCallParser.parse("{\"path\": \"a.txt\", \"content\": \"hello\"}").isEmpty());
    }

    @Test
    void returnsEmptyListForNullOrBlankContent() {
        assertTrue(JsonToolCallParser.parse(null).isEmpty());
        assertTrue(JsonToolCallParser.parse("   ").isEmpty());
    }

    @Test
    void returnsEmptyListForPlainProseWithNoJson() {
        assertTrue(JsonToolCallParser.parse("Sure, here is how you would do that in Java.").isEmpty());
    }
}
