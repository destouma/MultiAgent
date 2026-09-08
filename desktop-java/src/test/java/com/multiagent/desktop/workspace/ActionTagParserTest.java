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
    void parsesAPlainReadFileTag() {
        List<ActionTagParser.ParsedAction> actions = ActionTagParser.parse("<read_file path=\"a.txt\" />");
        assertEquals(1, actions.size());
        assertEquals("read_file", actions.get(0).name());
        assertEquals("a.txt", actions.get(0).args().get("path"));
        assertTrue(!actions.get(0).args().containsKey("offset"));
    }

    @Test
    void parsesADescribeImageTagWithBothAttributesInEitherOrder() {
        List<ActionTagParser.ParsedAction> a = ActionTagParser.parse(
                "<describe_image path=\"shot.png\" question=\"what error is shown?\" />");
        assertEquals(1, a.size());
        assertEquals("describe_image", a.get(0).name());
        assertEquals("shot.png", a.get(0).args().get("path"));
        assertEquals("what error is shown?", a.get(0).args().get("question"));

        List<ActionTagParser.ParsedAction> b = ActionTagParser.parse(
                "<describe_image question=\"transcribe\" path=\"a/b.jpg\" />");
        assertEquals(1, b.size());
        assertEquals("a/b.jpg", b.get(0).args().get("path"));
        assertEquals("transcribe", b.get(0).args().get("question"));
    }

    @Test
    void ignoresADescribeImageTagWithNoPath() {
        assertTrue(ActionTagParser.parse("<describe_image question=\"hi\" />").isEmpty());
    }

    @Test
    void parsesAReadFileTagWithOffsetAndLimit() {
        List<ActionTagParser.ParsedAction> actions = ActionTagParser.parse(
                "<read_file path=\"src/Big.java\" offset=\"120\" limit=\"60\" />");
        assertEquals(1, actions.size());
        assertEquals("read_file", actions.get(0).name());
        assertEquals("src/Big.java", actions.get(0).args().get("path"));
        assertEquals("120", actions.get(0).args().get("offset"));
        assertEquals("60", actions.get(0).args().get("limit"));
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
    void parsesARenameFileTag() {
        List<ActionTagParser.ParsedAction> actions = ActionTagParser.parse(
                "<rename_file path=\"old.txt\" newPath=\"new.txt\" />");
        assertEquals(1, actions.size());
        assertEquals("rename_file", actions.get(0).name());
        assertEquals("old.txt", actions.get(0).args().get("path"));
        assertEquals("new.txt", actions.get(0).args().get("newPath"));
    }

    @Test
    void parsesABareGitStatusTag() {
        List<ActionTagParser.ParsedAction> actions = ActionTagParser.parse("Let me check: <git_status />");
        assertEquals(1, actions.size());
        assertEquals("git_status", actions.get(0).name());
        assertTrue(actions.get(0).args().isEmpty());
    }

    @Test
    void parsesGitTagsWithAttributes() {
        List<ActionTagParser.ParsedAction> actions = ActionTagParser.parse(
                "<git_diff staged=\"true\" path=\"src/Foo.java\" />\n"
                        + "<git_commit message=\"Fix the bug\" all=\"true\" />");
        assertEquals(2, actions.size());
        assertEquals("git_diff", actions.get(0).name());
        assertEquals("true", actions.get(0).args().get("staged"));
        assertEquals("src/Foo.java", actions.get(0).args().get("path"));
        assertEquals("git_commit", actions.get(1).name());
        assertEquals("Fix the bug", actions.get(1).args().get("message"));
        assertEquals("true", actions.get(1).args().get("all"));
    }

    @Test
    void doesNotMistakeAnUnknownGitLikeTagForAnAction() {
        assertTrue(ActionTagParser.parse("<git_rebase onto=\"main\" />").isEmpty());
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
