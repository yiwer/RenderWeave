import { LosslessNumber } from 'lossless-json';
import { describe, expect, it } from 'vitest';

import { decodeTemplateTargetPropertyRef } from './template-target-property-ref';

describe('Template TargetPropertyRef codec', () => {
  it('decodes a LosslessNumber index and canonicalizes selector order', () => {
    expect(decodeTemplateTargetPropertyRef({
      rootPropertyId: 'runs',
      selectors: [
        { kind: 'member', name: 'text' },
        { kind: 'index', index: new LosslessNumber('0') },
      ],
    })).toEqual({
      targetPropertyRef: {
        rootPropertyId: 'runs',
        selectors: [
          { kind: 'index', index: 0 },
          { kind: 'member', name: 'text' },
        ],
      },
      propertyPath: 'runs[0].text',
    });
  });

  it.each([
    { selectors: [{ kind: 'index', index: 0 }, { kind: 'index', index: 1 }] },
    { selectors: [{ kind: 'member', name: 'x' }, { kind: 'member', name: 'y' }] },
  ])('rejects duplicate selector kinds instead of projecting an ambiguous path', ({ selectors }) => {
    expect(decodeTemplateTargetPropertyRef({ rootPropertyId: 'runs', selectors })).toBeNull();
  });

  it('rejects unknown members and non-canonical lossless index tokens', () => {
    expect(decodeTemplateTargetPropertyRef({
      rootPropertyId: 'runs',
      selectors: [],
      extra: true,
    })).toBeNull();
    expect(decodeTemplateTargetPropertyRef({
      rootPropertyId: 'runs',
      selectors: [{ kind: 'index', index: new LosslessNumber('1.0') }],
    })).toBeNull();
  });
});
