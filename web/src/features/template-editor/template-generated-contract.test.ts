import { describe, expect, expectTypeOf, it } from 'vitest';

import type {
  DesignBinding,
  DesignDefinition,
  DesignDslKernel,
  DesignNode,
  DesignPlacement,
  DesignValueSource,
} from '../../api/generated';

describe('generated DesignDSL authoring contract', () => {
  it('keeps the complete closed unions instead of degrading authored values to unknown', () => {
    expectTypeOf<DesignDslKernel['definitions'][number]>()
      .toEqualTypeOf<DesignDefinition>();
    expectTypeOf<DesignDslKernel['designRoot']['children'][number]>()
      .toEqualTypeOf<DesignNode>();
    expectTypeOf<DesignNode['bindings'][number]>()
      .toEqualTypeOf<DesignBinding>();
    expectTypeOf<DesignNode['placement']>()
      .toEqualTypeOf<DesignPlacement>();
    expectTypeOf<DesignDefinition['kind']>()
      .toEqualTypeOf<'custom' | 'mapping' | 'expression'>();
    expectTypeOf<DesignValueSource['kind']>()
      .toEqualTypeOf<'literal' | 'context' | 'loopIndex' | 'definition' | 'capability'>();
    expectTypeOf<DesignPlacement['type']>()
      .toEqualTypeOf<'ABSOLUTE' | 'STACK' | 'GRID' | 'PACK'>();
    expectTypeOf<DesignNode['kind']>().toEqualTypeOf<
      | 'group'
      | 'frame'
      | 'stack'
      | 'grid'
      | 'repeat'
      | 'text'
      | 'image'
      | 'rect'
      | 'ellipse'
      | 'line'
      | 'polygon'
      | 'polyline'
      | 'path'
      | 'qrCode'
      | 'barcode'
      | 'templateUse'
      | 'conditional'
    >();

    expect(true).toBe(true);
  });
});
