package com.multiagent.intellij.core.service;

import com.multiagent.intellij.core.llm.ChatRequestMessage;
import com.multiagent.intellij.core.model.ImageAttachment;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenEstimateTest {

    @Test
    void estimatesAboutFourCharsPerToken() {
        assertEquals(3, TokenEstimate.estimateTokens(List.of("hello world!"))); // 12 chars -> ceil(12/4) = 3
    }

    @Test
    void sumsCharsAcrossMultipleTexts() {
        assertEquals(3, TokenEstimate.estimateTokens(List.of("abcd", "abcdefgh"))); // 12 chars total -> ceil(12/4) = 3
    }

    @Test
    void treatsNullTextsAsZeroChars() {
        assertEquals(1, TokenEstimate.estimateTokens(java.util.Arrays.asList("ab", null)));
    }

    @Test
    void estimateMessagesSumsContentPlusPerMessageOverhead() {
        // "abcd" (4 chars -> 1) + "abcdefgh" (8 -> 2) = 3 content tokens, + 4 overhead per message
        int estimate = TokenEstimate.estimateMessages(List.of(
                ChatRequestMessage.user("abcd"), ChatRequestMessage.assistant("abcdefgh")));
        assertEquals(3 + 2 * 4, estimate);
    }

    @Test
    void estimateMessagesAddsAFlatCostForAnInlineImage() {
        int text = TokenEstimate.estimateMessages(List.of(ChatRequestMessage.user("hi")));
        int withImage = TokenEstimate.estimateMessages(List.of(ChatRequestMessage.userWithImage(
                "hi", new ImageAttachment("a.png", "image/png", new byte[]{1, 2, 3}))));
        assertEquals(text + TokenEstimate.IMAGE_TOKENS, withImage);
        assertTrue(withImage > 1000);
    }

    @Test
    void classifiesLevelsAtTheThresholds() {
        assertEquals(TokenEstimate.Level.OK, TokenEstimate.levelFor(3999));
        assertEquals(TokenEstimate.Level.WARN, TokenEstimate.levelFor(4000));
        assertEquals(TokenEstimate.Level.WARN, TokenEstimate.levelFor(7999));
        assertEquals(TokenEstimate.Level.DANGER, TokenEstimate.levelFor(8000));
    }
}
