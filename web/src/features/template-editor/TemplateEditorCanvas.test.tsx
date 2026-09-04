// @vitest-environment happy-dom

import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { parse } from 'lossless-json';
import { afterEach, describe, expect, it, vi } from 'vitest';

import type {
  CanonicalDesignWorkingCopy,
  EditorNodeProjection,
} from './template-editor-model';
import type { AssetReadableResponse } from '../../api/generated';
import type { TemplateEditorAssetTransport } from './template-editor-assets';
import {
  TEMPLATE_NODE_DRAG_MIME,
  TemplateEditorCanvas,
  type TemplateEditorCanvasAssetResources,
} from './TemplateEditorCanvas';

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe('Template Editor Canvas interaction surface', () => {
  it('renders editor-only Repeat ordinals and visible structural errors outside authored nodes', () => {
    const repeat = {
      ...fixedNode('repeat', 'repeat', '循环商品', 10, 10, 40, 20),
      loopId: 'loop',
      items: { kind: 'literal', valueType: { type: 'list', items: 'text' }, value: ['a', 'b'] },
      absentPolicy: 'EMPTY',
      itemLayout: { kind: 'STACK', direction: 'COLUMN', gapMm: 0 },
      instanceLayout: { kind: 'STACK', direction: 'ROW', gapMm: 2 },
      children: [{
        ...fixedNode('repeat-child', 'rect', '循环内容', 0, 0, 5, 4),
        placement: {
          type: 'PACK', widthMode: 'FIXED', widthMm: 5,
          heightMode: 'FIXED', heightMm: 4,
        },
      }],
    };
    const designRoot: Record<string, unknown> = {
      nodeId: 'canvas', kind: 'canvas', displayName: '画布', widthMm: 100, heightMm: 80,
      bindings: [], children: [repeat],
    };
    const view = render(<TemplateEditorCanvas
      workingCopy={{
        canonicalDesignDsl: '{}',
        designDsl: {
          dslVersion: 'renderweave-design/1.0',
          expressionProfile: 'renderweave-expression/1.0',
          definitions: [], designRoot,
        },
      }}
      nodes={projectNodes(designRoot)}
      selectedNodeId="repeat"
      onSelectNode={vi.fn()}
      structuralStates={{ repeat: { kind: 'repeat', outcome: 'VALUES', count: 2 } }}
    />);

    expect(view.container.querySelectorAll('[data-template-repeat-occurrence="repeat"]')).toHaveLength(2);
    expect(view.container.querySelectorAll(
      '[data-template-canvas-authored-node][data-template-canvas-node-id="repeat-child"]',
    )).toHaveLength(1);

    view.rerender(<TemplateEditorCanvas
      workingCopy={{
        canonicalDesignDsl: '{}',
        designDsl: {
          dslVersion: 'renderweave-design/1.0',
          expressionProfile: 'renderweave-expression/1.0',
          definitions: [], designRoot,
        },
      }}
      nodes={projectNodes(designRoot)}
      selectedNodeId="repeat"
      onSelectNode={vi.fn()}
      structuralStates={{ repeat: { kind: 'repeat', outcome: 'SOURCE_ERROR' } }}
    />);
    expect(screen.getByRole('alert').textContent).toContain('循环商品');
    expect(view.container.querySelector('[data-template-repeat-occurrence]')).toBeNull();
  });

  it('projects and describes fixed geometry from the lossless canonical working copy', () => {
    const canonicalDesignDsl = JSON.stringify({
      dslVersion: 'renderweave-design/1.0',
      expressionProfile: 'renderweave-expression/1.0',
      displayName: 'Lossless canvas test',
      definitions: [],
      designRoot: {
        nodeId: 'canvas',
        kind: 'canvas',
        displayName: '画布',
        widthMm: 210,
        heightMm: 297,
        bindings: [],
        children: [fixedNode('lossless-rect', 'rect', '无损矩形', 10, 20, 30, 40)],
      },
    });
    const designDsl = parse(canonicalDesignDsl) as Record<string, unknown>;
    const designRoot = designDsl.designRoot as Record<string, unknown>;
    const view = render(<TemplateEditorCanvas
      workingCopy={{ canonicalDesignDsl, designDsl }}
      nodes={projectNodes(designRoot)}
      selectedNodeId="lossless-rect"
      onSelectNode={vi.fn()}
    />);

    const authored = view.container.querySelector<HTMLElement>(
      '[data-template-canvas-authored-node][data-template-canvas-node-id="lossless-rect"]',
    );
    expect(authored).not.toBeNull();
    expect(authored?.style.left).toBe('40px');
    expect(authored?.style.top).toBe('80px');
    expect(authored?.style.width).toBe('120px');
    expect(authored?.style.height).toBe('160px');
    expect(screen.getByText('30×40 mm @ 10, 20')).toBeTruthy();
  });

  it('loads ephemeral Image/Font previews and disposes both browser resources on unmount', async () => {
    const fontId = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
    const imageId = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb';
    const text = {
      ...fixedNode('text-node', 'text', '售价', 4, 5, 50, 15),
      runs: [{
        text: '¥19.90',
        fontRef: { assetId: fontId },
        fontSizePt: 12,
        color: '#000000FF',
        decoration: 'NONE',
      }],
    };
    const image = {
      ...fixedNode('image-node', 'image', '商品图', 60, 5, 40, 30),
      imageRef: { assetId: imageId },
      fit: 'CONTAIN',
      sampling: 'LINEAR',
    };
    const designRoot: Record<string, unknown> = {
      nodeId: 'canvas', kind: 'canvas', displayName: '画布', widthMm: 210, heightMm: 297,
      bindings: [], children: [text, image],
    };
    const imageDispose = vi.fn();
    const fontDispose = vi.fn();
    const assetResources: TemplateEditorCanvasAssetResources = {
      createImage: vi.fn(() => ({ url: 'blob:asset-image', dispose: imageDispose })),
      loadFont: vi.fn(async () => ({ family: 'RenderWeaveTestFont', dispose: fontDispose })),
    };
    const assetTransport: TemplateEditorAssetTransport = {
      listAssets: vi.fn(async () => ({ items: [] })),
      getCurrent: vi.fn(async (assetId) => canvasAssetDetail(
        assetId,
        assetId === imageId ? 'IMAGE' : 'FONT',
      )),
      previewCurrent: vi.fn(async () => new Blob([new Uint8Array([1, 2, 3])])),
    };
    const view = render(<TemplateEditorCanvas
      workingCopy={{
        canonicalDesignDsl: '{}',
        designDsl: {
          dslVersion: 'renderweave-design/1.0',
          expressionProfile: 'renderweave-expression/1.0',
          definitions: [],
          designRoot,
        },
      }}
      nodes={projectNodes(designRoot)}
      selectedNodeId="text-node"
      onSelectNode={vi.fn()}
      assetTransport={assetTransport}
      assetResources={assetResources}
    />);

    await waitFor(() => {
      expect(view.container.querySelector<HTMLImageElement>(
        '[data-template-visual-kind="image"]',
      )?.getAttribute('src')).toBe('blob:asset-image');
      expect(view.container.querySelector<HTMLElement>(
        '[data-template-text-run]',
      )?.style.fontFamily).toContain('RenderWeaveTestFont');
    });
    view.unmount();
    expect(imageDispose).toHaveBeenCalledTimes(1);
    expect(fontDispose).toHaveBeenCalledTimes(1);
  });

  it('keeps authored nodes in preorder and renders selection chrome in an independent overlay', () => {
    const { workingCopy, nodes } = canvasFixture();
    const view = render(<TemplateEditorCanvas
      workingCopy={workingCopy}
      nodes={nodes}
      selectedNodeId="nested-rect"
      selectedNodeIds={['nested-rect']}
      onSelectNode={vi.fn()}
    />);

    const authored = Array.from(view.container.querySelectorAll<HTMLElement>(
      '[data-template-canvas-authored-node]',
    ));
    expect(authored.map((node) => node.dataset.templateCanvasNodeId))
      .toEqual(['back-rect', 'frame', 'nested-rect', 'stack']);
    expect(authored.every((node) => node.style.zIndex === '')).toBe(true);
    expect(authored.every((node) => !node.classList.contains('is-selected'))).toBe(true);
    expect(authored.flatMap((node) => Array.from(node.querySelectorAll('[data-resize-handle]'))))
      .toHaveLength(0);

    const overlay = view.container.querySelector<HTMLElement>(
      '[data-template-canvas-editor-overlay]',
    );
    const selection = view.container.querySelector<HTMLElement>(
      '[data-template-canvas-selection="nested-rect"]',
    );
    expect(overlay).not.toBeNull();
    expect(selection).not.toBeNull();
    expect(authored[2]?.contains(selection)).toBe(false);
    expect(selection?.querySelectorAll('[data-resize-handle]')).toHaveLength(8);
    expect(selection?.querySelector('.te-canvas-node-label')?.textContent).toBe('嵌套矩形');
  });

  it('switches tools with V/H/Escape and selects all authored elements outside editable controls', () => {
    const { workingCopy, nodes } = canvasFixture();
    const onToolChange = vi.fn();
    const onSelectionChange = vi.fn();
    const view = render(<TemplateEditorCanvas
      workingCopy={workingCopy}
      nodes={nodes}
      selectedNodeId="back-rect"
      tool="select"
      onSelectNode={vi.fn()}
      onToolChange={onToolChange}
      onSelectionChange={onSelectionChange}
    />);

    fireEvent.keyDown(window, { key: 'h' });
    fireEvent.keyDown(window, { key: 'V' });
    expect(onToolChange.mock.calls).toEqual([['pan'], ['select']]);

    view.rerender(<TemplateEditorCanvas
      workingCopy={workingCopy}
      nodes={nodes}
      selectedNodeId="back-rect"
      tool="pan"
      onSelectNode={vi.fn()}
      onToolChange={onToolChange}
      onSelectionChange={onSelectionChange}
    />);
    fireEvent.keyDown(window, { key: 'Escape' });
    expect(onToolChange).toHaveBeenLastCalledWith('select');

    fireEvent.keyDown(window, { key: 'a', ctrlKey: true });
    expect(onSelectionChange).toHaveBeenLastCalledWith(
      ['back-rect', 'frame', 'nested-rect', 'stack'],
      'back-rect',
    );

    const input = document.createElement('input');
    screen.getByLabelText('本地草稿画布视口').append(input);
    fireEvent.keyDown(input, { key: 'a', metaKey: true });
    expect(onSelectionChange).toHaveBeenCalledTimes(1);
  });

  it('leaves Space activation to interactive controls and only temporarily pans from non-interactive context', () => {
    const { workingCopy, nodes } = canvasFixture();
    const view = render(<TemplateEditorCanvas
      workingCopy={workingCopy}
      nodes={nodes}
      selectedNodeId="back-rect"
      tool="select"
      onSelectNode={vi.fn()}
    />);
    const viewport = screen.getByLabelText('本地草稿画布视口');
    const button = document.createElement('button');
    button.type = 'button';
    const link = document.createElement('a');
    link.href = '#canvas-test';
    const menuitem = document.createElement('div');
    menuitem.setAttribute('role', 'menuitem');
    menuitem.tabIndex = 0;
    viewport.append(button, link, menuitem);

    for (const control of [button, link, menuitem]) {
      const keyDown = new KeyboardEvent('keydown', {
        key: ' ', bubbles: true, cancelable: true,
      });
      control.dispatchEvent(keyDown);
      expect(keyDown.defaultPrevented).toBe(false);
      control.dispatchEvent(new KeyboardEvent('keyup', { key: ' ', bubbles: true }));
    }

    const nested = view.container.querySelector<HTMLElement>(
      '[data-template-canvas-authored-node][data-template-canvas-node-id="nested-rect"]',
    );
    const beforeControlSpace = Number(viewport.dataset.canvasX);
    button.dispatchEvent(new KeyboardEvent('keydown', {
      key: ' ', bubbles: true, cancelable: true,
    }));
    fireEvent.pointerDown(nested as HTMLElement, {
      button: 0, pointerId: 77, clientX: 200, clientY: 200,
    });
    fireEvent.pointerMove(viewport, { pointerId: 77, clientX: 220, clientY: 200 });
    fireEvent.pointerUp(viewport, { pointerId: 77, clientX: 220, clientY: 200 });
    button.dispatchEvent(new KeyboardEvent('keyup', { key: ' ', bubbles: true }));
    expect(Number(viewport.dataset.canvasX)).toBeCloseTo(beforeControlSpace, 8);

    const bodySpace = new KeyboardEvent('keydown', {
      key: ' ', bubbles: true, cancelable: true,
    });
    document.body.dispatchEvent(bodySpace);
    expect(bodySpace.defaultPrevented).toBe(true);
    fireEvent.pointerDown(nested as HTMLElement, {
      button: 0, pointerId: 78, clientX: 200, clientY: 200,
    });
    fireEvent.pointerMove(viewport, { pointerId: 78, clientX: 212, clientY: 200 });
    fireEvent.pointerUp(viewport, { pointerId: 78, clientX: 212, clientY: 200 });
    document.body.dispatchEvent(new KeyboardEvent('keyup', { key: ' ', bubbles: true }));
    expect(Number(viewport.dataset.canvasX)).toBeCloseTo(beforeControlSpace + 12, 8);
  });

  it('pans from the pasteboard or temporary Space tool and zooms around the pointer', () => {
    const { workingCopy, nodes } = canvasFixture();
    const view = render(<TemplateEditorCanvas
      workingCopy={workingCopy}
      nodes={nodes}
      selectedNodeId="back-rect"
      tool="select"
      onSelectNode={vi.fn()}
    />);
    const viewport = screen.getByLabelText('本地草稿画布视口');
    vi.spyOn(viewport, 'getBoundingClientRect').mockReturnValue(domRect(100, 50, 1000, 700));
    Object.defineProperty(viewport, 'clientWidth', { configurable: true, value: 1000 });
    Object.defineProperty(viewport, 'clientHeight', { configurable: true, value: 700 });

    const startX = Number(viewport.dataset.canvasX);
    const startY = Number(viewport.dataset.canvasY);
    fireEvent.pointerDown(viewport, { button: 0, pointerId: 1, clientX: 120, clientY: 90 });
    fireEvent.pointerMove(viewport, { pointerId: 1, clientX: 150, clientY: 110 });
    fireEvent.pointerUp(viewport, { pointerId: 1, clientX: 150, clientY: 110 });
    expect(Number(viewport.dataset.canvasX)).toBeCloseTo(startX + 30, 8);
    expect(Number(viewport.dataset.canvasY)).toBeCloseTo(startY + 20, 8);

    const nested = view.container.querySelector<HTMLElement>(
      '[data-template-canvas-node-id="nested-rect"]',
    );
    const beforeSpacePanX = Number(viewport.dataset.canvasX);
    fireEvent.keyDown(window, { key: ' ' });
    fireEvent.pointerDown(nested as HTMLElement, {
      button: 0, pointerId: 2, clientX: 200, clientY: 200,
    });
    fireEvent.pointerMove(viewport, { pointerId: 2, clientX: 212, clientY: 200 });
    fireEvent.pointerUp(viewport, { pointerId: 2, clientX: 212, clientY: 200 });
    fireEvent.keyUp(window, { key: ' ' });
    expect(Number(viewport.dataset.canvasX)).toBeCloseTo(beforeSpacePanX + 12, 8);

    view.rerender(<TemplateEditorCanvas
      workingCopy={workingCopy}
      nodes={nodes}
      selectedNodeId="back-rect"
      tool="pan"
      onSelectNode={vi.fn()}
    />);
    const beforePanToolY = Number(viewport.dataset.canvasY);
    fireEvent.pointerDown(nested as HTMLElement, {
      button: 0, pointerId: 5, clientX: 200, clientY: 200,
    });
    fireEvent.pointerMove(viewport, { pointerId: 5, clientX: 200, clientY: 216 });
    fireEvent.pointerUp(viewport, { pointerId: 5, clientX: 200, clientY: 216 });
    expect(Number(viewport.dataset.canvasY)).toBeCloseTo(beforePanToolY + 16, 8);

    const before = {
      scale: Number(viewport.dataset.canvasScale),
      x: Number(viewport.dataset.canvasX),
      y: Number(viewport.dataset.canvasY),
    };
    const anchor = { x: 320, y: 210 };
    const wheel = new WheelEvent('wheel', { deltaY: -120, bubbles: true, cancelable: true });
    Object.defineProperty(wheel, 'clientX', { value: anchor.x + 100 });
    Object.defineProperty(wheel, 'clientY', { value: anchor.y + 50 });
    fireEvent(viewport, wheel);
    const after = {
      scale: Number(viewport.dataset.canvasScale),
      x: Number(viewport.dataset.canvasX),
      y: Number(viewport.dataset.canvasY),
    };
    expect(after.scale).toBeGreaterThan(before.scale);
    expect(after.x + ((anchor.x - before.x) / before.scale) * after.scale)
      .toBeCloseTo(anchor.x, 8);
    expect(after.y + ((anchor.y - before.y) / before.scale) * after.scale)
      .toBeCloseTo(anchor.y, 8);
  });

  it('previews pointer move and free resize, then commits each geometry exactly once on pointerup', () => {
    const { workingCopy, nodes } = canvasFixture();
    const onGeometryCommit = vi.fn();
    const view = render(<TemplateEditorCanvas
      workingCopy={workingCopy}
      nodes={nodes}
      selectedNodeId="nested-rect"
      tool="select"
      onSelectNode={vi.fn()}
      onGeometryCommit={onGeometryCommit}
    />);
    const viewport = screen.getByLabelText('本地草稿画布视口');
    const authored = view.container.querySelector<HTMLElement>(
      '[data-template-canvas-authored-node][data-template-canvas-node-id="nested-rect"]',
    );
    const selection = view.container.querySelector<HTMLElement>(
      '[data-template-canvas-selection="nested-rect"]',
    );
    const selectionCapture = vi.fn();
    const viewportCapture = vi.fn();
    Object.defineProperty(selection, 'setPointerCapture', { value: selectionCapture });
    Object.defineProperty(viewport, 'setPointerCapture', { value: viewportCapture });

    fireEvent.pointerDown(selection as HTMLElement, {
      button: 0, pointerId: 3, clientX: 300, clientY: 300,
    });
    expect(selectionCapture).toHaveBeenCalledWith(3);
    expect(viewportCapture).not.toHaveBeenCalled();
    fireEvent.pointerMove(viewport, { pointerId: 3, clientX: 340, clientY: 320 });
    expect(authored?.style.left).toBe('248px');
    expect(authored?.style.top).toBe('272px');
    expect(onGeometryCommit).not.toHaveBeenCalled();
    fireEvent.pointerUp(viewport, { pointerId: 3, clientX: 340, clientY: 320 });
    fireEvent.pointerUp(viewport, { pointerId: 3, clientX: 340, clientY: 320 });
    expect(onGeometryCommit).toHaveBeenCalledTimes(1);
    expect(onGeometryCommit).toHaveBeenLastCalledWith('nested-rect', {
      xMm: 12,
      yMm: 8,
      widthMm: 4,
      heightMm: 5,
    });

    const southHandle = view.container.querySelector<HTMLElement>(
      '[data-template-canvas-selection="nested-rect"] [data-resize-handle="s"]',
    );
    fireEvent.pointerDown(southHandle as HTMLElement, {
      button: 0, pointerId: 4, clientX: 300, clientY: 300,
    });
    fireEvent.pointerMove(viewport, { pointerId: 4, clientX: 300, clientY: 288 });
    expect(authored?.style.width).toBe('16px');
    expect(authored?.style.height).toBe('8px');
    fireEvent.pointerUp(viewport, { pointerId: 4, clientX: 300, clientY: 288 });
    expect(onGeometryCommit).toHaveBeenCalledTimes(2);
    expect(onGeometryCommit).toHaveBeenLastCalledWith('nested-rect', {
      xMm: 2,
      yMm: 3,
      widthMm: 4,
      heightMm: 2,
    });

    fireEvent.pointerDown(selection as HTMLElement, {
      button: 0, pointerId: 6, clientX: 300, clientY: 300,
    });
    fireEvent.pointerMove(viewport, { pointerId: 6, clientX: 320, clientY: 320 });
    fireEvent.pointerCancel(viewport, { pointerId: 6, clientX: 320, clientY: 320 });
    expect(onGeometryCommit).toHaveBeenCalledTimes(2);
    expect(authored?.style.left).toBe('208px');
    expect(authored?.style.top).toBe('252px');
  });

  it.each(['group', 'frame', 'stack', 'grid'])(
    'moves the complete projected %s subtree during an ABSOLUTE preview and hides unsafe resize handles',
    (kind) => {
      const { workingCopy, nodes } = containerCanvasFixture(kind);
      const onGeometryCommit = vi.fn();
      const view = render(<TemplateEditorCanvas
        workingCopy={workingCopy}
        nodes={nodes}
        selectedNodeId="container"
        tool="select"
        onSelectNode={vi.fn()}
        onGeometryCommit={onGeometryCommit}
      />);
      const viewport = screen.getByLabelText('本地草稿画布视口');
      const container = authoredCanvasNode(view.container, 'container');
      const child = authoredCanvasNode(view.container, 'container-child');
      const selection = view.container.querySelector<HTMLElement>(
        '[data-template-canvas-selection="container"]',
      );
      const start = {
        containerX: Number.parseFloat(container?.style.left ?? ''),
        containerY: Number.parseFloat(container?.style.top ?? ''),
        childX: Number.parseFloat(child?.style.left ?? ''),
        childY: Number.parseFloat(child?.style.top ?? ''),
      };

      expect(selection?.querySelectorAll('[data-resize-handle]')).toHaveLength(0);
      fireEvent.pointerDown(selection as HTMLElement, {
        button: 0, pointerId: 110, clientX: 300, clientY: 300,
      });
      fireEvent.pointerMove(viewport, { pointerId: 110, clientX: 320, clientY: 312 });
      expect(container?.style.left).toBe(`${start.containerX + 20}px`);
      expect(container?.style.top).toBe(`${start.containerY + 12}px`);
      expect(child?.style.left).toBe(`${start.childX + 20}px`);
      expect(child?.style.top).toBe(`${start.childY + 12}px`);
      fireEvent.pointerUp(viewport, { pointerId: 110, clientX: 320, clientY: 312 });
      expect(onGeometryCommit).toHaveBeenCalledTimes(1);
      expect(onGeometryCommit).toHaveBeenCalledWith('container', expect.objectContaining({
        xMm: 15,
        yMm: 18,
      }));
    },
  );

  it('moves a managed-owned container subtree only ephemerally and restores it on pointerup', () => {
    const child = fixedNode('managed-child', 'rect', '子项', 2, 3, 4, 5);
    const managedFrame = {
      ...fixedNode('managed-frame', 'frame', '受管框架', 0, 0, 20, 20),
      placement: {
        type: 'STACK', widthMode: 'FIXED', widthMm: 20,
        heightMode: 'FIXED', heightMm: 20,
      },
      children: [child],
    };
    const stack = {
      ...fixedNode('outer-stack', 'stack', '外层堆叠', 10, 20, 100, 40),
      padding: { topMm: 0, rightMm: 0, bottomMm: 0, leftMm: 0 },
      direction: 'ROW', gapMm: 0, justifyContent: 'START', alignItems: 'START',
      children: [managedFrame],
    };
    const designRoot: Record<string, unknown> = {
      nodeId: 'canvas', kind: 'canvas', displayName: '画布', widthMm: 210, heightMm: 297,
      bindings: [], children: [stack],
    };
    const workingCopy: CanonicalDesignWorkingCopy = {
      canonicalDesignDsl: '{}',
      designDsl: {
        dslVersion: 'renderweave-design/1.0',
        expressionProfile: 'renderweave-expression/1.0',
        definitions: [], designRoot,
      },
    };
    const onGeometryCommit = vi.fn();
    const view = render(<TemplateEditorCanvas
      workingCopy={workingCopy}
      nodes={projectNodes(designRoot)}
      selectedNodeId="managed-frame"
      tool="select"
      onSelectNode={vi.fn()}
      onGeometryCommit={onGeometryCommit}
    />);
    const viewport = screen.getByLabelText('本地草稿画布视口');
    const container = authoredCanvasNode(view.container, 'managed-frame');
    const descendant = authoredCanvasNode(view.container, 'managed-child');
    const selection = view.container.querySelector<HTMLElement>(
      '[data-template-canvas-selection="managed-frame"]',
    );
    const startContainer = { left: container?.style.left, top: container?.style.top };
    const startDescendant = { left: descendant?.style.left, top: descendant?.style.top };

    fireEvent.pointerDown(selection as HTMLElement, {
      button: 0, pointerId: 111, clientX: 300, clientY: 300,
    });
    fireEvent.pointerMove(viewport, { pointerId: 111, clientX: 324, clientY: 316 });
    expect(container?.style.left).toBe(`${Number.parseFloat(startContainer.left ?? '') + 24}px`);
    expect(container?.style.top).toBe(`${Number.parseFloat(startContainer.top ?? '') + 16}px`);
    expect(descendant?.style.left).toBe(`${Number.parseFloat(startDescendant.left ?? '') + 24}px`);
    expect(descendant?.style.top).toBe(`${Number.parseFloat(startDescendant.top ?? '') + 16}px`);

    fireEvent.pointerUp(viewport, { pointerId: 111, clientX: 324, clientY: 316 });
    expect(container?.style.left).toBe(startContainer.left);
    expect(container?.style.top).toBe(startContainer.top);
    expect(descendant?.style.left).toBe(startDescendant.left);
    expect(descendant?.style.top).toBe(startDescendant.top);
    expect(onGeometryCommit).not.toHaveBeenCalled();
  });

  it('previews a Stack-owned child move then restores it, while persisting a fixed resize only', () => {
    const first = {
      ...fixedNode('stack-first', 'rect', '首项', 0, 0, 20, 10),
      placement: {
        type: 'STACK', widthMode: 'FIXED', widthMm: 20,
        heightMode: 'FIXED', heightMm: 10,
      },
    };
    const second = {
      ...fixedNode('stack-second', 'rect', '次项', 0, 0, 30, 10),
      placement: {
        type: 'STACK', widthMode: 'FIXED', widthMm: 30,
        heightMode: 'FIXED', heightMm: 10,
      },
    };
    const stack = {
      ...fixedNode('managed-stack', 'stack', '横向堆叠', 10, 20, 100, 30),
      padding: { topMm: 0, rightMm: 0, bottomMm: 0, leftMm: 0 },
      direction: 'ROW', gapMm: 4, justifyContent: 'START', alignItems: 'START',
      children: [first, second],
    };
    const designRoot: Record<string, unknown> = {
      nodeId: 'canvas', kind: 'canvas', displayName: '画布', widthMm: 210, heightMm: 297,
      bindings: [], children: [stack],
    };
    const workingCopy: CanonicalDesignWorkingCopy = {
      canonicalDesignDsl: '{}',
      designDsl: {
        dslVersion: 'renderweave-design/1.0',
        expressionProfile: 'renderweave-expression/1.0',
        definitions: [],
        designRoot,
      },
    };
    const onGeometryCommit = vi.fn();
    const view = render(<TemplateEditorCanvas
      workingCopy={workingCopy}
      nodes={projectNodes(designRoot)}
      selectedNodeId="stack-first"
      tool="select"
      onSelectNode={vi.fn()}
      onGeometryCommit={onGeometryCommit}
    />);
    const viewport = screen.getByLabelText('本地草稿画布视口');
    const authored = view.container.querySelector<HTMLElement>(
      '[data-template-canvas-authored-node][data-template-canvas-node-id="stack-first"]',
    );
    const selection = view.container.querySelector<HTMLElement>(
      '[data-template-canvas-selection="stack-first"]',
    );

    expect(authored?.style.left).toBe('40px');
    expect(authored?.style.top).toBe('80px');
    expect(authored?.style.width).toBe('80px');
    fireEvent.pointerDown(selection as HTMLElement, {
      button: 0, pointerId: 91, clientX: 300, clientY: 300,
    });
    fireEvent.pointerMove(viewport, { pointerId: 91, clientX: 340, clientY: 320 });
    expect(authored?.style.left).toBe('80px');
    expect(authored?.style.top).toBe('100px');
    fireEvent.pointerUp(viewport, { pointerId: 91, clientX: 340, clientY: 320 });
    expect(authored?.style.left).toBe('40px');
    expect(authored?.style.top).toBe('80px');
    expect(onGeometryCommit).not.toHaveBeenCalled();

    const eastHandle = view.container.querySelector<HTMLElement>(
      '[data-template-canvas-selection="stack-first"] [data-resize-handle="e"]',
    );
    fireEvent.pointerDown(eastHandle as HTMLElement, {
      button: 0, pointerId: 92, clientX: 300, clientY: 300,
    });
    fireEvent.pointerMove(viewport, { pointerId: 92, clientX: 320, clientY: 300 });
    expect(authored?.style.width).toBe('100px');
    fireEvent.pointerUp(viewport, { pointerId: 92, clientX: 320, clientY: 300 });
    expect(onGeometryCommit).toHaveBeenCalledWith('stack-first', {
      widthMm: 25,
      heightMm: 10,
    });
  });

  it('projects Grid tracks, cell alignment and FILL children into canvas coordinates', () => {
    const fixedCell = {
      ...fixedNode('grid-fixed', 'rect', '固定单元', 0, 0, 8, 5),
      placement: {
        type: 'GRID', row: 0, column: 0, rowSpan: 1, columnSpan: 1,
        widthMode: 'FIXED', widthMm: 8,
        heightMode: 'FIXED', heightMm: 5,
        marginTopMm: 1, marginRightMm: 1, marginBottomMm: 1, marginLeftMm: 1,
        horizontalAlignSelf: 'CENTER', verticalAlignSelf: 'START',
      },
    };
    const fillCell = {
      ...fixedNode('grid-fill', 'rect', '填充单元', 0, 0, 1, 1),
      placement: {
        type: 'GRID', row: 1, column: 1, rowSpan: 1, columnSpan: 1,
        widthMode: 'FILL', heightMode: 'FILL',
        horizontalAlignSelf: 'START', verticalAlignSelf: 'START',
      },
    };
    const grid = {
      ...fixedNode('managed-grid', 'grid', '实时网格', 5, 6, 60, 40),
      padding: { topMm: 1, rightMm: 1, bottomMm: 1, leftMm: 1 },
      columns: [{ type: 'FIXED', valueMm: 10 }, { type: 'FRACTION', weight: 1 }],
      rows: [{ type: 'AUTO' }, { type: 'FRACTION', weight: 1 }],
      columnGapMm: 2,
      rowGapMm: 3,
      children: [fixedCell, fillCell],
    };
    const designRoot: Record<string, unknown> = {
      nodeId: 'canvas', kind: 'canvas', displayName: '画布', widthMm: 100, heightMm: 80,
      bindings: [], children: [grid],
    };
    const view = render(<TemplateEditorCanvas
      workingCopy={{
        canonicalDesignDsl: '{}',
        designDsl: {
          dslVersion: 'renderweave-design/1.0',
          expressionProfile: 'renderweave-expression/1.0',
          definitions: [],
          designRoot,
        },
      }}
      nodes={projectNodes(designRoot)}
      selectedNodeId="grid-fill"
      onSelectNode={vi.fn()}
    />);

    const fixedProjected = view.container.querySelector<HTMLElement>(
      '[data-template-canvas-authored-node][data-template-canvas-node-id="grid-fixed"]',
    );
    const fillProjected = view.container.querySelector<HTMLElement>(
      '[data-template-canvas-authored-node][data-template-canvas-node-id="grid-fill"]',
    );
    expect(fixedProjected?.style.cssText).toContain('left: 28px');
    expect(fixedProjected?.style.cssText).toContain('top: 32px');
    expect(fixedProjected?.style.cssText).toContain('width: 32px');
    expect(fixedProjected?.style.cssText).toContain('height: 20px');
    expect(fillProjected?.style.cssText).toContain('left: 72px');
    expect(fillProjected?.style.cssText).toContain('top: 68px');
    expect(fillProjected?.style.cssText).toContain('width: 184px');
    expect(fillProjected?.style.cssText).toContain('height: 112px');
  });

  it('shows a stable visible problem instead of fabricating unsupported intrinsic geometry', () => {
    const intrinsic = {
      ...fixedNode('intrinsic-rect', 'rect', '非法自适应矩形', 10, 20, 30, 40),
      placement: {
        type: 'ABSOLUTE', xMm: 10, yMm: 20,
        widthMode: 'HUG_CONTENT', heightMode: 'FIXED', heightMm: 40,
      },
    };
    const designRoot: Record<string, unknown> = {
      nodeId: 'canvas', kind: 'canvas', displayName: '画布', widthMm: 210, heightMm: 297,
      bindings: [], children: [intrinsic],
    };
    const view = render(<TemplateEditorCanvas
      workingCopy={{
        canonicalDesignDsl: '{}',
        designDsl: {
          dslVersion: 'renderweave-design/1.0',
          expressionProfile: 'renderweave-expression/1.0',
          definitions: [],
          designRoot,
        },
      }}
      nodes={projectNodes(designRoot)}
      selectedNodeId="intrinsic-rect"
      onSelectNode={vi.fn()}
    />);

    expect(view.container.querySelector('[data-template-canvas-authored-node]')).toBeNull();
    expect(screen.getByRole('alert').textContent).toContain('HUG_CONTENT');
  });

  it('keeps QR resize strictly square while preserving the opposite edge or corner anchor', () => {
    const qrCode = {
      ...fixedNode('qr-code', 'qrCode', '二维码', 10, 20, 25, 25),
      content: 'RenderWeave',
      errorCorrectionLevel: 'M',
      foregroundColor: '#000000FF',
      backgroundColor: '#FFFFFFFF',
    };
    const designRoot: Record<string, unknown> = {
      nodeId: 'canvas', kind: 'canvas', displayName: '画布', widthMm: 210, heightMm: 297,
      bindings: [], children: [qrCode],
    };
    const onGeometryCommit = vi.fn();
    const view = render(<TemplateEditorCanvas
      workingCopy={{
        canonicalDesignDsl: '{}',
        designDsl: {
          dslVersion: 'renderweave-design/1.0',
          expressionProfile: 'renderweave-expression/1.0',
          definitions: [],
          designRoot,
        },
      }}
      nodes={projectNodes(designRoot)}
      selectedNodeId="qr-code"
      tool="select"
      onSelectNode={vi.fn()}
      onGeometryCommit={onGeometryCommit}
    />);
    const viewport = screen.getByLabelText('本地草稿画布视口');
    const authored = view.container.querySelector<HTMLElement>(
      '[data-template-canvas-authored-node][data-template-canvas-node-id="qr-code"]',
    );

    const eastHandle = view.container.querySelector<HTMLElement>(
      '[data-template-canvas-selection="qr-code"] [data-resize-handle="e"]',
    );
    fireEvent.pointerDown(eastHandle as HTMLElement, {
      button: 0, pointerId: 41, clientX: 300, clientY: 300,
    });
    fireEvent.pointerMove(viewport, { pointerId: 41, clientX: 320, clientY: 300 });
    expect(authored?.style.left).toBe('40px');
    expect(authored?.style.top).toBe('70px');
    expect(authored?.style.width).toBe('120px');
    expect(authored?.style.height).toBe('120px');
    fireEvent.pointerUp(viewport, { pointerId: 41, clientX: 320, clientY: 300 });
    expect(onGeometryCommit).toHaveBeenLastCalledWith('qr-code', {
      xMm: 10,
      yMm: 17.5,
      widthMm: 30,
      heightMm: 30,
    });

    const northwestHandle = view.container.querySelector<HTMLElement>(
      '[data-template-canvas-selection="qr-code"] [data-resize-handle="nw"]',
    );
    fireEvent.pointerDown(northwestHandle as HTMLElement, {
      button: 0, pointerId: 42, clientX: 300, clientY: 300,
    });
    fireEvent.pointerMove(viewport, { pointerId: 42, clientX: 320, clientY: 308 });
    expect(authored?.style.left).toBe('60px');
    expect(authored?.style.top).toBe('100px');
    expect(authored?.style.width).toBe('80px');
    expect(authored?.style.height).toBe('80px');
    fireEvent.pointerUp(viewport, { pointerId: 42, clientX: 320, clientY: 308 });
    expect(onGeometryCommit).toHaveBeenLastCalledWith('qr-code', {
      xMm: 15,
      yMm: 25,
      widthMm: 20,
      heightMm: 20,
    });
    expect(onGeometryCommit).toHaveBeenCalledTimes(2);
  });

  it('keeps a locked canvas selectable and navigable while disabling every semantic mutation', () => {
    const { workingCopy, nodes } = canvasFixture();
    const onSelectNode = vi.fn();
    const onGeometryCommit = vi.fn();
    const onReorderNode = vi.fn();
    const onDeleteSelection = vi.fn();
    const onInsertAt = vi.fn();
    const view = render(<TemplateEditorCanvas
      workingCopy={workingCopy}
      nodes={nodes}
      selectedNodeId="nested-rect"
      selectedNodeIds={['nested-rect']}
      tool="select"
      disabled
      onSelectNode={onSelectNode}
      onGeometryCommit={onGeometryCommit}
      onReorderNode={onReorderNode}
      onDeleteSelection={onDeleteSelection}
      onInsertAt={onInsertAt}
    />);
    const viewport = screen.getByLabelText('本地草稿画布视口');
    const authored = view.container.querySelector<HTMLElement>(
      '[data-template-canvas-authored-node][data-template-canvas-node-id="nested-rect"]',
    );
    const selection = view.container.querySelector<HTMLElement>(
      '[data-template-canvas-selection="nested-rect"]',
    );

    fireEvent.pointerDown(selection as HTMLElement, {
      button: 0, pointerId: 81, clientX: 300, clientY: 300,
    });
    fireEvent.pointerMove(viewport, { pointerId: 81, clientX: 340, clientY: 320 });
    fireEvent.pointerUp(viewport, { pointerId: 81, clientX: 340, clientY: 320 });
    expect(authored?.style.left).toBe('208px');
    expect(authored?.style.top).toBe('252px');
    expect(onGeometryCommit).not.toHaveBeenCalled();

    const beforePanX = Number(viewport.dataset.canvasX);
    fireEvent.pointerDown(viewport, {
      button: 0, pointerId: 82, clientX: 120, clientY: 90,
    });
    fireEvent.pointerMove(viewport, { pointerId: 82, clientX: 132, clientY: 90 });
    fireEvent.pointerUp(viewport, { pointerId: 82, clientX: 132, clientY: 90 });
    expect(Number(viewport.dataset.canvasX)).toBeCloseTo(beforePanX + 12, 8);

    const beforeScale = Number(viewport.dataset.canvasScale);
    fireEvent.wheel(viewport, { deltaY: -120, clientX: 200, clientY: 180 });
    expect(Number(viewport.dataset.canvasScale)).toBeGreaterThan(beforeScale);

    const stack = view.container.querySelector<HTMLElement>(
      '[data-template-canvas-authored-node][data-template-canvas-node-id="stack"]',
    );
    fireEvent.click(stack as HTMLElement);
    expect(onSelectNode).toHaveBeenLastCalledWith('stack');
    fireEvent.contextMenu(stack as HTMLElement, { clientX: 420, clientY: 280 });
    for (const name of ['置于顶层', '上移一层', '下移一层', '置于底层', '删除']) {
      const item = screen.getByRole('menuitem', { name }) as HTMLButtonElement;
      expect(item.disabled).toBe(true);
      expect(item.getAttribute('aria-disabled')).toBe('true');
      fireEvent.click(item);
    }
    expect(onReorderNode).not.toHaveBeenCalled();
    expect(onDeleteSelection).not.toHaveBeenCalled();

    const artboard = view.container.querySelector<HTMLElement>('.te-artboard');
    const dataTransfer = {
      types: [TEMPLATE_NODE_DRAG_MIME],
      dropEffect: 'none',
      getData: (type: string) => type === TEMPLATE_NODE_DRAG_MIME ? 'rect' : '',
    };
    fireEvent.dragOver(artboard as HTMLElement, { dataTransfer });
    const drop = new Event('drop', { bubbles: true, cancelable: true });
    Object.defineProperties(drop, {
      clientX: { value: 140 },
      clientY: { value: 90 },
      dataTransfer: { value: dataTransfer },
    });
    fireEvent(artboard as HTMLElement, drop);
    expect(onInsertAt).not.toHaveBeenCalled();
  });

  it('cancels an in-flight geometry preview when the editor becomes locked', () => {
    const { workingCopy, nodes } = canvasFixture();
    const onGeometryCommit = vi.fn();
    const props = {
      workingCopy,
      nodes,
      selectedNodeId: 'nested-rect',
      selectedNodeIds: ['nested-rect'],
      tool: 'select' as const,
      onSelectNode: vi.fn(),
      onGeometryCommit,
    };
    const view = render(<TemplateEditorCanvas {...props} />);
    const viewport = screen.getByLabelText('本地草稿画布视口');
    const authored = view.container.querySelector<HTMLElement>(
      '[data-template-canvas-authored-node][data-template-canvas-node-id="nested-rect"]',
    );
    const selection = view.container.querySelector<HTMLElement>(
      '[data-template-canvas-selection="nested-rect"]',
    );
    const releasePointerCapture = vi.fn();
    Object.defineProperties(selection, {
      setPointerCapture: { configurable: true, value: vi.fn() },
      hasPointerCapture: { configurable: true, value: () => true },
      releasePointerCapture: { configurable: true, value: releasePointerCapture },
    });

    fireEvent.pointerDown(selection as HTMLElement, {
      button: 0, pointerId: 83, clientX: 300, clientY: 300,
    });
    fireEvent.pointerMove(viewport, { pointerId: 83, clientX: 340, clientY: 320 });
    expect(authored?.style.left).toBe('248px');

    view.rerender(<TemplateEditorCanvas {...props} disabled />);
    expect(authored?.style.left).toBe('208px');
    expect(authored?.style.top).toBe('252px');
    expect(releasePointerCapture).toHaveBeenCalledWith(83);

    fireEvent.pointerUp(viewport, { pointerId: 83, clientX: 340, clientY: 320 });
    expect(onGeometryCommit).not.toHaveBeenCalled();
  });

  it('selects on right click and exposes only children-order operations plus delete', () => {
    const { workingCopy, nodes } = canvasFixture();
    const onSelectNode = vi.fn();
    const onSelectionChange = vi.fn();
    const onReorderNode = vi.fn();
    const onDeleteSelection = vi.fn();
    const view = render(<TemplateEditorCanvas
      workingCopy={workingCopy}
      nodes={nodes}
      selectedNodeId="nested-rect"
      onSelectNode={onSelectNode}
      onSelectionChange={onSelectionChange}
      onReorderNode={onReorderNode}
      onDeleteSelection={onDeleteSelection}
    />);
    const frame = view.container.querySelector<HTMLElement>(
      '[data-template-canvas-authored-node][data-template-canvas-node-id="frame"]',
    );
    const openMenu = () => fireEvent.contextMenu(frame as HTMLElement, {
      clientX: 420,
      clientY: 280,
    });

    openMenu();
    expect(onSelectNode).toHaveBeenLastCalledWith('frame');
    expect(onSelectionChange).toHaveBeenLastCalledWith(['frame'], 'frame');
    expect(screen.getByRole('menu', { name: '节点操作' })).toBeTruthy();
    fireEvent.click(screen.getByRole('menuitem', { name: '置于顶层' }));
    expect(onReorderNode).toHaveBeenLastCalledWith('frame', 'front');

    openMenu();
    fireEvent.click(screen.getByRole('menuitem', { name: '上移一层' }));
    expect(onReorderNode).toHaveBeenLastCalledWith('frame', 'forward');
    openMenu();
    fireEvent.click(screen.getByRole('menuitem', { name: '下移一层' }));
    expect(onReorderNode).toHaveBeenLastCalledWith('frame', 'backward');
    openMenu();
    fireEvent.click(screen.getByRole('menuitem', { name: '置于底层' }));
    expect(onReorderNode).toHaveBeenLastCalledWith('frame', 'back');

    openMenu();
    fireEvent.keyDown(window, { key: 'Escape' });
    expect(screen.queryByRole('menu')).toBeNull();
    openMenu();
    fireEvent.click(screen.getByRole('menuitem', { name: '删除' }));
    expect(onDeleteSelection).toHaveBeenCalledTimes(1);
  });

  it('returns focus to the canvas viewport after its temporary context menu closes', () => {
    const { workingCopy, nodes } = canvasFixture();
    const view = render(<TemplateEditorCanvas
      workingCopy={workingCopy}
      nodes={nodes}
      selectedNodeId="frame"
      onSelectNode={vi.fn()}
      onReorderNode={vi.fn()}
    />);
    const viewport = screen.getByLabelText('本地草稿画布视口');
    const frame = view.container.querySelector<HTMLElement>(
      '[data-template-canvas-authored-node][data-template-canvas-node-id="frame"]',
    );
    const openMenu = () => fireEvent.contextMenu(frame as HTMLElement, {
      clientX: 420,
      clientY: 280,
    });

    viewport.focus();
    openMenu();
    expect(document.activeElement).toBe(screen.getByRole('menuitem', { name: '置于顶层' }));
    fireEvent.keyDown(window, { key: 'Escape' });
    expect(screen.queryByRole('menu')).toBeNull();
    expect(document.activeElement).toBe(viewport);

    openMenu();
    fireEvent.click(screen.getByRole('menuitem', { name: '置于顶层' }));
    expect(screen.queryByRole('menu')).toBeNull();
    expect(document.activeElement).toBe(viewport);

    openMenu();
    fireEvent.pointerDown(document.body);
    expect(screen.queryByRole('menu')).toBeNull();
    expect(document.activeElement).toBe(viewport);
  });

  it('preserves an existing multi-selection when right click makes one selected node primary', () => {
    const { workingCopy, nodes } = canvasFixture();
    const onSelectNode = vi.fn();
    const onSelectionChange = vi.fn();
    const view = render(<TemplateEditorCanvas
      workingCopy={workingCopy}
      nodes={nodes}
      selectedNodeId="nested-rect"
      selectedNodeIds={['nested-rect', 'stack']}
      onSelectNode={onSelectNode}
      onSelectionChange={onSelectionChange}
    />);
    const stack = view.container.querySelector<HTMLElement>(
      '[data-template-canvas-authored-node][data-template-canvas-node-id="stack"]',
    );
    const backRect = view.container.querySelector<HTMLElement>(
      '[data-template-canvas-authored-node][data-template-canvas-node-id="back-rect"]',
    );

    fireEvent.contextMenu(stack as HTMLElement, { clientX: 420, clientY: 280 });
    expect(onSelectNode).toHaveBeenLastCalledWith('stack');
    expect(onSelectionChange).toHaveBeenLastCalledWith(
      ['nested-rect', 'stack'],
      'stack',
    );

    fireEvent.contextMenu(backRect as HTMLElement, { clientX: 320, clientY: 180 });
    expect(onSelectNode).toHaveBeenLastCalledWith('back-rect');
    expect(onSelectionChange).toHaveBeenLastCalledWith(['back-rect'], 'back-rect');
  });

  it('disables Z-order actions at the real sibling boundaries of the target node', () => {
    const { workingCopy, nodes } = canvasFixture();
    const view = render(<TemplateEditorCanvas
      workingCopy={workingCopy}
      nodes={nodes}
      selectedNodeId="back-rect"
      onSelectNode={vi.fn()}
      onReorderNode={vi.fn()}
    />);
    const authored = (nodeId: string) => view.container.querySelector<HTMLElement>(
      `[data-template-canvas-authored-node][data-template-canvas-node-id="${nodeId}"]`,
    );
    const expectMenuItem = (name: string, expectedDisabled: boolean) => {
      const item = screen.getByRole('menuitem', { name }) as HTMLButtonElement;
      expect(item.disabled).toBe(expectedDisabled);
      expect(item.getAttribute('aria-disabled')).toBe(String(expectedDisabled));
    };

    fireEvent.contextMenu(authored('back-rect') as HTMLElement, {
      clientX: 320, clientY: 180,
    });
    expectMenuItem('置于顶层', false);
    expectMenuItem('上移一层', false);
    expectMenuItem('下移一层', true);
    expectMenuItem('置于底层', true);

    fireEvent.contextMenu(authored('stack') as HTMLElement, {
      clientX: 420, clientY: 280,
    });
    expectMenuItem('置于顶层', true);
    expectMenuItem('上移一层', true);
    expectMenuItem('下移一层', false);
    expectMenuItem('置于底层', false);

    fireEvent.contextMenu(authored('nested-rect') as HTMLElement, {
      clientX: 380, clientY: 240,
    });
    expectMenuItem('置于顶层', true);
    expectMenuItem('上移一层', true);
    expectMenuItem('下移一层', true);
    expectMenuItem('置于底层', true);
  });

  it('accepts every element and container library drop at artboard-local millimetres', () => {
    const { workingCopy, nodes } = canvasFixture();
    const onInsertAt = vi.fn();
    const view = render(<TemplateEditorCanvas
      workingCopy={workingCopy}
      nodes={nodes}
      selectedNodeId="back-rect"
      onSelectNode={vi.fn()}
      onInsertAt={onInsertAt}
    />);
    const artboard = view.container.querySelector<HTMLElement>('.te-artboard');
    vi.spyOn(artboard as HTMLElement, 'getBoundingClientRect')
      .mockReturnValue(domRect(100, 50, 840, 1188));
    const data = new Map([[TEMPLATE_NODE_DRAG_MIME, 'frame']]);
    const dataTransfer = {
      types: [TEMPLATE_NODE_DRAG_MIME],
      dropEffect: 'none',
      getData: (type: string) => data.get(type) ?? '',
    };

    const kinds = [
      'group', 'frame', 'stack', 'grid', 'text', 'image', 'rect', 'ellipse', 'line', 'shape',
      'polygon', 'polyline', 'path', 'qrCode', 'barcode',
    ];
    for (const kind of kinds) {
      data.set(TEMPLATE_NODE_DRAG_MIME, kind);
      fireEvent.dragOver(artboard as HTMLElement, { dataTransfer });
      const drop = new Event('drop', { bubbles: true, cancelable: true });
      Object.defineProperties(drop, {
        clientX: { value: 140 },
        clientY: { value: 90 },
        dataTransfer: { value: dataTransfer },
      });
      fireEvent(artboard as HTMLElement, drop);
    }

    expect(onInsertAt.mock.calls).toEqual(kinds.map((kind) => [kind, 10, 10]));
  });
});

function canvasFixture(): {
  workingCopy: CanonicalDesignWorkingCopy;
  nodes: EditorNodeProjection[];
} {
  const nestedRect = fixedNode('nested-rect', 'rect', '嵌套矩形', 2, 3, 4, 5);
  const designRoot: Record<string, unknown> = {
    nodeId: 'canvas',
    kind: 'canvas',
    displayName: '画布',
    widthMm: 210,
    heightMm: 297,
    bindings: [],
    children: [
      fixedNode('back-rect', 'rect', '后矩形', 10, 20, 30, 40),
      {
        ...fixedNode('frame', 'frame', '框架', 50, 60, 80, 90),
        children: [nestedRect],
      },
      {
        ...fixedNode('stack', 'stack', '堆叠', 20, 100, 40, 50),
        children: [],
        padding: { topMm: 0, rightMm: 0, bottomMm: 0, leftMm: 0 },
        direction: 'COLUMN',
        gapMm: 0,
      },
    ],
  };
  return {
    workingCopy: {
      canonicalDesignDsl: '{}',
      designDsl: {
        dslVersion: 'renderweave-design/1.0',
        expressionProfile: 'renderweave-expression/1.0',
        displayName: 'Canvas test',
        definitions: [],
        designRoot,
      },
    },
    nodes: projectNodes(designRoot),
  };
}

function fixedNode(
  nodeId: string,
  kind: string,
  displayName: string,
  xMm: number,
  yMm: number,
  widthMm: number,
  heightMm: number,
): Record<string, unknown> {
  return {
    nodeId,
    kind,
    displayName,
    bindings: [],
    placement: {
      type: 'ABSOLUTE',
      xMm,
      yMm,
      widthMode: 'FIXED',
      widthMm,
      heightMode: 'FIXED',
      heightMm,
    },
  };
}

function containerCanvasFixture(kind: string): {
  workingCopy: CanonicalDesignWorkingCopy;
  nodes: EditorNodeProjection[];
} {
  const absoluteChild = fixedNode('container-child', 'rect', '容器子项', 2, 3, 4, 5);
  const absolutePlacement = {
    type: 'ABSOLUTE', xMm: 10, yMm: 15,
    widthMode: 'FIXED', widthMm: 20,
    heightMode: 'FIXED', heightMm: 20,
  };
  let container: Record<string, unknown>;
  if (kind === 'group') {
    container = {
      nodeId: 'container', kind, displayName: '自由分组', bindings: [],
      placement: {
        type: 'ABSOLUTE', xMm: 10, yMm: 15,
        widthMode: 'HUG_CONTENT', heightMode: 'HUG_CONTENT',
      },
      children: [absoluteChild],
    };
  } else if (kind === 'stack') {
    container = {
      nodeId: 'container', kind, displayName: '堆叠容器', bindings: [],
      placement: absolutePlacement,
      padding: { topMm: 0, rightMm: 0, bottomMm: 0, leftMm: 0 },
      direction: 'ROW', gapMm: 0, justifyContent: 'START', alignItems: 'START',
      children: [{
        ...absoluteChild,
        placement: {
          type: 'STACK', widthMode: 'FIXED', widthMm: 4,
          heightMode: 'FIXED', heightMm: 5,
        },
      }],
    };
  } else if (kind === 'grid') {
    container = {
      nodeId: 'container', kind, displayName: '网格容器', bindings: [],
      placement: absolutePlacement,
      padding: { topMm: 0, rightMm: 0, bottomMm: 0, leftMm: 0 },
      columns: [{ type: 'FIXED', valueMm: 20 }],
      rows: [{ type: 'FIXED', valueMm: 20 }],
      columnGapMm: 0, rowGapMm: 0,
      children: [{
        ...absoluteChild,
        placement: {
          type: 'GRID', column: 0, row: 0, columnSpan: 1, rowSpan: 1,
          widthMode: 'FIXED', widthMm: 4,
          heightMode: 'FIXED', heightMm: 5,
        },
      }],
    };
  } else {
    container = {
      nodeId: 'container', kind: 'frame', displayName: '框架', bindings: [],
      placement: absolutePlacement,
      children: [absoluteChild],
    };
  }
  const designRoot: Record<string, unknown> = {
    nodeId: 'canvas', kind: 'canvas', displayName: '画布', widthMm: 100, heightMm: 80,
    bindings: [], children: [container],
  };
  return {
    workingCopy: {
      canonicalDesignDsl: '{}',
      designDsl: {
        dslVersion: 'renderweave-design/1.0',
        expressionProfile: 'renderweave-expression/1.0',
        definitions: [], designRoot,
      },
    },
    nodes: projectNodes(designRoot),
  };
}

function authoredCanvasNode(container: HTMLElement, nodeId: string): HTMLElement | null {
  return container.querySelector<HTMLElement>(
    `[data-template-canvas-authored-node][data-template-canvas-node-id="${nodeId}"]`,
  );
}

function canvasAssetDetail(
  assetId: string,
  kind: 'IMAGE' | 'FONT',
): AssetReadableResponse {
  const common = {
    assetId,
    disclosure: 'READABLE' as const,
    lifecycle: 'ACTIVE' as const,
    assetRevision: 0,
    currentContentVersion: 0,
    displayName: kind === 'IMAGE' ? '商品图' : '售价字体',
    tags: [],
    mediaType: kind === 'IMAGE' ? 'image/png' : 'font/ttf',
    byteLength: 3,
    sha256: 'a'.repeat(64),
    createdAt: '2026-09-03T00:00:00Z',
    updatedAt: '2026-09-03T00:00:00Z',
  };
  return kind === 'IMAGE'
    ? {
      ...common,
      kind,
      descriptor: {
        encodedWidthPx: 1,
        encodedHeightPx: 1,
        orientation: 'IDENTITY',
        logicalWidthPx: 1,
        logicalHeightPx: 1,
        frameCount: 1,
        colorEncoding: 'SRGB_8BIT',
      },
    }
    : {
      ...common,
      kind,
      descriptor: { faceIndex: 0, flavor: 'TRUETYPE_GLYF', unitsPerEm: 1_000 },
    };
}

function projectNodes(root: Record<string, unknown>): EditorNodeProjection[] {
  const result: EditorNodeProjection[] = [];
  const visit = (value: Record<string, unknown>, depth: number) => {
    const children = Array.isArray(value.children)
      ? value.children as Record<string, unknown>[]
      : [];
    result.push({
      nodeId: String(value.nodeId),
      kind: String(value.kind),
      displayName: String(value.displayName),
      depth,
      childCount: children.length,
      value,
    });
    for (const child of children) visit(child, depth + 1);
  };
  visit(root, 0);
  return result;
}

function domRect(x: number, y: number, width: number, height: number): DOMRect {
  return {
    x, y, width, height,
    top: y,
    right: x + width,
    bottom: y + height,
    left: x,
    toJSON: () => ({}),
  };
}
