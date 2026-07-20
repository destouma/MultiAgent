import { describe, expect, it } from 'vitest';
import { parsePlan } from './planParser';

const ALLOWED = ['researcher', 'coder', 'critic'] as const;

describe('parsePlan', () => {
  it('parses a valid plan JSON blob, ignoring surrounding prose', () => {
    const raw =
      'Sure, here is the plan:\n{"specialists":["coder","critic"],"rationale":"needs code and review"}\nthanks';
    expect(parsePlan(raw, ALLOWED)).toEqual({
      specialists: ['coder', 'critic'],
      rationale: 'needs code and review',
    });
  });

  it('filters out specialist ids that are not in the allowed list', () => {
    const raw = '{"specialists":["coder","astrologer"],"rationale":"pick coder"}';
    expect(parsePlan(raw, ALLOWED)).toEqual({
      specialists: ['coder'],
      rationale: 'pick coder',
    });
  });

  it('dedupes specialists and caps the list at three', () => {
    const raw =
      '{"specialists":["researcher","researcher","coder","critic","researcher"],"rationale":"r"}';
    const result = parsePlan(raw, ALLOWED);
    expect(result.specialists).toEqual(['researcher', 'coder', 'critic']);
  });

  it('defaults rationale when missing or blank', () => {
    const raw = '{"specialists":["researcher"]}';
    expect(parsePlan(raw, ALLOWED).rationale).toBe('Plan selected by orchestrator');
  });

  it('returns an empty specialists array when the field is missing entirely', () => {
    const raw = '{"rationale":"no specialists needed"}';
    expect(parsePlan(raw, ALLOWED)).toEqual({
      specialists: [],
      rationale: 'no specialists needed',
    });
  });

  it('falls back to a default plan when the response has no JSON object', () => {
    expect(parsePlan('I will just answer directly.', ALLOWED)).toEqual({
      specialists: ['researcher'],
      rationale: 'Default plan',
    });
  });

  it('falls back to a default plan when the JSON is malformed', () => {
    const raw = '{"specialists": ["coder",}';
    expect(parsePlan(raw, ALLOWED)).toEqual({
      specialists: ['researcher'],
      rationale: 'Default plan',
    });
  });
});
