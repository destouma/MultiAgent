export type PlanResult = {
  specialists: string[];
  rationale: string;
};

const FALLBACK_PLAN: PlanResult = {
  specialists: ['researcher'],
  rationale: 'Default plan',
};

/**
 * Parses the orchestrator's specialist-selection response (expected to be a
 * JSON object embedded in the completion text). Falls back to a single
 * default specialist if the model didn't return valid, well-formed JSON.
 */
export function parsePlan(raw: string, allowedIds: readonly string[]): PlanResult {
  const jsonMatch = raw.match(/\{[\s\S]*\}/);
  if (!jsonMatch) return FALLBACK_PLAN;

  try {
    const parsed = JSON.parse(jsonMatch[0]) as {
      specialists?: unknown;
      rationale?: unknown;
    };
    const ids = Array.isArray(parsed.specialists)
      ? parsed.specialists
          .map((item) => String(item))
          .filter((id): id is string => allowedIds.includes(id))
      : [];
    const unique = [...new Set(ids)].slice(0, 3);
    return {
      specialists: unique,
      rationale:
        typeof parsed.rationale === 'string' && parsed.rationale.trim()
          ? parsed.rationale.trim()
          : 'Plan selected by orchestrator',
    };
  } catch {
    return FALLBACK_PLAN;
  }
}
