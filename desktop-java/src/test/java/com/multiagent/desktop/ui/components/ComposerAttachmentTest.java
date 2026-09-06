package com.multiagent.desktop.ui.components;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Covers Composer.buildMessageWithAttachment() - the pure-logic piece of the text-file
 * attachment feature (folding an attached file into the plain message text as a
 * ```filename\ncontent``` fence, which then renders/downloads/copies via ChatThread's
 * existing codeBox() with no separate attachment rendering path or schema change).
 */
class ComposerAttachmentTest {

    @Test
    void returnsTypedTextUnchangedWhenThereIsNoAttachment() {
        assertEquals("hello", Composer.buildMessageWithAttachment("hello", null, null));
    }

    @Test
    void appendsTheAttachmentAsAFencedBlockAfterTheTypedText() {
        String result = Composer.buildMessageWithAttachment("what does this do?", "notes.txt", "hello world");
        assertEquals("what does this do?\n\n```notes.txt\nhello world\n```", result);
    }

    @Test
    void sendsJustTheFenceWhenNothingWasTyped() {
        assertEquals("```notes.txt\nhello world\n```",
                Composer.buildMessageWithAttachment("", "notes.txt", "hello world"));
        assertEquals("```notes.txt\nhello world\n```",
                Composer.buildMessageWithAttachment(null, "notes.txt", "hello world"));
        assertEquals("```notes.txt\nhello world\n```",
                Composer.buildMessageWithAttachment("   ", "notes.txt", "hello world"));
    }
}
