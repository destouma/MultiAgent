import { describe, expect, it } from 'vitest';
import { contextUsageLevel, estimateTokens } from './tokenEstimate';

describe('estimateTokens', () => {
  it('estimates roughly 1 token per 4 characters', () => {
    expect(estimateTokens(['abcdefgh'])).toBe(2);
  });

  it('sums characters across multiple strings before dividing', () => {
    expect(estimateTokens(['ab', 'cd', 'ef'])).toBe(2);
  });

  it('rounds up partial tokens', () => {
    expect(estimateTokens(['abc'])).toBe(1);
  });

  it('returns 0 for empty input', () => {
    expect(estimateTokens([])).toBe(0);
    expect(estimateTokens([''])).toBe(0);
  });
});

describe('contextUsageLevel', () => {
  it('is "ok" below the warn threshold', () => {
    expect(contextUsageLevel(0)).toBe('ok');
    expect(contextUsageLevel(3999)).toBe('ok');
  });

  it('is "warn" between the warn and danger thresholds', () => {
    expect(contextUsageLevel(4000)).toBe('warn');
    expect(contextUsageLevel(7999)).toBe('warn');
  });

  it('is "danger" at or above the danger threshold', () => {
    expect(contextUsageLevel(8000)).toBe('danger');
    expect(contextUsageLevel(20000)).toBe('danger');
  });
});
