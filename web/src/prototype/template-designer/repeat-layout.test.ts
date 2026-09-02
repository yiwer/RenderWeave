import { describe, expect, it } from 'vitest';

import type { DesignerNode, DraftBox, InspectorProp } from './model';
import { childTemplateIds, definitionIds } from './model';
import { projectPrototypeRepeat } from './repeat-layout';

function prop(label: string, value: string): InspectorProp {
  return { label, value, bindable: false };
}

function child(
  id: string,
  kind: DesignerNode['kind'] = 'text',
  props: InspectorProp[] = [],
): DesignerNode {
  return { id, kind, name: id, detail: '', flags: [], props, children: [] };
}

function repeat(
  source: string,
  children: DesignerNode[],
  overrides: Record<string, string> = {},
): DesignerNode {
  const values = {
    items: source,
    absentPolicy: 'EMPTY',
    'itemLayout.kind': 'STACK',
    'itemLayout.direction': 'ROW',
    'itemLayout.gapMm': '1',
    'itemLayout.columns': '2',
    'itemLayout.columnGapMm': '1',
    'itemLayout.rowGapMm': '1',
    'instanceLayout.kind': 'GRID',
    'instanceLayout.direction': 'ROW',
    'instanceLayout.gapMm': '2',
    'instanceLayout.columns': '2',
    'instanceLayout.columnGapMm': '2',
    'instanceLayout.rowGapMm': '3',
    ...overrides,
  };
  return {
    id: 'repeat',
    loopId: 'loop',
    kind: 'repeat',
    name: 'repeat',
    detail: '',
    flags: [],
    props: Object.entries(values).map(([label, value]) => prop(label, value)),
    children,
  };
}

function box(nodeId: string, w: number, h: number): DraftBox {
  return { nodeId, x: 0, y: 0, w, h, tone: 'frame', label: nodeId };
}

describe('projectPrototypeRepeat', () => {
  it('proves list sources from the system schema and authored definitions', () => {
    const scalar = projectPrototypeRepeat(
      repeat('context(invocation, /tags)', [child('label')]),
      [box('label', 10, 4)],
      'values',
    );
    const referenceUse = child('offer-use', 'templateUse', [
      prop('templateRef.templateId', childTemplateIds.offerCard),
    ]);
    const reference = projectPrototypeRepeat(
      repeat('context(invocation, /offers)', [referenceUse]),
      [box('offer-use', 20, 8)],
      'values',
    );
    const definition = projectPrototypeRepeat(
      repeat(`definition(${definitionIds.featuredTags})`, [child('definition-label')]),
      [box('definition-label', 10, 4)],
      'values',
    );

    expect(scalar.sourceProof).toMatchObject({ valid: true, sourceGroup: 'SYSTEM', sourceType: 'SCALAR_LIST', itemStaticSchemaRef: 'system-basic-text@v1' });
    expect(reference.sourceProof).toMatchObject({ valid: true, sourceGroup: 'SYSTEM', sourceType: 'REFERENCE_LIST', itemStaticSchemaRef: 'offer-card@v2' });
    expect(definition.sourceProof).toMatchObject({ valid: true, sourceGroup: 'CUSTOM', sourceType: 'SCALAR_LIST', itemStaticSchemaRef: 'system-basic-text@v1' });
    expect(definition.occurrences.map((entry) => entry.label)).toEqual(['精选', '当季', '限量']);
    expect(reference.compatibleTemplates.map((candidate) => candidate.templateId)).toEqual([childTemplateIds.offerCard]);
  });

  it('keeps missing-list EMPTY and ERROR outcomes distinct', () => {
    const item = child('label');
    const empty = projectPrototypeRepeat(
      repeat('context(invocation, /tags)', [item], { absentPolicy: 'EMPTY' }),
      [box('label', 10, 4)],
      'absent',
    );
    const error = projectPrototypeRepeat(
      repeat('context(invocation, /tags)', [item], { absentPolicy: 'ERROR' }),
      [box('label', 10, 4)],
      'absent',
    );

    expect(empty).toMatchObject({ phase: 'READY', outcome: 'EMPTY', occurrences: [] });
    expect(error).toMatchObject({ phase: 'READY', outcome: 'ABSENT_ERROR', occurrences: [] });
  });

  it('packs authored item children before independently packing virtual instances', () => {
    const label = child('label');
    const badge = child('badge', 'rect');
    const projection = projectPrototypeRepeat(
      repeat('context(invocation, /tags)', [label, badge]),
      [box('label', 10, 4), box('badge', 4, 6)],
      'values',
    );

    expect(projection.itemLayout).toMatchObject({ kind: 'STACK', widthMm: 15, heightMm: 6 });
    expect(projection.itemLayout.children.map((entry) => ({ id: entry.authoredNodeId, x: entry.xMm }))).toEqual([
      { id: 'label', x: 0 },
      { id: 'badge', x: 11 },
    ]);
    expect(projection.instanceLayout).toMatchObject({ kind: 'GRID', columns: 2 });
    expect(projection.occurrences.map(({ xMm, yMm }) => [xMm, yMm])).toEqual([
      [0, 0],
      [17, 0],
      [0, 9],
      [17, 9],
    ]);
  });

  it('reports authored content that no longer matches the selected item schema as repairable', () => {
    const staleScalarItem = child('label', 'text', [
      {
        ...prop('runs[0].text', '新品'),
        bindable: true,
        binding: { id: 'binding', ref: 'runs[0].text', source: 'context(loop loop, /value)', note: '' },
      },
    ]);
    const projection = projectPrototypeRepeat(
      repeat('context(invocation, /offers)', [staleScalarItem]),
      [box('label', 10, 4)],
      'values',
    );

    expect(projection).toMatchObject({ phase: 'NEEDS_REPAIR', outcome: 'INVALID' });
    expect(projection.message).toContain('offer-card@v2');
  });

  it('creates virtual occurrences without mutating the authored subtree or draft boxes', () => {
    const authored = repeat('context(invocation, /tags)', [child('label')]);
    const boxes = [box('label', 10, 4)];
    const authoredBefore = structuredClone(authored);
    const boxesBefore = structuredClone(boxes);
    const projection = projectPrototypeRepeat(authored, boxes, 'values');

    expect(projection.occurrences).toHaveLength(4);
    expect(projection.occurrences.every((entry) => entry.virtualId.startsWith('repeat::occurrence::'))).toBe(true);
    expect(authored).toEqual(authoredBefore);
    expect(boxes).toEqual(boxesBefore);
    expect(authored.children).toHaveLength(1);
  });
});
