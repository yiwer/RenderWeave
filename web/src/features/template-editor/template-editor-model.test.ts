import { describe, expect, it } from 'vitest';

import {
  createRawRepairSession,
  createSessionFromBaseline,
  projectStructuredNodes,
} from './template-editor-model';
import { structuredBaseline } from './template-editor-test-support';

describe('Template Editor E1 modes', () => {
  it('projects a trusted exact-profile current into Structured mode in authored preorder', () => {
    const baseline = structuredBaseline();
    const session = createSessionFromBaseline(baseline, { state: 'checked', value: 'READY' });

    expect(session.mode).toBe('structured');
    if (session.mode !== 'structured') throw new Error('expected Structured Editor');
    expect(projectStructuredNodes(session)).toEqual([
      expect.objectContaining({ nodeId: 'canvas-id', kind: 'canvas', depth: 0 }),
      expect.objectContaining({ nodeId: 'frame-id', kind: 'frame', depth: 1 }),
      expect.objectContaining({ nodeId: 'rect-id', kind: 'rect', depth: 2 }),
    ]);
  });

  it('uses Compatibility Read-only for an unknown exact profile or node wire', () => {
    const unknownProfile = structuredBaseline();
    unknownProfile.designDsl.expressionProfile = 'renderweave-expression/2.0';
    expect(createSessionFromBaseline(
      unknownProfile,
      { state: 'checked', value: 'READY' },
    ).mode).toBe('compatibility');

    const unknownNode = structuredBaseline();
    (unknownNode.designDsl.designRoot as Record<string, unknown>).children = [{
      nodeId: 'future-id', kind: 'futureNode', children: [], bindings: [],
    }];
    expect(createSessionFromBaseline(
      unknownNode,
      { state: 'checked', value: 'READY' },
    ).mode).toBe('compatibility');
  });

  it('keeps Raw Repair as an explicit local-buffer-only mode with no canonical baseline', () => {
    const session = createRawRepairSession('{"dslVersion":', 'JSON 未闭合');

    expect(session).toEqual(expect.objectContaining({
      mode: 'raw-repair',
      rawBuffer: '{"dslVersion":',
      problem: 'JSON 未闭合',
    }));
    expect('baseline' in session).toBe(false);
  });
});
