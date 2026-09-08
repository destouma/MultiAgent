package com.multiagent.desktop.ui.components;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CodeBlockExtensionsTest {

    @Test
    void mapsCommonLanguagesToTheirExtension() {
        assertEquals("py", CodeBlockExtensions.extensionFor("python"));
        assertEquals("js", CodeBlockExtensions.extensionFor("javascript"));
        assertEquals("java", CodeBlockExtensions.extensionFor("java"));
        assertEquals("sh", CodeBlockExtensions.extensionFor("bash"));
    }

    @Test
    void isCaseInsensitiveAndTrimsWhitespace() {
        assertEquals("py", CodeBlockExtensions.extensionFor("  Python  "));
        assertEquals("cpp", CodeBlockExtensions.extensionFor("C++"));
    }

    @Test
    void fallsBackToTxtForBlankOrNull() {
        assertEquals("txt", CodeBlockExtensions.extensionFor(""));
        assertEquals("txt", CodeBlockExtensions.extensionFor("   "));
        assertEquals("txt", CodeBlockExtensions.extensionFor(null));
    }

    @Test
    void fallsBackToTxtForAnUnrecognizedLanguage() {
        assertEquals("txt", CodeBlockExtensions.extensionFor("some-made-up-language"));
    }
}
