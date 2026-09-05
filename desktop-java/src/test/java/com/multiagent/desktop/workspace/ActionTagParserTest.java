package com.multiagent.desktop.workspace;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionTagParserTest {

    @Test
    void parsesASelfClosingListDirTag() {
        List<ActionTagParser.ParsedAction> actions = ActionTagParser.parse("<list_dir path=\".\" />");
        assertEquals(1, actions.size());
        assertEquals("list_dir", actions.get(0).name());
        assertEquals(".", actions.get(0).args().get("path"));
    }

    @Test
    void parsesAWriteFileTagWithMultilineContent() {
        String content = "<write_file path=\"src/Foo.java\">line1\nline2</write_file>";
        List<ActionTagParser.ParsedAction> actions = ActionTagParser.parse(content);
        assertEquals(1, actions.size());
        assertEquals("write_file", actions.get(0).name());
        assertEquals("src/Foo.java", actions.get(0).args().get("path"));
        assertEquals("line1\nline2", actions.get(0).args().get("content"));
    }

    @Test
    void parsesAGenerateImageTagOnlyWhenPromptIsPresent() {
        List<ActionTagParser.ParsedAction> withPrompt = ActionTagParser.parse(
                "<generate_image prompt=\"a red circle\" path=\"images/out.png\" size=\"512x512\" />");
        assertEquals(1, withPrompt.size());
        assertEquals("generate_image", withPrompt.get(0).name());
        assertEquals("a red circle", withPrompt.get(0).args().get("prompt"));
        assertEquals("images/out.png", withPrompt.get(0).args().get("path"));
        assertEquals("512x512", withPrompt.get(0).args().get("size"));

        List<ActionTagParser.ParsedAction> withoutPrompt = ActionTagParser.parse(
                "<generate_image path=\"images/out.png\" />");
        assertTrue(withoutPrompt.isEmpty());
    }

    @Test
    void defaultsGenerateImagePathWhenOmitted() {
        List<ActionTagParser.ParsedAction> actions = ActionTagParser.parse(
                "<generate_image prompt=\"a cat\" />");
        assertEquals("images/generated.png", actions.get(0).args().get("path"));
    }

    @Test
    void parsesMultipleActionsFromTheSameContent() {
        String content = "<list_dir path=\".\" />\nSome text\n<read_file path=\"a.txt\" />";
        List<ActionTagParser.ParsedAction> actions = ActionTagParser.parse(content);
        assertEquals(2, actions.size());
        assertEquals("list_dir", actions.get(0).name());
        assertEquals("read_file", actions.get(1).name());
    }

    @Test
    void returnsEmptyListForPlainTextWithNoTags() {
        assertTrue(ActionTagParser.parse("just a normal reply, no tools needed").isEmpty());
    }

    @Test
    void returnsEmptyListForNullOrEmptyContent() {
        assertTrue(ActionTagParser.parse(null).isEmpty());
        assertTrue(ActionTagParser.parse("").isEmpty());
    }
}
