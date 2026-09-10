package com.multiagent.desktop.service;

import java.util.List;

/**
 * Heuristics for spotting when a vision model didn't actually look at the image and just
 * returned a canned "I can't see images" style reply - common with small local VLMs when
 * the server drops the image part or the model's vision path fails on an odd input. Callers
 * treat a hit as "no description available" rather than passing the refusal on to the chat
 * model, which would otherwise parrot it.
 */
final class VisionResponses {

    private static final List<String> REFUSAL_MARKERS = List.of(
            "cannot view", "can't view", "cannot directly view", "can't directly view",
            "unable to view", "not able to view", "cannot see", "can't see", "unable to see",
            "don't have access to", "do not have access to", "no access to any",
            "don't have the ability to", "cannot provide a detailed description of an image",
            "not able to provide an image", "provide a link to the image",
            "describe the image you have in mind", "provide me with more information or a detailed description",
            "as an ai language model, i don't have access", "as an ai language model, i cannot",
            // A VLM that got the prompt but no usable image often just asks for one back.
            "please provide the image", "please share the image", "please upload the image",
            "provide the image you would like", "provide the image you'd like",
            "no image was provided", "i don't see an image", "i do not see an image",
            "there is no image", "haven't provided an image", "have not provided an image");

    /** Longer than this is almost certainly a real description that merely opens with a hedge. */
    private static final int MAX_REFUSAL_LENGTH = 500;

    private VisionResponses() {
    }

    static boolean looksLikeRefusal(String text) {
        if (text == null) {
            return true;
        }
        String trimmed = text.strip();
        if (trimmed.isEmpty()) {
            return true;
        }
        if (trimmed.length() > MAX_REFUSAL_LENGTH) {
            return false;
        }
        String lower = trimmed.toLowerCase();
        return REFUSAL_MARKERS.stream().anyMatch(lower::contains);
    }
}
