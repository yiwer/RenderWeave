import { describe, expect, it } from 'vitest';

import {
  expectedTemplateChildPlacement,
  isCoreTemplateAuthoringKind,
  isCoreTemplateAuthoringParentKind,
  isTemplateDesignContainerKind,
  TEMPLATE_DESIGN_NODE_KINDS,
} from './template-editor-node-contract';

describe('Template Editor node contract projection', () => {
  it('projects every formal node kind and its exact child placement capability', () => {
    expect(TEMPLATE_DESIGN_NODE_KINDS.size).toBe(18);
    expect(expectedTemplateChildPlacement('canvas')).toBe('ABSOLUTE');
    expect(expectedTemplateChildPlacement('frame')).toBe('ABSOLUTE');
    expect(expectedTemplateChildPlacement('stack')).toBe('STACK');
    expect(expectedTemplateChildPlacement('grid')).toBe('GRID');
    expect(expectedTemplateChildPlacement('repeat')).toBe('PACK');
    expect(expectedTemplateChildPlacement('conditional')).toBe('ABSOLUTE');
    expect(expectedTemplateChildPlacement('rect')).toBeNull();
    expect(expectedTemplateChildPlacement('unknown')).toBeNull();
  });

  it('distinguishes complete DesignDSL containers from the current core command slice', () => {
    expect(isTemplateDesignContainerKind('grid')).toBe(true);
    expect(isCoreTemplateAuthoringKind('grid')).toBe(false);
    for (const kind of [
      'text', 'image', 'rect', 'ellipse', 'line', 'polygon',
      'polyline', 'path', 'qrCode', 'barcode',
    ]) {
      expect(isCoreTemplateAuthoringKind(kind)).toBe(true);
    }
    expect(isCoreTemplateAuthoringParentKind('stack')).toBe(true);
    expect(isCoreTemplateAuthoringParentKind('rect')).toBe(false);
    expect(isCoreTemplateAuthoringParentKind('grid')).toBe(false);
  });
});
