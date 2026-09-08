package com.multiagent.desktop.model;

/**
 * An image the user attached to the composer for one turn. Not persisted - the vision model
 * transcribes it once at send time and the description is injected into that turn's context
 * (see ChatService.send); only a short "[image: name]" marker reaches the transcript.
 */
public record ImageAttachment(String name, String mimeType, byte[] bytes) {
}
