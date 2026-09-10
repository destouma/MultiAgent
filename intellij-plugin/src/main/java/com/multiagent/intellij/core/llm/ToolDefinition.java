package com.multiagent.intellij.core.llm;

import com.fasterxml.jackson.databind.JsonNode;

/** One OpenAI-style function-tool definition, mirroring shared/workspace/workspaceService.ts's workspaceTools entries. */
public record ToolDefinition(String name, String description, JsonNode parametersSchema) {
}
