// @vitest-environment happy-dom

import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { parse } from 'lossless-json';
import { afterEach, describe, expect, it, vi } from 'vitest';

import type { EditorNodeProjection } from './template-editor-model';
import { TemplateEditorInspector } from './TemplateEditorInspector';

afterEach(cleanup);

describe('Template Editor Inspector', () => {
  it('shows only relevant property groups in the professional authored order', () => {
    const view = renderInspector(rectNode());

    expect(groupOrder(view.container)).toEqual([
      'content', 'position-size', 'appearance', 'advanced',
    ]);
    expect(screen.getByLabelText('名称')).toBeTruthy();
    expect(screen.getByLabelText('X 坐标')).toBeTruthy();
    expect(screen.getByLabelText('宽度')).toBeTruthy();
    expect(screen.getByLabelText('填充颜色')).toBeTruthy();
    expect(screen.queryByLabelText('排列方向')).toBeNull();

    view.rerender(<TemplateEditorInspector
      node={canvasNode()}
      disabled={false}
      onCommand={vi.fn()}
    />);
    expect(groupOrder(view.container)).toEqual(['position-size', 'appearance', 'advanced']);
    expect(screen.queryByLabelText('名称')).toBeNull();
    expect(screen.getByLabelText('画布宽度')).toBeTruthy();
    expect(screen.getByLabelText('画布高度')).toBeTruthy();
    expect(screen.getByLabelText('背景颜色')).toBeTruthy();
  });

  it('keeps invalid local drafts and emits rename or one atomic fixed geometry command', () => {
    const onCommand = vi.fn();
    renderInspector(rectNode(), { onCommand });

    const name = screen.getByLabelText('名称');
    fireEvent.change(name, { target: { value: '   ' } });
    fireEvent.blur(name);
    expect(name.getAttribute('aria-invalid')).toBe('true');
    expect((name as HTMLInputElement).value).toBe('   ');
    expect(screen.getByText('名称必须是 1–128 个有效字符。')).toBeTruthy();
    expect(onCommand).not.toHaveBeenCalled();

    fireEvent.change(name, { target: { value: '  主背景  ' } });
    fireEvent.keyDown(name, { key: 'Enter' });
    expect(onCommand).toHaveBeenLastCalledWith({
      operation: 'rename', nodeId: 'rect', displayName: '主背景',
    });

    const width = screen.getByLabelText('宽度');
    fireEvent.change(width, { target: { value: '0' } });
    fireEvent.blur(width);
    expect((width as HTMLInputElement).value).toBe('0');
    expect(width.getAttribute('aria-invalid')).toBe('true');
    expect(onCommand).toHaveBeenCalledTimes(1);

    fireEvent.change(width, { target: { value: '120.5' } });
    fireEvent.keyDown(width, { key: 'Enter' });
    expect(onCommand).toHaveBeenLastCalledWith({
      operation: 'set-geometry',
      nodeId: 'rect',
      geometry: { xMm: 12, yMm: 18, widthMm: 120.5, heightMm: 40 },
    });
    expect(onCommand).toHaveBeenCalledTimes(2);
  });

  it('shares Java trim and Unicode-scalar name semantics while keeping concise Chinese feedback', () => {
    const onCommand = vi.fn();
    renderInspector(rectNode(), { onCommand });
    const name = screen.getByLabelText('名称');

    fireEvent.change(name, { target: { value: '\ud800' } });
    fireEvent.blur(name);
    expect(screen.getByText('名称必须是 1–128 个有效字符。')).toBeTruthy();
    expect(onCommand).not.toHaveBeenCalled();

    fireEvent.change(name, { target: { value: '\u00a0名称\u00a0' } });
    fireEvent.keyDown(name, { key: 'Enter' });
    expect(onCommand).toHaveBeenCalledWith({
      operation: 'rename', nodeId: 'rect', displayName: '\u00a0名称\u00a0',
    });
  });

  it('edits Stack layout facts without presenting absolute geometry', () => {
    const onCommand = vi.fn();
    const view = renderInspector(stackNode(), { onCommand });

    expect(groupOrder(view.container)).toEqual([
      'content', 'layout-constraints', 'appearance', 'advanced',
    ]);
    expect(screen.queryByLabelText('X 坐标')).toBeNull();

    fireEvent.change(screen.getByLabelText('排列方向'), { target: { value: 'ROW' } });
    expect(onCommand).toHaveBeenLastCalledWith({
      operation: 'set-property', nodeId: 'stack', property: 'direction', value: 'ROW',
    });

    fireEvent.click(screen.getByRole('checkbox', { name: /裁剪溢出内容/ }));
    expect(onCommand).toHaveBeenLastCalledWith({
      operation: 'set-property', nodeId: 'stack', property: 'clipContent', value: true,
    });

    const gap = screen.getByLabelText('间距');
    fireEvent.change(gap, { target: { value: '6.25' } });
    fireEvent.blur(gap);
    expect(onCommand).toHaveBeenLastCalledWith({
      operation: 'set-property', nodeId: 'stack', property: 'gapMm', value: 6.25,
    });
  });

  it('shows exact lossless numeric tokens without emitting unchanged authored commands', () => {
    const onCommand = vi.fn();
    const value = parse(JSON.stringify({
      nodeId: 'rect',
      kind: 'rect',
      displayName: '精确矩形',
      bindings: [],
      fill: { color: '#2563EBFF' },
      placement: {
        type: 'ABSOLUTE', xMm: 12.5, yMm: 18,
        widthMode: 'FIXED', widthMm: 80.25,
        heightMode: 'FIXED', heightMm: 40,
      },
    })) as Record<string, unknown>;
    renderInspector(node('rect', '精确矩形', value), { onCommand });

    expect((screen.getByLabelText('X 坐标') as HTMLInputElement).value).toBe('12.5');
    expect((screen.getByLabelText('宽度') as HTMLInputElement).value).toBe('80.25');
    fireEvent.blur(screen.getByLabelText('X 坐标'));
    fireEvent.blur(screen.getByLabelText('宽度'));
    expect(onCommand).not.toHaveBeenCalled();
  });

  it('lists only existing authored bindings on the Bindings tab', () => {
    const node = rectNode({
      bindings: [
        {
          bindingId: 'binding-opacity',
          targetPropertyRef: { rootPropertyId: 'opacity', selectors: [] },
          source: { kind: 'definition', definitionId: 'price-color' },
        },
        {
          bindingId: 'binding-fill',
          targetPropertyRef: {
            rootPropertyId: 'fill',
            selectors: [{ kind: 'member', name: 'color' }],
          },
          source: { kind: 'context', domain: 'invocation', pointer: '/theme/accent' },
        },
      ],
    });
    renderInspector(node);

    const propertiesTab = screen.getByRole('tab', { name: '属性' });
    propertiesTab.focus();
    fireEvent.keyDown(propertiesTab, { key: 'ArrowRight' });
    expect(screen.getByRole('tab', { name: /绑定/ }).getAttribute('aria-selected')).toBe('true');
    const list = screen.getByRole('list', { name: '已有绑定' });
    expect(within(list).getAllByRole('listitem')).toHaveLength(2);
    expect(within(list).getByText('opacity')).toBeTruthy();
    expect(within(list).getByText('fill.color')).toBeTruthy();
    expect(within(list).getByText('定义 · price-color')).toBeTruthy();
    expect(within(list).getByText('上下文 · invocation / /theme/accent')).toBeTruthy();
    expect(screen.queryByText(/可绑定属性/)).toBeNull();
    expect(within(list).queryAllByRole('button')).toHaveLength(0);
  });

  it('renders an honest empty selection and disables every authored control', () => {
    const onCommand = vi.fn();
    const view = render(<TemplateEditorInspector
      disabled={false}
      onCommand={onCommand}
    />);
    expect(screen.getByText('未选择元素')).toBeTruthy();

    view.rerender(<TemplateEditorInspector
      node={stackNode()}
      disabled
      onCommand={onCommand}
    />);
    const controls = view.container.querySelectorAll<HTMLInputElement | HTMLSelectElement>(
      'input, select',
    );
    expect(controls.length).toBeGreaterThan(0);
    expect([...controls].every((control) => control.disabled)).toBe(true);
  });
});

function renderInspector(
  node: EditorNodeProjection,
  overrides: Partial<Parameters<typeof TemplateEditorInspector>[0]> = {},
) {
  return render(<TemplateEditorInspector
    node={node}
    disabled={false}
    onCommand={vi.fn()}
    {...overrides}
  />);
}

function groupOrder(container: HTMLElement): string[] {
  return Array.from(container.querySelectorAll<HTMLElement>('[data-inspector-group]'))
    .map((group) => group.dataset.inspectorGroup ?? '');
}

function rectNode(members: Record<string, unknown> = {}): EditorNodeProjection {
  return node('rect', '背景矩形', {
    displayName: '背景矩形',
    bindings: [],
    fill: { color: '#2563EBFF' },
    placement: {
      type: 'ABSOLUTE', xMm: 12, yMm: 18,
      widthMode: 'FIXED', widthMm: 80,
      heightMode: 'FIXED', heightMm: 40,
    },
    ...members,
  });
}

function stackNode(): EditorNodeProjection {
  return node('stack', '价格堆叠', {
    displayName: '价格堆叠',
    bindings: [],
    children: [],
    direction: 'COLUMN',
    gapMm: 4,
    clipContent: false,
    fill: { color: '#FFFFFFFF' },
    placement: {
      type: 'STACK',
      widthMode: 'FIXED', widthMm: 80,
      heightMode: 'FIXED', heightMm: 40,
    },
  });
}

function canvasNode(): EditorNodeProjection {
  return node('canvas', '画布', {
    displayName: '画布',
    bindings: [],
    children: [],
    widthMm: 210,
    heightMm: 297,
    backgroundColor: '#FFFFFFFF',
  }, 1);
}

function node(
  kind: string,
  displayName: string,
  value: Record<string, unknown>,
  childCount = 0,
): EditorNodeProjection {
  return {
    nodeId: kind,
    kind,
    displayName,
    depth: kind === 'canvas' ? 0 : 1,
    childCount,
    value: { nodeId: kind, kind, ...value },
  };
}
