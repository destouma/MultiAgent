// Rough token estimate (~4 chars/token, a common approximation for English
// text) used only to warn before a context_exceeded error, not to bill or
// enforce anything — there's no bundled tokenizer, and it would need to be
// model-specific anyway across the range of local models this app supports.
export function estimateTokens(texts: string[]): number {
  const chars = texts.reduce((sum, text) => sum + text.length, 0);
  return Math.ceil(chars / 4);
}

export type ContextUsageLevel = 'ok' | 'warn' | 'danger';

const WARN_THRESHOLD = 4000;
const DANGER_THRESHOLD = 8000;

export function contextUsageLevel(estimatedTokens: number): ContextUsageLevel {
  if (estimatedTokens >= DANGER_THRESHOLD) return 'danger';
  if (estimatedTokens >= WARN_THRESHOLD) return 'warn';
  return 'ok';
}
