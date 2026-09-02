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
      'frame', 'stack', 'text', 'image', 'rect', 'ellipse', 'line', 'shape',
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
      fixedNode('stack', 'stack', '堆叠', 20, 100, 40, 50),
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
