package com.multiagent.desktop.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
    void classifiesLevelsAtTheThresholds() {
        assertEquals(TokenEstimate.Level.OK, TokenEstimate.levelFor(3999));
        assertEquals(TokenEstimate.Level.WARN, TokenEstimate.levelFor(4000));
        assertEquals(TokenEstimate.Level.WARN, TokenEstimate.levelFor(7999));
        assertEquals(TokenEstimate.Level.DANGER, TokenEstimate.levelFor(8000));
    }
}
