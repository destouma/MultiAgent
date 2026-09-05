package com.multiagent.desktop.llm;

import java.util.List;

/** Mirrors shared/llm/types.ts ChatCompletionResult. */
public record ChatCompletionResult(String content, List<ToolCall> toolCalls) {
}
