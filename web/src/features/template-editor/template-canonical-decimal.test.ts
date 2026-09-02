import { describe, expect, it } from 'vitest';

import {
  canonicalTemplateDecimal,
  TemplateCanonicalDecimalError,
} from './template-canonical-decimal';

describe('template canonical decimal', () => {
  it.each([
    ['1.0', '1'],
    ['-0', '0'],
    ['12.3400', '12.34'],
    ['1.25e3', '1250'],
    ['1.25e-3', '0.00125'],
  ])('canonicalizes %s with the Java decimal wire rules', (token, expected) => {
    expect(canonicalTemplateDecimal(token)).toBe(expected);
  });

  it('accepts at most 256 UTF-8 bytes in an authored number token', () => {
    expect(canonicalTemplateDecimal('1'.repeat(256))).toBe('1'.repeat(256));
    expect(() => canonicalTemplateDecimal('1'.repeat(257))).toThrowError(
      new TemplateCanonicalDecimalError('NUMBER_TOKEN_TOO_LARGE'),
    );
  });

  it('fails closed before expanding a decimal beyond the 16 MiB canonical limit', () => {
    expect(() => canonicalTemplateDecimal('1e20000000')).toThrowError(
      new TemplateCanonicalDecimalError('CANONICAL_SIZE_EXCEEDED'),
    );
  });
});
