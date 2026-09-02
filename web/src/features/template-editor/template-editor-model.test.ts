/// <reference types="node" />

import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

import { parse } from 'lossless-json';
import { describe, expect, it } from 'vitest';

import {
  createRawRepairSession,
  createSessionFromBaseline,
  projectStructuredNodes,
} from './template-editor-model';
import { structuredBaseline } from './template-editor-test-support';

describe('Template Editor E1 modes', () => {
  it('opens the shared all-kinds canonical fixture in Structured mode', () => {
    const canonicalDesignDsl = readFileSync(fileURLToPath(new URL(
      '../../../../renderweave-template/src/test/resources/cn/hbads/renderweave/template/complete-wire-v1/all-kinds.json',
      import.meta.url,
    )), 'utf8');
    const baseline = structuredBaseline();
    baseline.canonicalDesignDsl = canonicalDesignDsl;
    baseline.designDsl = parse(canonicalDesignDsl) as Record<string, unknown>;

    const session = createSessionFromBaseline(baseline, { state: 'checked', value: 'READY' });

    expect(session.mode).toBe('structured');
    if (session.mode !== 'structured') throw new Error('expected Structured Editor');
    expect(new Set(projectStructuredNodes(session).map((node) => node.kind)).size).toBe(18);
  });

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

    const unknownNestedProperty = structuredBaseline();
    const frame = ((unknownNestedProperty.designDsl.designRoot as Record<string, unknown>)
      .children as Record<string, unknown>[])[0];
    const rect = (frame?.children as Record<string, unknown>[] | undefined)?.[0];
    if (!rect) throw new Error('expected Rect fixture');
    rect.futurePaint = { opaque: true };
    const nestedSession = createSessionFromBaseline(
      unknownNestedProperty,
      { state: 'checked', value: 'READY' },
    );
    expect(nestedSession).toEqual(expect.objectContaining({
      mode: 'compatibility',
      reason: expect.stringContaining('designRoot.children[0].children[0].futurePaint'),
    }));
  });

  it('rejects malformed authored structure instead of exposing a current session', () => {
    const malformed = structuredBaseline();
    const frame = ((malformed.designDsl.designRoot as Record<string, unknown>)
      .children as Record<string, unknown>[])[0];
    if (!frame) throw new Error('expected Frame fixture');
    delete frame.nodeId;

    expect(() => createSessionFromBaseline(
      malformed,
      { state: 'checked', value: 'READY' },
    )).toThrow(/designRoot\.children\[0\]\.nodeId/);
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
