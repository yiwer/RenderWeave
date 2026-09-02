import { LosslessNumber } from 'lossless-json';
import { describe, expect, it } from 'vitest';

import {
  finiteTemplateNumber,
  positiveTemplateNumber,
  sameTemplateNumber,
  templateNumberDraft,
} from './template-editor-numbers';

describe('Template Editor authored number adapter', () => {
  it('reads native and lossless finite numbers without discarding the authored draft token', () => {
    const authored = new LosslessNumber('12.500');

    expect(finiteTemplateNumber(authored)).toBe(12.5);
    expect(finiteTemplateNumber(4.25)).toBe(4.25);
    expect(finiteTemplateNumber(new LosslessNumber('1e10000'))).toBeNull();
    expect(finiteTemplateNumber('12.5')).toBeNull();
    expect(positiveTemplateNumber(authored)).toBe(12.5);
    expect(positiveTemplateNumber(new LosslessNumber('0'))).toBeNull();
    expect(templateNumberDraft(authored)).toBe('12.500');
    expect(templateNumberDraft(4.25)).toBe('4.25');
    expect(templateNumberDraft('4.25')).toBe('');
    expect(sameTemplateNumber(authored, 12.5)).toBe(true);
    expect(sameTemplateNumber(authored, 12.5001)).toBe(false);
    expect(sameTemplateNumber(authored, Number.NaN)).toBe(false);
  });
});
