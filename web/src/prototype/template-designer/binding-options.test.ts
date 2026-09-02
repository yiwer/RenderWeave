import { describe, expect, it } from 'vitest';

import { bindingTargetValueType, prototypeBindingSourceOptions } from './binding-options';
import { findNode, initialDesignerState, nodeIds, type DesignerNode, type NodeKind } from './model';

function stateWithLeaf(kind: NodeKind, props: DesignerNode['props']) {
  const leaf: DesignerNode = {
    id: `test-${kind}`,
    kind,
    name: kind,
    detail: '',
    flags: [],
    props,
    children: [],
  };
  return {
    state: {
      ...initialDesignerState,
      tree: { ...initialDesignerState.tree, children: [leaf] },
    },
    leaf,
  };
}

describe('prototypeBindingSourceOptions', () => {
  it('offers only typed invocation sources outside a Repeat lexical domain', () => {
    const options = prototypeBindingSourceOptions(initialDesignerState, nodeIds.titleText, 'runs[0].text');

    expect(options.some((option) => option.source === '/title')).toBe(true);
    expect(options.every((option) => option.valueType === 'text')).toBe(true);
    expect(options.some((option) => option.group === '循环域')).toBe(false);
    expect(options.some((option) => option.source.includes('loopIndex('))).toBe(false);
  });

  it('adds only the concrete ancestor Repeat item sources for a descendant', () => {
    const repeat = findNode(initialDesignerState.tree, nodeIds.tagLoop);
    expect(repeat?.kind).toBe('repeat');
    const textChild = repeat?.children.find((child) => child.kind === 'text');
    expect(textChild).toBeDefined();

    const options = prototypeBindingSourceOptions(initialDesignerState, textChild!.id, 'runs[0].text');

    expect(options.some((option) => option.source === `context(loop ${repeat!.loopId}, /value)`)).toBe(true);
    expect(options.some((option) => option.source.startsWith('loopIndex('))).toBe(false);
    expect(options.every((option) => option.valueType === 'text')).toBe(true);
  });

  it('exposes loopIndex only for decimal targets inside that Repeat', () => {
    const repeat = findNode(initialDesignerState.tree, nodeIds.tagLoop)!;
    const child = repeat.children[0]!;
    const options = prototypeBindingSourceOptions(initialDesignerState, child.id, 'opacity');

    expect(options.some((option) => option.source === `loopIndex(${repeat.loopId})`)).toBe(true);
    expect(options.every((option) => option.valueType === 'decimal')).toBe(true);
  });

  it('keeps enum property identities distinct from text sources', () => {
    expect(bindingTargetValueType('stack', 'direction')).toBe('enum<HORIZONTAL|VERTICAL>');
    expect(prototypeBindingSourceOptions(initialDesignerState, nodeIds.rootStack, 'direction')).toEqual([]);
  });

  it.each([
    ['barcode', 'showText'],
    ['polyline', 'closed'],
  ] as const)('types %s.%s as boolean instead of falling through to decimal', (kind, propertyLabel) => {
    const { state, leaf } = stateWithLeaf(kind, [{ label: propertyLabel, value: 'true', bindable: true }]);
    const options = prototypeBindingSourceOptions(state, leaf.id, propertyLabel);

    expect(bindingTargetValueType(kind, propertyLabel)).toBe('boolean');
    expect(options.some((option) => option.source === '/promotionEnabled')).toBe(true);
    expect(options.some((option) => option.source === '/title' || option.source === '/price')).toBe(false);
    expect(options.every((option) => option.valueType === 'boolean')).toBe(true);
  });

  it('fails closed for an unknown node property identity', () => {
    expect(bindingTargetValueType('text', 'futureProperty')).toBeNull();
  });
});
