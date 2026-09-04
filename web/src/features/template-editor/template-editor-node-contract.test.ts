import { describe, expect, it } from 'vitest';

import {
  expectedTemplateChildPlacement,
  isCoreTemplateAuthoringKind,
  isCoreTemplateAuthoringParentKind,
  isTemplateNodeSizeModeAllowed,
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

  it('includes structural nodes in the core command and parent slice', () => {
    expect(isTemplateDesignContainerKind('grid')).toBe(true);
    expect(isCoreTemplateAuthoringKind('group')).toBe(true);
    expect(isCoreTemplateAuthoringKind('grid')).toBe(true);
    expect(isCoreTemplateAuthoringKind('repeat')).toBe(true);
    expect(isCoreTemplateAuthoringKind('conditional')).toBe(true);
    expect(isCoreTemplateAuthoringKind('templateUse')).toBe(true);
    for (const kind of [
      'text', 'image', 'rect', 'ellipse', 'line', 'polygon',
      'polyline', 'path', 'qrCode', 'barcode',
    ]) {
      expect(isCoreTemplateAuthoringKind(kind)).toBe(true);
    }
    expect(isCoreTemplateAuthoringParentKind('group')).toBe(true);
    expect(isCoreTemplateAuthoringParentKind('stack')).toBe(true);
    expect(isCoreTemplateAuthoringParentKind('grid')).toBe(true);
    expect(isCoreTemplateAuthoringParentKind('repeat')).toBe(true);
    expect(isCoreTemplateAuthoringParentKind('conditional')).toBe(true);
    expect(isCoreTemplateAuthoringParentKind('templateUse')).toBe(false);
    expect(isCoreTemplateAuthoringParentKind('rect')).toBe(false);
  });

  it('projects the formal node-kind size-mode capability matrix', () => {
    expect(isTemplateNodeSizeModeAllowed('group', 'HUG_CONTENT')).toBe(true);
    expect(isTemplateNodeSizeModeAllowed('group', 'FIXED')).toBe(false);
    expect(isTemplateNodeSizeModeAllowed('rect', 'FILL')).toBe(true);
    expect(isTemplateNodeSizeModeAllowed('rect', 'HUG_CONTENT')).toBe(false);
    expect(isTemplateNodeSizeModeAllowed('qrCode', 'FIXED')).toBe(true);
    expect(isTemplateNodeSizeModeAllowed('qrCode', 'FILL')).toBe(true);
    expect(isTemplateNodeSizeModeAllowed('qrCode', 'HUG_CONTENT')).toBe(false);
    expect(isTemplateNodeSizeModeAllowed('grid', 'FIXED')).toBe(true);
    expect(isTemplateNodeSizeModeAllowed('grid', 'HUG_CONTENT')).toBe(true);
    expect(isTemplateNodeSizeModeAllowed('grid', 'FILL')).toBe(true);
    expect(isTemplateNodeSizeModeAllowed('canvas', 'FIXED')).toBe(false);
  });
});
