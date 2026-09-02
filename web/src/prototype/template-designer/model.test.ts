import { describe, expect, it } from 'vitest';

import {
  childTemplateIds,
  designerReducer,
  findNode,
  flattenDesignerTree,
  initialDesignerState,
  nestedTemplates,
  nodeIds,
  templateUseContextSources,
  type DesignerDefinition,
  type DesignerState,
  type LayerOrderOperation,
} from './model';
import { projectPrototypeRepeat } from './repeat-layout';

function selectedState(...selectedNodeIds: string[]): DesignerState {
  return {
    ...structuredClone(initialDesignerState),
    selectedNodeId: selectedNodeIds.at(-1) ?? nodeIds.canvas,
    selectedNodeIds: selectedNodeIds.length > 0 ? selectedNodeIds : [nodeIds.canvas],
    notice: null,
  };
}

function childIds(state: DesignerState, parentId = nodeIds.rootStack): string[] {
  return findNode(state.tree, parentId)?.children.map((child) => child.id) ?? [];
}

function expectedOrder(
  children: string[],
  selectedNodeIds: string[],
  operation: LayerOrderOperation,
): string[] {
  const selected = new Set(selectedNodeIds);
  const ordered = [...children];
  if (operation === 'front') {
    return [...ordered.filter((id) => !selected.has(id)), ...ordered.filter((id) => selected.has(id))];
  }
  if (operation === 'back') {
    return [...ordered.filter((id) => selected.has(id)), ...ordered.filter((id) => !selected.has(id))];
  }
  if (operation === 'forward') {
    for (let index = ordered.length - 2; index >= 0; index -= 1) {
      if (selected.has(ordered[index]!) && !selected.has(ordered[index + 1]!)) {
        [ordered[index], ordered[index + 1]] = [ordered[index + 1]!, ordered[index]!];
      }
    }
    return ordered;
  }
  for (let index = 1; index < ordered.length; index += 1) {
    if (selected.has(ordered[index]!) && !selected.has(ordered[index - 1]!)) {
      [ordered[index - 1], ordered[index]] = [ordered[index]!, ordered[index - 1]!];
    }
  }
  return ordered;
}

describe('designerReducer layer ordering', () => {
  it.each<LayerOrderOperation>(['front', 'forward', 'backward', 'back'])(
    'applies %s only by reordering the selected siblings',
    (operation) => {
      const state = selectedState(nodeIds.brandLogo);
      const before = childIds(state);
      const result = designerReducer(state, { type: 'reorder-selection', operation });

      expect(childIds(result)).toEqual(expectedOrder(before, [nodeIds.brandLogo], operation));
      expect(result.selectedNodeIds).toEqual([nodeIds.brandLogo]);
      expect(result.dirty).toBe(true);
      expect(findNode(result.tree, nodeIds.brandLogo)).toEqual(findNode(state.tree, nodeIds.brandLogo));
    },
  );

  it('moves a multi-selection as one stable relative-order set', () => {
    const selected: string[] = [nodeIds.priceBand, nodeIds.brandLogo];
    const state = selectedState(...selected);
    const before = childIds(state);
    const result = designerReducer(state, { type: 'reorder-selection', operation: 'forward' });

    expect(childIds(result)).toEqual(expectedOrder(before, selected, 'forward'));
    expect(childIds(result).filter((id) => selected.includes(id))).toEqual(selected);
  });

  it('never crosses a parent boundary and keeps draft boxes in authored preorder', () => {
    const state = selectedState(nodeIds.priceText);
    const result = designerReducer(state, { type: 'reorder-selection', operation: 'front' });
    const priceChildren = findNode(result.tree, nodeIds.priceBand)?.children.map((child) => child.id);
    const rootChildren = childIds(result);
    const boxIds = new Set(result.boxes.map((box) => box.nodeId));
    const authoredBoxOrder = flattenDesignerTree(result.tree)
      .filter((node) => node.kind !== 'canvas')
      .map((node) => node.id)
      .filter((id) => boxIds.has(id));

    expect(priceChildren?.at(-1)).toBe(nodeIds.priceText);
    expect(rootChildren).toEqual(childIds(state));
    expect(result.boxes.map((box) => box.nodeId)).toEqual(authoredBoxOrder);
  });
});

describe('designerReducer Repeat demos', () => {
  it('loads one authored scalar item subtree and projects four virtual instances', () => {
    const result = designerReducer(selectedState(), { type: 'load-repeat-demo', preset: 'scalar-tags' });
    const repeat = findNode(result.tree, result.selectedNodeId)!;
    const projection = projectPrototypeRepeat(repeat, result.boxes, result.repeatPreviewSample);

    expect(repeat.kind).toBe('repeat');
    expect(repeat.children).toHaveLength(2);
    expect(repeat.children.every((child) => child.props.some((prop) => prop.label === 'placement.type' && prop.value === 'PACK'))).toBe(true);
    expect(projection).toMatchObject({ phase: 'READY', outcome: 'PROJECTED' });
    expect(projection.occurrences).toHaveLength(4);
    expect(flattenDesignerTree(result.tree)).toHaveLength(4);
  });

  it('preserves authored item content and exposes a repair state when the source schema changes', () => {
    const loaded = designerReducer(selectedState(), { type: 'load-repeat-demo', preset: 'scalar-tags' });
    const repeat = findNode(loaded.tree, loaded.selectedNodeId)!;
    const childIdsBefore = repeat.children.map((child) => child.id);
    const switched = designerReducer(loaded, {
      type: 'update-prop',
      nodeId: repeat.id,
      label: 'items',
      value: 'context(invocation, /offers)',
    });
    const updated = findNode(switched.tree, repeat.id)!;

    expect(updated.children.map((child) => child.id)).toEqual(childIdsBefore);
    expect(projectPrototypeRepeat(updated, switched.boxes, 'values').phase).toBe('NEEDS_REPAIR');
  });

  it('inserts a compatible template as a PACK child with the current loop context', () => {
    const loaded = designerReducer(selectedState(), { type: 'load-repeat-demo', preset: 'reference-offers' });
    const repeat = findNode(loaded.tree, loaded.selectedNodeId)!;
    const result = designerReducer(loaded, {
      type: 'insert-node',
      kind: 'templateUse',
      templateId: childTemplateIds.offerCard,
      parentId: repeat.id,
    });
    const inserted = findNode(result.tree, result.selectedNodeId)!;

    expect(inserted.props.find((prop) => prop.label === 'placement.type')?.value).toBe('PACK');
    expect(inserted.props.find((prop) => prop.label === 'contextSelector')?.value).toContain(repeat.loopId);
  });

  it('replaces the authored item with the compatible template selected by the compact setup', () => {
    const loaded = designerReducer(selectedState(), { type: 'load-repeat-demo', preset: 'scalar-tags' });
    const repeat = findNode(loaded.tree, loaded.selectedNodeId)!;
    const previousChildIds = new Set(repeat.children.map((child) => child.id));
    const result = designerReducer(loaded, {
      type: 'set-repeat-template',
      nodeId: repeat.id,
      templateId: childTemplateIds.tagPill,
    });
    const updated = findNode(result.tree, repeat.id)!;
    const template = updated.children[0]!;

    expect(updated.children).toHaveLength(1);
    expect(template.kind).toBe('templateUse');
    expect(template.props.find((prop) => prop.label === 'templateRef.templateId')?.value).toBe(childTemplateIds.tagPill);
    expect(template.props.find((prop) => prop.label === 'contextSelector')?.value).toContain(repeat.loopId);
    expect(result.boxes.some((box) => previousChildIds.has(box.nodeId))).toBe(false);
    expect(projectPrototypeRepeat(updated, result.boxes, 'values').phase).toBe('READY');
  });
});

describe('designerReducer standalone TemplateUse', () => {
  it('configures a direct StaticSchema reference as property then compatible template', () => {
    const inserted = designerReducer(selectedState(), { type: 'insert-node', kind: 'templateUse', preset: 'templateUse' });
    const nodeId = inserted.selectedNodeId;
    const emptyNode = findNode(inserted.tree, nodeId)!;
    const withSource = designerReducer(inserted, { type: 'set-template-use-context', nodeId, sourceId: 'brand' });
    const configured = designerReducer(withSource, {
      type: 'set-template-use-template',
      nodeId,
      templateId: childTemplateIds.brandBadge,
    });
    const node = findNode(configured.tree, nodeId)!;

    expect(emptyNode.props.find((prop) => prop.label === 'contextSelector')?.value).toBe('');
    expect(node.props.find((prop) => prop.label === 'contextSelector')?.value).toBe('context(invocation, /brand)');
    expect(node.props.find((prop) => prop.label === 'templateRef.templateId')?.value).toBe(childTemplateIds.brandBadge);
    expect(node.name).toContain('品牌角标');
  });

  it('keeps arrays out, marks scalar contexts as proposals, and clears a template when the property changes', () => {
    expect(templateUseContextSources.map((source) => source.pointer)).not.toEqual(expect.arrayContaining(['/tags', '/offers']));
    const price = templateUseContextSources.find((source) => source.id === 'price')!;
    const decimalTemplate = nestedTemplates.find((template) => template.compatibilityKey === price.compatibilityKey)!;
    expect(price.kind).toBe('SCALAR_PROPOSAL');
    expect(decimalTemplate.proposal).toBe(true);

    const inserted = designerReducer(selectedState(), { type: 'insert-node', kind: 'templateUse', preset: 'templateUse' });
    const nodeId = inserted.selectedNodeId;
    const withPrice = designerReducer(inserted, { type: 'set-template-use-context', nodeId, sourceId: price.id });
    const withTemplate = designerReducer(withPrice, { type: 'set-template-use-template', nodeId, templateId: decimalTemplate.id });
    const changed = designerReducer(withTemplate, { type: 'set-template-use-context', nodeId, sourceId: 'title' });
    const node = findNode(changed.tree, nodeId)!;

    expect(node.props.find((prop) => prop.label === 'contextSelector')?.value).toBe('context(invocation, /title)');
    expect(node.props.find((prop) => prop.label === 'templateRef.templateId')?.value).toBe('');
  });
});

describe('designerReducer DesignDSL definitions', () => {
  it('upserts authored definitions in the current template draft', () => {
    const authored: DesignerDefinition = {
      id: '4b000000-0000-4000-8000-000000000001',
      name: 'memberLabels',
      kind: 'CUSTOM',
      valueType: 'list<text>',
      exposure: 'PUBLIC',
      defaultValue: '["会员", "专享"]',
      detail: 'typed literal default',
    };

    const created = designerReducer(selectedState(), { type: 'save-definition', definition: authored });
    const updated = designerReducer(created, {
      type: 'save-definition',
      definition: { ...authored, name: 'memberBadges', exposure: 'PRIVATE' },
    });

    expect(created.definitions.at(-1)).toEqual(authored);
    expect(updated.definitions.filter((definition) => definition.id === authored.id)).toHaveLength(1);
    expect(updated.definitions.find((definition) => definition.id === authored.id)).toMatchObject({ name: 'memberBadges', exposure: 'PRIVATE' });
    expect(updated.notice).toContain('DesignDSL definitions[]');
  });

  it('makes a newly authored scalar list immediately available to Repeat projection', () => {
    const authored: DesignerDefinition = {
      id: '4b000000-0000-4000-8000-000000000002',
      name: 'priceSteps',
      kind: 'CUSTOM',
      valueType: 'list<decimal>',
      exposure: 'PRIVATE',
      defaultValue: '[9.9, 19.9, 29.9]',
      detail: 'decimal list',
    };
    const withDefinition = designerReducer(selectedState(), { type: 'save-definition', definition: authored });
    const loaded = designerReducer(withDefinition, { type: 'load-repeat-demo', preset: 'scalar-tags' });
    const repeat = findNode(loaded.tree, loaded.selectedNodeId)!;
    const switched = designerReducer(loaded, {
      type: 'update-prop',
      nodeId: repeat.id,
      label: 'items',
      value: `definition(${authored.id})`,
    });
    const updated = findNode(switched.tree, repeat.id)!;
    const projection = projectPrototypeRepeat(updated, switched.boxes, 'values', switched.definitions);

    expect(projection.sourceProof).toMatchObject({ sourceGroup: 'CUSTOM', itemStaticSchemaRef: 'system-basic-decimal@v1' });
    expect(projection.occurrences.map((occurrence) => occurrence.value)).toEqual([9.9, 19.9, 29.9]);
  });
});
