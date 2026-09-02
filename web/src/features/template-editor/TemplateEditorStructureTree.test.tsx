// @vitest-environment happy-dom

import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { parse } from 'lossless-json';
import { useState } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import type { EditorNodeProjection } from './template-editor-model';
import { TemplateEditorStructureTree } from './TemplateEditorStructureTree';

afterEach(cleanup);

describe('Template Editor Structure tree authoring interactions', () => {
  it('shows finite Canvas dimensions parsed as lossless authored numbers', () => {
    const projected = nodes();
    projected[0] = {
      ...projected[0]!,
      value: parse('{"nodeId":"canvas","kind":"canvas","displayName":"画布","widthMm":210,"heightMm":297}') as Record<string, unknown>,
    };

    renderTree({ nodes: projected });

    expect(screen.getByRole('treeitem', { name: /canvas · 210×297 mm/ })).toBeTruthy();
  });

  it('keeps navigation available while disabling every mutation affordance when locked', () => {
    const onSelectNode = vi.fn();
    const onRenameNode = vi.fn();
    const onMoveNode = vi.fn();
    const onReorderNode = vi.fn();
    const onDeleteNode = vi.fn();
    renderTree({
      selectedNodeId: 'canvas',
      disabled: true,
      onSelectNode,
      onRenameNode,
      onMoveNode,
      onReorderNode,
      onDeleteNode,
    });

    fireEvent.click(screen.getByRole('button', { name: '折叠内容区子级' }));
    expect(screen.queryByRole('treeitem', { name: /底色/ })).toBeNull();
    fireEvent.change(screen.getByRole('searchbox', { name: '搜索 DesignDSL 结构' }), {
      target: { value: '底色' },
    });
    const rect = screen.getByRole('treeitem', { name: /底色/ });
    fireEvent.click(rect);
    expect(onSelectNode).toHaveBeenCalledWith('rect');
    fireEvent.doubleClick(rect);
    fireEvent.keyDown(rect, { key: 'F2' });
    expect(screen.queryByRole('textbox', { name: '重命名 底色' })).toBeNull();
    expect(rect.getAttribute('draggable')).toBe('false');

    fireEvent.contextMenu(screen.getByRole('treeitem', { name: /底色/ }));
    const mutationItems = screen.getAllByRole('menuitem');
    expect(mutationItems.length).toBeGreaterThan(0);
    expect(mutationItems.every((item) => item.hasAttribute('disabled'))).toBe(true);
    fireEvent.click(screen.getByRole('menuitem', { name: '置于顶层' }));
    fireEvent.click(screen.getByRole('menuitem', { name: '删除' }));
    expect(onRenameNode).not.toHaveBeenCalled();
    expect(onMoveNode).not.toHaveBeenCalled();
    expect(onReorderNode).not.toHaveBeenCalled();
    expect(onDeleteNode).not.toHaveBeenCalled();
  });

  it('cancels an in-flight rename or move panel when the editor becomes locked', () => {
    const onRenameNode = vi.fn();
    const onMoveNode = vi.fn();
    const props: Parameters<typeof TemplateEditorStructureTree>[0] = {
      nodes: nodes(),
      selectedNodeId: 'rect',
      onSelectNode: vi.fn(),
      onRenameNode,
      onMoveNode,
    };
    const view = render(<TemplateEditorStructureTree {...props} />);
    const source = screen.getByRole('treeitem', { name: /底色/ });

    fireEvent.keyDown(source, { key: 'F2' });
    expect(screen.getByRole('textbox', { name: '重命名 底色' })).toBeTruthy();
    view.rerender(<TemplateEditorStructureTree {...props} disabled />);
    expect(screen.queryByRole('textbox', { name: '重命名 底色' })).toBeNull();
    expect(onRenameNode).not.toHaveBeenCalled();

    view.rerender(<TemplateEditorStructureTree {...props} />);
    fireEvent.keyDown(screen.getByRole('treeitem', { name: /底色/ }), {
      key: 'F10',
      shiftKey: true,
    });
    fireEvent.click(screen.getByRole('menuitem', { name: '移动…' }));
    expect(screen.getByRole('dialog', { name: '移动 底色' })).toBeTruthy();
    expect(screen.getByText('之前/之后与目标同级；移入容器则成为目标的最后一个子级。'))
      .toBeTruthy();
    view.rerender(<TemplateEditorStructureTree {...props} disabled />);
    expect(screen.queryByRole('dialog', { name: '移动 底色' })).toBeNull();
    expect(onMoveNode).not.toHaveBeenCalled();
  });

  it('renames a non-root row from double click or F2 and cancels with Escape', () => {
    const onRenameNode = vi.fn();
    renderTree({ onRenameNode });

    const rect = screen.getByRole('treeitem', { name: /底色/ });
    fireEvent.doubleClick(rect);
    const firstRename = screen.getByRole('textbox', { name: '重命名 底色' });
    fireEvent.change(firstRename, { target: { value: '主底色' } });
    fireEvent.keyDown(firstRename, { key: 'Enter' });
    expect(onRenameNode).toHaveBeenCalledWith('rect', '主底色');

    fireEvent.keyDown(rect, { key: 'F2' });
    const cancelledRename = screen.getByRole('textbox', { name: '重命名 底色' });
    fireEvent.change(cancelledRename, { target: { value: '不要保存' } });
    fireEvent.keyDown(cancelledRename, { key: 'Escape' });
    expect(onRenameNode).toHaveBeenCalledTimes(1);

    fireEvent.keyDown(rect, { key: 'F2' });
    const blurredRename = screen.getByRole('textbox', { name: '重命名 底色' });
    fireEvent.change(blurredRename, { target: { value: '模糊提交' } });
    fireEvent.blur(blurredRename);
    expect(onRenameNode).toHaveBeenLastCalledWith('rect', '模糊提交');

    fireEvent.keyDown(screen.getByRole('treeitem', { name: /画布/ }), { key: 'F2' });
    expect(screen.queryByRole('textbox', { name: /重命名 画布/ })).toBeNull();
  });

  it('reports before, into and after tree drops while exposing the active drop affordance', () => {
    const onMoveNode = vi.fn();
    renderTree({ onMoveNode });
    const source = screen.getByRole('treeitem', { name: /底色/ });
    const container = screen.getByRole('treeitem', { name: /标签堆叠/ });
    const leaf = screen.getByRole('treeitem', { name: /标签背景/ });
    vi.spyOn(container, 'getBoundingClientRect').mockReturnValue(bounds(100, 44));
    vi.spyOn(leaf, 'getBoundingClientRect').mockReturnValue(bounds(200, 44));

    const intoTransfer = dragTransfer('rect');
    fireDrag(source, 'dragstart', intoTransfer);
    fireDrag(container, 'dragover', intoTransfer, 122);
    expect(container.dataset.dropPosition).toBe('into');
    fireDrag(container, 'drop', intoTransfer, 122);
    expect(onMoveNode).toHaveBeenLastCalledWith('rect', 'stack', 'into');
    expect(container.dataset.dropPosition).toBeUndefined();

    const edgeTransfer = dragTransfer('rect');
    fireDrag(source, 'dragstart', edgeTransfer);
    fireDrag(leaf, 'dragover', edgeTransfer, 202);
    expect(leaf.dataset.dropPosition).toBe('before');
    fireDrag(leaf, 'dragover', edgeTransfer, 242);
    expect(leaf.dataset.dropPosition).toBe('after');
    fireDrag(leaf, 'drop', edgeTransfer, 242);
    expect(onMoveNode).toHaveBeenLastCalledWith('rect', 'nested-rect', 'after');
  });

  it('marks every DesignDSL container without offering unsupported move-into targets', () => {
    const onMoveNode = vi.fn();
    renderTree({ nodes: nodesWithGrid(), onMoveNode });
    const source = screen.getByRole('treeitem', { name: /底色/ });
    const grid = screen.getByRole('treeitem', { name: /数据网格/ });
    const gridChild = screen.getByRole('treeitem', { name: /网格单元/ });

    expect(grid.dataset.container).toBe('true');
    expect(grid.classList.contains('is-container')).toBe(true);
    expect(source.dataset.container).toBeUndefined();

    vi.spyOn(grid, 'getBoundingClientRect').mockReturnValue(bounds(100, 44));
    const transfer = dragTransfer('rect');
    fireDrag(source, 'dragstart', transfer);
    fireDrag(grid, 'dragover', transfer, 122);
    expect(grid.dataset.dropPosition).toBe('after');
    expect(grid.dataset.dropPosition).not.toBe('into');

    vi.spyOn(gridChild, 'getBoundingClientRect').mockReturnValue(bounds(100, 44));
    fireDrag(gridChild, 'dragover', transfer, 122);
    expect(gridChild.dataset.dropPosition).toBeUndefined();

    fireEvent.keyDown(source, { key: 'F10', shiftKey: true });
    fireEvent.click(screen.getByRole('menuitem', { name: '移动…' }));
    fireEvent.change(screen.getByRole('combobox', { name: '目标节点' }), {
      target: { value: 'grid-child' },
    });
    expect(screen.getByRole('radio', { name: '之前' }).hasAttribute('disabled')).toBe(true);
    expect(screen.getByRole('radio', { name: '移入容器' }).hasAttribute('disabled')).toBe(true);
    expect(screen.getByRole('radio', { name: '之后' }).hasAttribute('disabled')).toBe(true);
    expect(onMoveNode).not.toHaveBeenCalled();
  });

  it('moves before, into or after a chosen target from a keyboard-accessible menu', () => {
    const onMoveNode = vi.fn();
    renderTree({ onMoveNode });
    const source = screen.getByRole('treeitem', { name: /底色/ });

    fireEvent.keyDown(source, { key: 'F10', shiftKey: true });
    fireEvent.click(screen.getByRole('menuitem', { name: '移动…' }));
    expect(screen.getByRole('dialog', { name: '移动 底色' })).toBeTruthy();
    const target = screen.getByRole('combobox', { name: '目标节点' });
    expect([...target.querySelectorAll('option')].map((option) => option.value))
      .not.toContain('rect');

    fireEvent.change(target, { target: { value: 'stack' } });
    fireEvent.click(screen.getByRole('radio', { name: '移入容器' }));
    fireEvent.click(screen.getByRole('button', { name: '确认移动' }));
    expect(onMoveNode).toHaveBeenLastCalledWith('rect', 'stack', 'into');

    fireEvent.keyDown(source, { key: 'F10', shiftKey: true });
    fireEvent.click(screen.getByRole('menuitem', { name: '移动…' }));
    fireEvent.change(screen.getByRole('combobox', { name: '目标节点' }), {
      target: { value: 'nested-rect' },
    });
    expect(screen.getByRole('radio', { name: '移入容器' }).hasAttribute('disabled')).toBe(true);
    fireEvent.click(screen.getByRole('radio', { name: '之后' }));
    fireEvent.click(screen.getByRole('button', { name: '确认移动' }));
    expect(onMoveNode).toHaveBeenLastCalledWith('rect', 'nested-rect', 'after');

    fireEvent.keyDown(source, { key: 'F10', shiftKey: true });
    fireEvent.click(screen.getByRole('menuitem', { name: '移动…' }));
    fireEvent.change(screen.getByRole('combobox', { name: '目标节点' }), {
      target: { value: 'frame' },
    });
    fireEvent.click(screen.getByRole('radio', { name: '之前' }));
    fireEvent.click(screen.getByRole('button', { name: '确认移动' }));
    expect(onMoveNode).toHaveBeenLastCalledWith('rect', 'frame', 'before');
    expect(onMoveNode).toHaveBeenCalledTimes(3);

    fireEvent.keyDown(source, { key: 'F10', shiftKey: true });
    fireEvent.click(screen.getByRole('menuitem', { name: '移动…' }));
    fireEvent.keyDown(window, { key: 'Escape' });
    expect(screen.queryByRole('dialog', { name: '移动 底色' })).toBeNull();
    expect(onMoveNode).toHaveBeenCalledTimes(3);
  });

  it('uses one keyboard-dismissable context menu for sibling order and delete actions', () => {
    const onSelectNode = vi.fn();
    const onReorderNode = vi.fn();
    const onDeleteNode = vi.fn();
    renderTree({ onSelectNode, onReorderNode, onDeleteNode });
    const rect = screen.getByRole('treeitem', { name: /底色/ });

    fireEvent.contextMenu(rect, { clientX: 120, clientY: 160 });
    expect(onSelectNode).toHaveBeenCalledWith('rect');
    expect(screen.getAllByRole('menu')).toHaveLength(1);
    expect(screen.getByRole('menuitem', { name: '置于底层' }).hasAttribute('disabled')).toBe(true);
    fireEvent.click(screen.getByRole('menuitem', { name: '置于顶层' }));
    expect(onReorderNode).toHaveBeenCalledWith('rect', 'front');
    expect(screen.queryByRole('menu')).toBeNull();

    fireEvent.contextMenu(rect, { clientX: 120, clientY: 160 });
    fireEvent.click(screen.getByRole('menuitem', { name: '删除' }));
    expect(onDeleteNode).toHaveBeenCalledWith('rect');

    fireEvent.contextMenu(rect, { clientX: 120, clientY: 160 });
    fireEvent.keyDown(window, { key: 'Escape' });
    expect(screen.queryByRole('menu')).toBeNull();

    fireEvent.contextMenu(screen.getByRole('treeitem', { name: /画布/ }));
    expect(screen.getByRole('menuitem', { name: '删除' }).hasAttribute('disabled')).toBe(true);
  });

  it('returns focus to the exact treeitem after menu actions and layered Escape dismissal', () => {
    const onReorderNode = vi.fn();
    renderTree({ onReorderNode, onMoveNode: vi.fn() });
    const source = screen.getByRole('treeitem', { name: /底色/ });

    source.focus();
    fireEvent.keyDown(source, { key: 'F10', shiftKey: true });
    expect(document.activeElement).toBe(screen.getByRole('menuitem', { name: '移动…' }));
    fireEvent.click(screen.getByRole('menuitem', { name: '移动…' }));
    expect(document.activeElement).toBe(screen.getByRole('combobox', { name: '目标节点' }));

    fireEvent.click(screen.getByRole('button', { name: '取消' }));
    expect(screen.queryByRole('dialog', { name: '移动 底色' })).toBeNull();
    expect(screen.getByRole('menu', { name: '底色 操作' })).toBeTruthy();
    expect(document.activeElement).toBe(screen.getByRole('menuitem', { name: '移动…' }));

    fireEvent.click(screen.getByRole('menuitem', { name: '移动…' }));
    expect(document.activeElement).toBe(screen.getByRole('combobox', { name: '目标节点' }));

    fireEvent.keyDown(window, { key: 'Escape' });
    expect(screen.queryByRole('dialog', { name: '移动 底色' })).toBeNull();
    expect(screen.getByRole('menu', { name: '底色 操作' })).toBeTruthy();
    expect(document.activeElement).toBe(screen.getByRole('menuitem', { name: '移动…' }));

    fireEvent.keyDown(window, { key: 'Escape' });
    expect(screen.queryByRole('menu')).toBeNull();
    expect(document.activeElement).toBe(source);

    fireEvent.keyDown(source, { key: 'ContextMenu' });
    fireEvent.click(screen.getByRole('menuitem', { name: '置于顶层' }));
    expect(onReorderNode).toHaveBeenCalledWith('rect', 'front');
    expect(document.activeElement).toBe(source);

    fireEvent.contextMenu(source, { clientX: 120, clientY: 160 });
    fireEvent.pointerDown(document.body);
    expect(screen.queryByRole('menu')).toBeNull();
    expect(document.activeElement).toBe(source);
  });

  it('falls back to the tree when a menu action removes its trigger row', () => {
    function DeleteHarness() {
      const [projected, setProjected] = useState(nodes);
      return <TemplateEditorStructureTree
        nodes={projected}
        selectedNodeId="rect"
        onSelectNode={vi.fn()}
        onDeleteNode={(nodeId) => setProjected((current) => current
          .filter((candidate) => candidate.nodeId !== nodeId)
          .map((candidate) => candidate.nodeId === 'frame'
            ? { ...candidate, childCount: 1 }
            : candidate))}
      />;
    }
    render(<DeleteHarness />);
    const source = screen.getByRole('treeitem', { name: /底色/ });

    source.focus();
    fireEvent.keyDown(source, { key: 'ContextMenu' });
    fireEvent.click(screen.getByRole('menuitem', { name: '删除' }));

    expect(source.isConnected).toBe(false);
    expect(document.activeElement).toBe(screen.getByRole('treeitem', { name: /画布/ }));
  });
});

function renderTree(overrides: Partial<Parameters<typeof TemplateEditorStructureTree>[0]> = {}) {
  const props: Parameters<typeof TemplateEditorStructureTree>[0] = {
    nodes: nodes(),
    selectedNodeId: 'rect',
    onSelectNode: vi.fn(),
    ...overrides,
  };
  return render(<TemplateEditorStructureTree {...props} />);
}

function nodes(): EditorNodeProjection[] {
  return [
    node('canvas', 'canvas', '画布', 0, 1),
    node('frame', 'frame', '内容区', 1, 2),
    node('rect', 'rect', '底色', 2, 0),
    node('stack', 'stack', '标签堆叠', 2, 1),
    node('nested-rect', 'rect', '标签背景', 3, 0),
  ];
}

function nodesWithGrid(): EditorNodeProjection[] {
  return [
    node('canvas', 'canvas', '画布', 0, 1),
    node('frame', 'frame', '内容区', 1, 3),
    node('rect', 'rect', '底色', 2, 0),
    node('stack', 'stack', '标签堆叠', 2, 1),
    node('nested-rect', 'rect', '标签背景', 3, 0),
    node('grid', 'grid', '数据网格', 2, 1),
    node('grid-child', 'rect', '网格单元', 3, 0),
  ];
}

function dragTransfer(nodeId: string) {
  const values = new Map<string, string>([
    ['application/x-renderweave-template-node', nodeId],
    ['text/plain', nodeId],
  ]);
  return {
    types: [...values.keys()],
    effectAllowed: 'move',
    dropEffect: 'move',
    setData: vi.fn((type: string, value: string) => values.set(type, value)),
    getData: vi.fn((type: string) => values.get(type) ?? ''),
  };
}

function fireDrag(
  target: Element,
  type: 'dragstart' | 'dragover' | 'drop',
  dataTransfer: ReturnType<typeof dragTransfer>,
  clientY = 0,
) {
  const event = new Event(type, { bubbles: true, cancelable: true });
  Object.defineProperties(event, {
    clientY: { value: clientY },
    dataTransfer: { value: dataTransfer },
  });
  fireEvent(target, event);
}

function bounds(top: number, height: number): DOMRect {
  return {
    x: 0,
    y: top,
    top,
    left: 0,
    width: 280,
    height,
    right: 280,
    bottom: top + height,
    toJSON: () => ({}),
  };
}

function node(
  nodeId: string,
  kind: string,
  displayName: string,
  depth: number,
  childCount: number,
): EditorNodeProjection {
  return {
    nodeId,
    kind,
    displayName,
    depth,
    childCount,
    value: { nodeId, kind, displayName },
  };
}
