package com.multiagent.intellij.core.llm;

/** Mirrors shared/llm/types.ts GenerateImageInput. Stub for Phase 3. */
public record GenerateImageInput(String prompt, String model, String size, Integer steps,
                                  Double cfgScale, Long seed) {
}
