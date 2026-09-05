package com.multiagent.desktop.llm;

/** Mirrors shared/llm/types.ts ChatCompletionResult.toolCalls[]. Unused until Phase 2. */
public record ToolCall(String id, String name, String arguments) {
}
