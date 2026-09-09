package com.multiagent.intellij.core.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisionResponsesTest {

    @Test
    void flagsTheCannedICantSeeImagesReplies() {
        // Verbatim from a Qwen2.5-VL-3B run where the image part was dropped.
        assertTrue(VisionResponses.looksLikeRefusal(
                "I'm sorry, but I cannot provide a detailed description of an image as I don't have "
                        + "access to any visual content. Please provide me with more information or a "
                        + "detailed description of the image you would like me to describe."));
        assertTrue(VisionResponses.looksLikeRefusal(
                "I'm sorry, but I'm not able to provide an image for you to describe. Could you please "
                        + "describe the image you have in mind or provide a link to the image?"));
        assertTrue(VisionResponses.looksLikeRefusal("I cannot directly view images."));
        assertTrue(VisionResponses.looksLikeRefusal(""));
        assertTrue(VisionResponses.looksLikeRefusal(null));
    }

    @Test
    void doesNotFlagARealDescription() {
        assertFalse(VisionResponses.looksLikeRefusal(
                "The image shows the \"Edit server\" dialog of a desktop application. It has fields for "
                        + "Name (set to \"Local\"), Provider (a dropdown set to LEMONADE), Base URL "
                        + "(http://localhost:13305/api/v1), API key (masked), Max history (40), and a "
                        + "Vision model dropdown. Two buttons at the bottom read OK and Cancel."));
        assertFalse(VisionResponses.looksLikeRefusal(
                "A red circle on a white background, roughly centered, about 200px across."));
    }
}
