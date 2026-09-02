import { Maximize2, Minus, Plus } from 'lucide-react';
import {
  useCallback,
  useEffect,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
  type MouseEvent as ReactMouseEvent,
  type PointerEvent as ReactPointerEvent,
} from 'react';

import {
  objectOrNull,
  type CanonicalDesignWorkingCopy,
  type EditorNodeProjection,
} from './template-editor-model';
import {
  fitCanvasViewport,
  panCanvasViewport,
  resizeCanvasRect,
  wheelZoomCanvasViewport,
  zoomCanvasViewportAt,
  type CanvasRect,
  type CanvasResizeHandle,
  type CanvasViewportTransform,
} from './template-canvas-viewport';
import {
  finiteTemplateNumber,
  positiveTemplateNumber,
} from './template-editor-numbers';
import {
  resolveTemplateAssetRef,
  type TemplateAssetResolution,
  type TemplateEditorAssetTransport,
} from './template-editor-assets';
import { TemplateEditorVisualNode } from './TemplateEditorVisualNode';
import { isTemplateEditorVisualNodeKind } from './template-editor-visual-projection';

export const CANVAS_PX_PER_MM = 4;
export const TEMPLATE_NODE_DRAG_MIME = 'application/x-renderweave-template-node-kind';

const FIT_PADDING = 48;

interface AuthoredCanvasNode {
  readonly nodeId: string;
  readonly kind: string;
  readonly displayName: string;
  readonly xMm: number;
  readonly yMm: number;
  readonly widthMm: number;
  readonly heightMm: number;
  readonly xPx: number;
  readonly yPx: number;
  readonly widthPx: number;
  readonly heightPx: number;
  readonly fill: string | null;
  readonly stroke: string | null;
  readonly strokeWidthPx: number;
  readonly borderRadius: string | undefined;
  readonly opacity: number;
  readonly transform: string | undefined;
  readonly transformOrigin: string | undefined;
  readonly value: Readonly<Record<string, unknown>>;
}

export type TemplateEditorCanvasTool = 'select' | 'pan';
export type TemplateEditorCanvasReorderOperation = 'front' | 'forward' | 'backward' | 'back';

export interface TemplateEditorCanvasGeometry {
  readonly xMm?: number;
  readonly yMm?: number;
  readonly widthMm: number;
  readonly heightMm: number;
}

export interface TemplateEditorCanvasProps {
  readonly workingCopy: CanonicalDesignWorkingCopy;
  readonly nodes: readonly EditorNodeProjection[];
  readonly selectedNodeId: string;
  readonly selectedNodeIds?: readonly string[];
  readonly tool?: TemplateEditorCanvasTool;
  readonly disabled?: boolean;
  readonly onSelectNode: (nodeId: string) => void;
  readonly onToolChange?: (tool: TemplateEditorCanvasTool) => void;
  readonly onSelectionChange?: (nodeIds: string[], primaryId: string) => void;
  readonly onGeometryCommit?: (nodeId: string, geometry: TemplateEditorCanvasGeometry) => void;
  readonly onDeleteSelection?: () => void;
  readonly onReorderNode?: (
    nodeId: string,
    operation: TemplateEditorCanvasReorderOperation,
  ) => void;
  readonly assetTransport?: TemplateEditorAssetTransport;
  readonly assetResources?: TemplateEditorCanvasAssetResources;
  readonly onInsertAt?: (kind: TemplateCanvasDropKind, xMm: number, yMm: number) => void;
}

export type TemplateCanvasDropKind =
  | 'frame' | 'stack' | 'text' | 'image' | 'rect' | 'ellipse' | 'line'
  | 'shape' | 'polygon' | 'polyline' | 'path' | 'qrCode' | 'barcode';

interface TemplateEditorCanvasImageResource {
  readonly url: string;
  dispose(): void;
}

interface TemplateEditorCanvasFontResource {
  readonly family: string;
  dispose(): void;
}

export interface TemplateEditorCanvasAssetResources {
  createImage(blob: Blob): TemplateEditorCanvasImageResource;
  loadFont(assetId: string, blob: Blob): Promise<TemplateEditorCanvasFontResource | null>;
}

const defaultTemplateEditorCanvasAssetResources: TemplateEditorCanvasAssetResources = {
  createImage(blob) {
    const url = URL.createObjectURL(blob);
    return { url, dispose: () => URL.revokeObjectURL(url) };
  },
  async loadFont(assetId, blob) {
    if (typeof FontFace === 'undefined' || !document.fonts) return null;
    const family = `RenderWeaveAsset-${assetId}`;
    const face = await new FontFace(family, await blob.arrayBuffer()).load();
    document.fonts.add(face);
    return { family, dispose: () => document.fonts.delete(face) };
  },
};

const RESIZE_HANDLES = ['nw', 'n', 'ne', 'e', 'se', 's', 'sw', 'w'] as const;

interface CanvasGeometryInteraction {
  readonly pointerId: number;
  readonly captureTarget: HTMLElement;
  readonly node: AuthoredCanvasNode;
  readonly mode: 'move' | 'resize';
  readonly handle?: CanvasResizeHandle;
  readonly startClientX: number;
  readonly startClientY: number;
}

interface CanvasGeometryPreview {
  readonly nodeId: string;
  readonly rect: CanvasRect;
  readonly geometry: TemplateEditorCanvasGeometry;
}

interface CanvasContextMenuState {
  readonly nodeId: string;
  readonly x: number;
  readonly y: number;
  readonly triggerElement: HTMLElement;
}

interface CanvasZOrderAvailability {
  readonly canRaise: boolean;
  readonly canLower: boolean;
}

export function TemplateEditorCanvas({
  workingCopy,
  nodes,
  selectedNodeId,
  selectedNodeIds,
  tool,
  disabled = false,
  onSelectNode,
  onToolChange,
  onSelectionChange,
  onGeometryCommit,
  onDeleteSelection,
  onReorderNode,
  onInsertAt,
  assetTransport,
  assetResources = defaultTemplateEditorCanvasAssetResources,
}: TemplateEditorCanvasProps) {
  const canvas = objectOrNull(workingCopy.designDsl.designRoot);
  const widthMm = positiveTemplateNumber(canvas?.widthMm) ?? 210;
  const heightMm = positiveTemplateNumber(canvas?.heightMm) ?? 297;
  const artboardSize = useMemo(() => ({
    width: widthMm * CANVAS_PX_PER_MM,
    height: heightMm * CANVAS_PX_PER_MM,
  }), [heightMm, widthMm]);
  const authoredNodes = useMemo(
    () => projectAuthoredCanvasNodes(canvas, CANVAS_PX_PER_MM),
    [canvas],
  );
  const selectedIds = useMemo(
    () => new Set(selectedNodeIds ?? [selectedNodeId]),
    [selectedNodeId, selectedNodeIds],
  );
  const zOrderAvailability = useMemo(() => projectZOrderAvailability(nodes), [nodes]);
  const selected = nodes.find((node) => node.nodeId === selectedNodeId) ?? nodes[0];
  const viewportRef = useRef<HTMLDivElement>(null);
  const contextMenuRef = useRef<HTMLDivElement>(null);
  const viewModeRef = useRef<'fit' | 'custom'>('fit');
  const panRef = useRef<{ pointerId: number; x: number; y: number } | null>(null);
  const geometryInteractionRef = useRef<CanvasGeometryInteraction | null>(null);
  const spacePressedRef = useRef(false);
  const pendingContextFocusRestoreRef = useRef<CanvasContextMenuState | null>(null);
  const [uncontrolledTool, setUncontrolledTool] = useState<TemplateEditorCanvasTool>(
    tool ?? 'select',
  );
  const [panning, setPanning] = useState(false);
  const [geometryPreview, setGeometryPreview] = useState<CanvasGeometryPreview | null>(null);
  const [contextMenu, setContextMenu] = useState<CanvasContextMenuState | null>(null);
  const [transform, setTransform] = useState<CanvasViewportTransform>({
    scale: 1,
    x: FIT_PADDING,
    y: FIT_PADDING,
  });
  const activeTool = tool ?? uncontrolledTool;

  const changeTool = useCallback((nextTool: TemplateEditorCanvasTool) => {
    if (tool === undefined) setUncontrolledTool(nextTool);
    onToolChange?.(nextTool);
  }, [onToolChange, tool]);

  const selectOnly = useCallback((nodeId: string) => {
    onSelectNode(nodeId);
    onSelectionChange?.([nodeId], nodeId);
  }, [onSelectNode, onSelectionChange]);

  const closeContextMenu = useCallback((restoreFocus: boolean) => {
    setContextMenu((current) => {
      pendingContextFocusRestoreRef.current = restoreFocus ? current : null;
      return null;
    });
  }, []);

  useLayoutEffect(() => {
    if (contextMenu || !pendingContextFocusRestoreRef.current) return;
    const request = pendingContextFocusRestoreRef.current;
    pendingContextFocusRestoreRef.current = null;
    restoreCanvasContextFocus(request.triggerElement, viewportRef.current);
  }, [contextMenu]);

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && contextMenu) {
        event.preventDefault();
        closeContextMenu(true);
        return;
      }
      if (event.key === 'Escape' && activeTool === 'pan') {
        event.preventDefault();
        changeTool('select');
        return;
      }
      if (isInteractiveShortcutTarget(event.target)) return;
      const key = event.key.toLowerCase();
      if ((event.ctrlKey || event.metaKey) && !event.altKey && key === 'a') {
        const nodeIds = nodes
          .filter((node) => node.kind !== 'canvas')
          .map((node) => node.nodeId);
        const firstNodeId = nodeIds[0];
        if (!firstNodeId) return;
        event.preventDefault();
        const primaryId = nodeIds.includes(selectedNodeId) ? selectedNodeId : firstNodeId;
        onSelectionChange?.(nodeIds, primaryId);
        return;
      }
      if (event.ctrlKey || event.metaKey || event.altKey) return;
      if (key === 'v') {
        event.preventDefault();
        changeTool('select');
      } else if (key === 'h') {
        event.preventDefault();
        changeTool('pan');
      } else if (event.key === ' ' && !event.repeat) {
        event.preventDefault();
        spacePressedRef.current = true;
      }
    };
    const onKeyUp = (event: KeyboardEvent) => {
      if (event.key === ' ') spacePressedRef.current = false;
    };
    const onWindowBlur = () => {
      spacePressedRef.current = false;
    };
    window.addEventListener('keydown', onKeyDown);
    window.addEventListener('keyup', onKeyUp);
    window.addEventListener('blur', onWindowBlur);
    return () => {
      window.removeEventListener('keydown', onKeyDown);
      window.removeEventListener('keyup', onKeyUp);
      window.removeEventListener('blur', onWindowBlur);
    };
  }, [activeTool, changeTool, closeContextMenu, contextMenu, nodes, onSelectionChange, selectedNodeId]);

  useEffect(() => {
    if (!contextMenu) return;
    const menu = contextMenuRef.current;
    const firstEnabledAction = menu?.querySelector<HTMLButtonElement>(
      '[role="menuitem"]:not(:disabled)',
    );
    (firstEnabledAction ?? menu)?.focus();
  }, [contextMenu]);

  useEffect(() => {
    if (!contextMenu) return undefined;
    const dismissFromPointer = (event: PointerEvent) => {
      if (contextMenuRef.current?.contains(event.target as Node)) return;
      closeContextMenu(true);
    };
    window.addEventListener('pointerdown', dismissFromPointer, true);
    return () => window.removeEventListener('pointerdown', dismissFromPointer, true);
  }, [closeContextMenu, contextMenu]);

  useEffect(() => {
    if (!disabled || !geometryInteractionRef.current) return;
    releaseGeometryPointerCapture(geometryInteractionRef.current);
    geometryInteractionRef.current = null;
    setGeometryPreview(null);
  }, [disabled]);

  const fitToView = useCallback(() => {
    const viewport = viewportRef.current;
    if (!viewport) return;
    const bounds = viewport.getBoundingClientRect();
    const width = viewport.clientWidth || bounds.width;
    const height = viewport.clientHeight || bounds.height;
    if (width <= 0 || height <= 0) return;
    viewModeRef.current = 'fit';
    setTransform(fitCanvasViewport({ width, height }, artboardSize, FIT_PADDING));
  }, [artboardSize]);

  useEffect(() => {
    const viewport = viewportRef.current;
    if (!viewport) return undefined;
    fitToView();
    if (typeof ResizeObserver === 'undefined') return undefined;
    const observer = new ResizeObserver(() => {
      if (viewModeRef.current === 'fit') fitToView();
    });
    observer.observe(viewport);
    return () => observer.disconnect();
  }, [fitToView]);

  useEffect(() => {
    const viewport = viewportRef.current;
    if (!viewport) return undefined;
    const onWheel = (event: WheelEvent) => {
      event.preventDefault();
      const bounds = viewport.getBoundingClientRect();
      const pageHeight = viewport.clientHeight || bounds.height || 800;
      const deltaY = event.deltaY * (event.deltaMode === WheelEvent.DOM_DELTA_LINE
        ? 16
        : event.deltaMode === WheelEvent.DOM_DELTA_PAGE
          ? pageHeight
          : 1);
      viewModeRef.current = 'custom';
      setTransform((current) => wheelZoomCanvasViewport(
        current,
        deltaY,
        { x: event.clientX - bounds.left, y: event.clientY - bounds.top },
        event.ctrlKey,
      ));
    };
    viewport.addEventListener('wheel', onWheel, { passive: false });
    return () => viewport.removeEventListener('wheel', onWheel);
  }, []);

  const zoomAroundCenter = (scale: number) => {
    const viewport = viewportRef.current;
    if (!viewport) return;
    const bounds = viewport.getBoundingClientRect();
    const width = viewport.clientWidth || bounds.width;
    const height = viewport.clientHeight || bounds.height;
    viewModeRef.current = 'custom';
    setTransform((current) => zoomCanvasViewportAt(current, scale, {
      x: width / 2,
      y: height / 2,
    }));
  };

  const beginPan = (event: ReactPointerEvent<HTMLDivElement>) => {
    closeContextMenu(false);
    if (isCanvasControlTarget(event.target)) return;
    event.currentTarget.focus({ preventScroll: true });
    const accepted = event.button === 1 || (event.button === 0 && (
      spacePressedRef.current
      || activeTool === 'pan'
      || event.target === event.currentTarget
    ));
    if (!accepted) return;
    event.preventDefault();
    event.currentTarget.setPointerCapture?.(event.pointerId);
    panRef.current = { pointerId: event.pointerId, x: event.clientX, y: event.clientY };
    viewModeRef.current = 'custom';
    setPanning(true);
  };

  const geometryAtPointer = (
    interaction: CanvasGeometryInteraction,
    clientX: number,
    clientY: number,
  ): CanvasGeometryPreview => {
    const scale = transform.scale > 0 ? transform.scale : 1;
    const delta = {
      x: (clientX - interaction.startClientX) / scale,
      y: (clientY - interaction.startClientY) / scale,
    };
    const startRect: CanvasRect = {
      x: interaction.node.xPx,
      y: interaction.node.yPx,
      width: interaction.node.widthPx,
      height: interaction.node.heightPx,
    };
    const rect = interaction.mode === 'resize' && interaction.handle
      ? interaction.node.kind === 'qrCode'
        ? resizeStrictSquareCanvasRect(
          startRect,
          interaction.handle,
          delta,
          CANVAS_PX_PER_MM,
        )
        : resizeCanvasRect(startRect, interaction.handle, delta, CANVAS_PX_PER_MM)
      : {
        ...startRect,
        x: startRect.x + delta.x,
        y: startRect.y + delta.y,
      };
    return {
      nodeId: interaction.node.nodeId,
      rect,
      geometry: {
        xMm: interaction.node.xMm + (rect.x - startRect.x) / CANVAS_PX_PER_MM,
        yMm: interaction.node.yMm + (rect.y - startRect.y) / CANVAS_PX_PER_MM,
        widthMm: rect.width / CANVAS_PX_PER_MM,
        heightMm: rect.height / CANVAS_PX_PER_MM,
      },
    };
  };

  const beginGeometry = (
    event: ReactPointerEvent<HTMLElement>,
    node: AuthoredCanvasNode,
    handle?: CanvasResizeHandle,
  ) => {
    if (disabled || event.button !== 0 || activeTool !== 'select' || spacePressedRef.current) return;
    closeContextMenu(false);
    event.preventDefault();
    event.stopPropagation();
    const viewport = viewportRef.current;
    viewport?.focus({ preventScroll: true });
    event.currentTarget.setPointerCapture?.(event.pointerId);
    const interaction: CanvasGeometryInteraction = {
      pointerId: event.pointerId,
      captureTarget: event.currentTarget,
      node,
      mode: handle ? 'resize' : 'move',
      handle,
      startClientX: event.clientX,
      startClientY: event.clientY,
    };
    geometryInteractionRef.current = interaction;
    setGeometryPreview(geometryAtPointer(interaction, event.clientX, event.clientY));
  };

  const movePan = (event: ReactPointerEvent<HTMLDivElement>) => {
    const current = panRef.current;
    if (!current || current.pointerId !== event.pointerId) return;
    const delta = { x: event.clientX - current.x, y: event.clientY - current.y };
    panRef.current = { pointerId: current.pointerId, x: event.clientX, y: event.clientY };
    setTransform((viewport) => panCanvasViewport(viewport, delta));
  };

  const movePointer = (event: ReactPointerEvent<HTMLDivElement>) => {
    const interaction = geometryInteractionRef.current;
    if (interaction?.pointerId === event.pointerId) {
      setGeometryPreview(geometryAtPointer(interaction, event.clientX, event.clientY));
      return;
    }
    movePan(event);
  };

  const endPan = (event: ReactPointerEvent<HTMLDivElement>) => {
    if (panRef.current?.pointerId !== event.pointerId) return;
    panRef.current = null;
    event.currentTarget.releasePointerCapture?.(event.pointerId);
    setPanning(false);
  };

  const endPointer = (event: ReactPointerEvent<HTMLDivElement>) => {
    const interaction = geometryInteractionRef.current;
    if (interaction?.pointerId === event.pointerId) {
      const finalPreview = geometryAtPointer(interaction, event.clientX, event.clientY);
      geometryInteractionRef.current = null;
      releaseGeometryPointerCapture(interaction);
      setGeometryPreview(null);
      if (!disabled) {
        onGeometryCommit?.(interaction.node.nodeId, finalPreview.geometry);
      }
      return;
    }
    endPan(event);
  };

  const cancelPointer = (event: ReactPointerEvent<HTMLDivElement>) => {
    if (geometryInteractionRef.current?.pointerId === event.pointerId) {
      const interaction = geometryInteractionRef.current;
      geometryInteractionRef.current = null;
      releaseGeometryPointerCapture(interaction);
      setGeometryPreview(null);
      return;
    }
    endPan(event);
  };

  const openContextMenu = (
    event: ReactPointerEvent<HTMLElement> | ReactMouseEvent<HTMLElement>,
    nodeId: string,
  ) => {
    event.preventDefault();
    event.stopPropagation();
    if (selectedIds.has(nodeId)) {
      onSelectNode(nodeId);
      onSelectionChange?.(Array.from(selectedIds), nodeId);
    } else {
      selectOnly(nodeId);
    }
    const viewport = viewportRef.current;
    const bounds = viewport?.getBoundingClientRect();
    const width = viewport?.clientWidth || bounds?.width || 0;
    const height = viewport?.clientHeight || bounds?.height || 0;
    const requestedX = event.clientX - (bounds?.left ?? 0);
    const requestedY = event.clientY - (bounds?.top ?? 0);
    setContextMenu({
      nodeId,
      x: clampMenuCoordinate(requestedX, width, 168),
      y: clampMenuCoordinate(requestedY, height, 220),
      triggerElement: event.currentTarget,
    });
  };

  const reorderFromMenu = (operation: TemplateEditorCanvasReorderOperation) => {
    if (!contextMenu || isReorderDisabled(operation)) return;
    onReorderNode?.(contextMenu.nodeId, operation);
    closeContextMenu(true);
  };

  const isReorderDisabled = (operation: TemplateEditorCanvasReorderOperation): boolean => {
    if (disabled || !onReorderNode || !contextMenu) return true;
    const availability = zOrderAvailability.get(contextMenu.nodeId);
    return operation === 'front' || operation === 'forward'
      ? availability?.canRaise !== true
      : availability?.canLower !== true;
  };

  const background = colorValue(canvas?.backgroundColor) ?? 'var(--color-surface)';
  const percent = Math.round(transform.scale * 100);

  return (
    <div
      ref={viewportRef}
      className={`te-canvas-viewport${panning ? ' is-panning' : ''}${activeTool === 'pan' ? ' is-pan-tool' : ''}`}
      data-template-canvas-viewport=""
      data-canvas-scale={transform.scale}
      data-canvas-x={transform.x}
      data-canvas-y={transform.y}
      data-canvas-tool={activeTool}
      tabIndex={0}
      aria-label="本地草稿画布视口"
      onBlur={() => {
        spacePressedRef.current = false;
        panRef.current = null;
        if (geometryInteractionRef.current) {
          releaseGeometryPointerCapture(geometryInteractionRef.current);
        }
        geometryInteractionRef.current = null;
        setGeometryPreview(null);
        setPanning(false);
      }}
      onKeyDown={(event) => {
        if (event.key !== ' ' || event.repeat || isInteractiveShortcutTarget(event.target)) return;
        event.preventDefault();
        spacePressedRef.current = true;
      }}
      onKeyUp={(event) => {
        if (event.key === ' ') spacePressedRef.current = false;
      }}
      onPointerDown={beginPan}
      onPointerMove={movePointer}
      onPointerUp={endPointer}
      onPointerCancel={cancelPointer}
      onClick={(event) => {
        if (event.target === event.currentTarget && typeof canvas?.nodeId === 'string') {
          selectOnly(canvas.nodeId);
        }
      }}
    >
      <p className="sr-only">
        画布是非权威本地投影。滚轮围绕指针缩放；空格加拖拽或鼠标中键平移。结构树提供完整键盘选择路径。
      </p>
      <div className="te-canvas-ruler is-horizontal" aria-hidden="true">
        <span>0</span><span>{formatNumber(widthMm / 2)}</span><span>{formatNumber(widthMm)} mm</span>
      </div>
      <div className="te-canvas-ruler is-vertical" aria-hidden="true">
        <span>0</span><span>{formatNumber(heightMm / 2)}</span><span>{formatNumber(heightMm)} mm</span>
      </div>
      <div
        className="te-canvas-world"
        aria-hidden="true"
        style={{
          width: `${artboardSize.width}px`,
          height: `${artboardSize.height}px`,
          transform: `translate(${transform.x}px, ${transform.y}px) scale(${transform.scale})`,
        }}
      >
        <div
          className="te-artboard"
          data-template-canvas-node-id={typeof canvas?.nodeId === 'string' ? canvas.nodeId : undefined}
          style={{ width: `${artboardSize.width}px`, height: `${artboardSize.height}px`, background }}
          onClick={(event) => {
            event.stopPropagation();
            if (typeof canvas?.nodeId === 'string') selectOnly(canvas.nodeId);
          }}
          onDragOver={(event) => {
            if (disabled || !onInsertAt
              || !event.dataTransfer.types.includes(TEMPLATE_NODE_DRAG_MIME)) return;
            event.preventDefault();
            event.dataTransfer.dropEffect = 'copy';
          }}
          onDrop={(event) => {
            if (disabled || !onInsertAt) return;
            const kind = event.dataTransfer.getData(TEMPLATE_NODE_DRAG_MIME);
            if (!isTemplateCanvasDropKind(kind)) return;
            event.preventDefault();
            event.stopPropagation();
            const bounds = event.currentTarget.getBoundingClientRect();
            const scale = transform.scale > 0 ? transform.scale : 1;
            const xMm = Math.max(0, (event.clientX - bounds.left) / scale / CANVAS_PX_PER_MM);
            const yMm = Math.max(0, (event.clientY - bounds.top) / scale / CANVAS_PX_PER_MM);
            onInsertAt(kind, xMm, yMm);
          }}
        >
          {authoredNodes.map((node) => {
            const displayRect = geometryPreview?.nodeId === node.nodeId
              ? geometryPreview.rect
              : { x: node.xPx, y: node.yPx, width: node.widthPx, height: node.heightPx };
            return (
              <div
                key={node.nodeId}
                className={`te-canvas-node${isTemplateEditorVisualNodeKind(node.kind) ? ' is-visual' : ''}`}
                data-template-canvas-authored-node=""
                data-template-canvas-node-id={node.nodeId}
                data-template-canvas-node-kind={node.kind}
                style={{
                  left: displayRect.x,
                  top: displayRect.y,
                  width: displayRect.width,
                  height: displayRect.height,
                  background: isTemplateEditorVisualNodeKind(node.kind)
                    ? 'transparent' : node.fill ?? 'transparent',
                  borderColor: isTemplateEditorVisualNodeKind(node.kind)
                    ? undefined : node.stroke ?? undefined,
                  borderWidth: isTemplateEditorVisualNodeKind(node.kind)
                    ? undefined : node.stroke ? node.strokeWidthPx : undefined,
                  borderRadius: node.borderRadius,
                  opacity: node.opacity,
                  transform: node.transform,
                  transformOrigin: node.transformOrigin,
                }}
                title={`${node.displayName} · ${node.kind}`}
                onClick={(event) => {
                  event.stopPropagation();
                  selectOnly(node.nodeId);
                }}
                onContextMenu={(event) => openContextMenu(event, node.nodeId)}
              >
                {isTemplateEditorVisualNodeKind(node.kind) ? (
                  <CanvasVisualNode
                    node={node.value}
                    widthMm={displayRect.width / CANVAS_PX_PER_MM}
                    heightMm={displayRect.height / CANVAS_PX_PER_MM}
                    assetTransport={assetTransport}
                    assetResources={assetResources}
                  />
                ) : null}
              </div>
            );
          })}
        </div>
        <div className="te-canvas-editor-overlay" data-template-canvas-editor-overlay="">
          {authoredNodes.filter((node) => selectedIds.has(node.nodeId)).map((node) => {
            const isPrimary = node.nodeId === selectedNodeId;
            const displayRect = geometryPreview?.nodeId === node.nodeId
              ? geometryPreview.rect
              : { x: node.xPx, y: node.yPx, width: node.widthPx, height: node.heightPx };
            return (
              <div
                key={node.nodeId}
                className={`te-canvas-selection${isPrimary ? ' is-primary' : ''}`}
                data-template-canvas-selection={node.nodeId}
                style={{
                  left: displayRect.x,
                  top: displayRect.y,
                  width: displayRect.width,
                  height: displayRect.height,
                  borderRadius: node.borderRadius,
                  transform: node.transform,
                  transformOrigin: node.transformOrigin,
                }}
                onPointerDown={(event) => beginGeometry(event, node)}
                onContextMenu={(event) => openContextMenu(event, node.nodeId)}
              >
                {isPrimary ? (
                  <>
                    <span className="te-canvas-node-label">{node.displayName}</span>
                    {RESIZE_HANDLES.map((handle) => (
                      <i
                        key={handle}
                        data-resize-handle={handle}
                        onPointerDown={(event) => beginGeometry(event, node, handle)}
                      />
                    ))}
                  </>
                ) : null}
              </div>
            );
          })}
        </div>
      </div>
      {contextMenu ? (
        <div
          ref={contextMenuRef}
          className="te-canvas-context-menu"
          role="menu"
          aria-label="节点操作"
          tabIndex={-1}
          style={{ left: contextMenu.x, top: contextMenu.y }}
          onPointerDown={(event) => event.stopPropagation()}
        >
          <button
            type="button"
            role="menuitem"
            disabled={isReorderDisabled('front')}
            aria-disabled={isReorderDisabled('front')}
            onClick={() => reorderFromMenu('front')}
          >
            置于顶层
          </button>
          <button
            type="button"
            role="menuitem"
            disabled={isReorderDisabled('forward')}
            aria-disabled={isReorderDisabled('forward')}
            onClick={() => reorderFromMenu('forward')}
          >
            上移一层
          </button>
          <button
            type="button"
            role="menuitem"
            disabled={isReorderDisabled('backward')}
            aria-disabled={isReorderDisabled('backward')}
            onClick={() => reorderFromMenu('backward')}
          >
            下移一层
          </button>
          <button
            type="button"
            role="menuitem"
            disabled={isReorderDisabled('back')}
            aria-disabled={isReorderDisabled('back')}
            onClick={() => reorderFromMenu('back')}
          >
            置于底层
          </button>
          <span role="separator" />
          <button
            type="button"
            role="menuitem"
            className="is-danger"
            disabled={disabled || !onDeleteSelection}
            aria-disabled={disabled || !onDeleteSelection}
            onClick={() => {
              if (disabled) return;
              onDeleteSelection?.();
              closeContextMenu(true);
            }}
          >删除</button>
        </div>
      ) : null}
      <div className="te-canvas-breadcrumb">
        <strong>{selected?.displayName ?? '画布'}</strong>
        <span>{selected ? nodeGeometrySummary(selected.value) : `${formatNumber(widthMm)} × ${formatNumber(heightMm)} mm`}</span>
      </div>
      <div className="te-canvas-zoom" aria-label="画布缩放">
        <button
          type="button"
          aria-label="缩小画布"
          onClick={() => zoomAroundCenter(transform.scale / 1.25)}
        ><Minus aria-hidden="true" size={14} /></button>
        <button
          type="button"
          aria-label="重置画布缩放到 100%"
          onClick={() => zoomAroundCenter(1)}
        >{percent}%</button>
        <button
          type="button"
          aria-label="放大画布"
          onClick={() => zoomAroundCenter(transform.scale * 1.25)}
        ><Plus aria-hidden="true" size={14} /></button>
        <button type="button" aria-label="适合画板" onClick={fitToView}>
          <Maximize2 aria-hidden="true" size={14} />适合画板
        </button>
      </div>
      <div className="te-canvas-status" aria-hidden="true">
        <span>{formatNumber(widthMm)} × {formatNumber(heightMm)} mm · {nodes.length} 图层</span>
        <span>滚轮缩放 · 空格拖拽 / 中键平移</span>
        <span>本地草稿 · 非权威</span>
      </div>
    </div>
  );
}

/**
 * QR has one physical side length, not two independently authored dimensions.
 * Corner handles keep the opposite corner fixed and use the dominant pointer axis;
 * edge handles keep the opposite edge fixed and preserve the orthogonal center.
 */
function resizeStrictSquareCanvasRect(
  current: CanvasRect,
  handle: CanvasResizeHandle,
  delta: Readonly<{ x: number; y: number }>,
  minimumSize: number,
): CanvasRect {
  const freelyResized = resizeCanvasRect(current, handle, delta, minimumSize);
  const startRight = current.x + current.width;
  const startBottom = current.y + current.height;
  const startCenterX = current.x + current.width / 2;
  const startCenterY = current.y + current.height / 2;
  const horizontalChange = Math.abs(freelyResized.width - current.width);
  const verticalChange = Math.abs(freelyResized.height - current.height);
  const hasHorizontalAxis = handle.includes('e') || handle.includes('w');
  const hasVerticalAxis = handle.includes('n') || handle.includes('s');
  const side = hasHorizontalAxis && hasVerticalAxis
    ? horizontalChange >= verticalChange ? freelyResized.width : freelyResized.height
    : hasHorizontalAxis ? freelyResized.width : freelyResized.height;

  return {
    x: handle.includes('w')
      ? startRight - side
      : handle.includes('e')
        ? current.x
        : startCenterX - side / 2,
    y: handle.includes('n')
      ? startBottom - side
      : handle.includes('s')
        ? current.y
        : startCenterY - side / 2,
    width: side,
    height: side,
  };
}

function projectZOrderAvailability(
  nodes: readonly EditorNodeProjection[],
): ReadonlyMap<string, CanvasZOrderAvailability> {
  const ancestry: string[] = [];
  const siblingsByParent = new Map<string | null, string[]>();
  for (const node of nodes) {
    const depth = Number.isInteger(node.depth) && node.depth >= 0 ? node.depth : 0;
    if (ancestry.length > depth) ancestry.length = depth;
    const parentId = depth > 0 ? ancestry[depth - 1] ?? null : null;
    const siblings = siblingsByParent.get(parentId) ?? [];
    siblings.push(node.nodeId);
    siblingsByParent.set(parentId, siblings);
    ancestry[depth] = node.nodeId;
    ancestry.length = depth + 1;
  }

  const result = new Map<string, CanvasZOrderAvailability>();
  for (const siblings of siblingsByParent.values()) {
    siblings.forEach((nodeId, index) => {
      result.set(nodeId, {
        canRaise: index < siblings.length - 1,
        canLower: index > 0,
      });
    });
  }
  return result;
}

function projectAuthoredCanvasNodes(
  canvas: Record<string, unknown> | null,
  pixelsPerMm: number,
): AuthoredCanvasNode[] {
  if (!canvas || !Array.isArray(canvas.children)) return [];
  const projected: AuthoredCanvasNode[] = [];
  const visit = (children: unknown[], originXmm: number, originYmm: number) => {
    for (const childValue of children) {
      const child = objectOrNull(childValue);
      const placement = objectOrNull(child?.placement);
      if (!child || !placement || placement.type !== 'ABSOLUTE'
        || placement.widthMode !== 'FIXED' || placement.heightMode !== 'FIXED') {
        continue;
      }
      const xMm = finiteTemplateNumber(placement.xMm);
      const yMm = finiteTemplateNumber(placement.yMm);
      const widthMm = positiveTemplateNumber(placement.widthMm);
      const heightMm = positiveTemplateNumber(placement.heightMm);
      if (xMm === null || yMm === null || widthMm === null || heightMm === null
        || typeof child.nodeId !== 'string' || typeof child.kind !== 'string') {
        continue;
      }
      const x = originXmm + xMm;
      const y = originYmm + yMm;
      const fill = objectOrNull(child.fill);
      const stroke = objectOrNull(child.stroke);
      const transform = objectOrNull(child.transform);
      const scaleX = finiteTemplateNumber(transform?.scaleX) ?? 1;
      const scaleY = finiteTemplateNumber(transform?.scaleY) ?? 1;
      const rotation = finiteTemplateNumber(transform?.rotationDeg) ?? 0;
      const originX = finiteTemplateNumber(transform?.originX) ?? 0.5;
      const originY = finiteTemplateNumber(transform?.originY) ?? 0.5;
      projected.push({
        nodeId: child.nodeId,
        kind: child.kind,
        displayName: typeof child.displayName === 'string' && child.displayName.length > 0
          ? child.displayName
          : child.kind,
        xMm,
        yMm,
        widthMm,
        heightMm,
        xPx: x * pixelsPerMm,
        yPx: y * pixelsPerMm,
        widthPx: widthMm * pixelsPerMm,
        heightPx: heightMm * pixelsPerMm,
        fill: colorValue(fill?.color),
        stroke: colorValue(stroke?.color),
        strokeWidthPx: (positiveTemplateNumber(stroke?.widthMm) ?? 0.25) * pixelsPerMm,
        borderRadius: cornerRadiusValue(child.cornerRadii, pixelsPerMm, child.kind),
        opacity: boundedOpacity(child.opacity),
        transform: rotation !== 0 || scaleX !== 1 || scaleY !== 1
          ? `rotate(${rotation}deg) scale(${scaleX}, ${scaleY})`
          : undefined,
        transformOrigin: `${originX * 100}% ${originY * 100}%`,
        value: child,
      });
      if (Array.isArray(child.children)) visit(child.children, x, y);
    }
  };
  visit(canvas.children, 0, 0);
  return projected;
}

interface CanvasVisualResourceState {
  readonly imagePreviewUrl?: string;
  readonly imageAlt?: string;
  readonly fontFamilies: ReadonlyMap<string, string>;
  readonly warning?: string;
}

function CanvasVisualNode({
  node,
  widthMm,
  heightMm,
  assetTransport,
  assetResources,
}: {
  node: Readonly<Record<string, unknown>>;
  widthMm: number;
  heightMm: number;
  assetTransport?: TemplateEditorAssetTransport;
  assetResources: TemplateEditorCanvasAssetResources;
}) {
  const [resources, setResources] = useState<CanvasVisualResourceState>({
    fontFamilies: new Map(),
  });
  const signature = visualAssetSignature(node);

  useEffect(() => {
    if (!assetTransport || signature.length === 0) {
      let active = true;
      queueMicrotask(() => {
        if (active) setResources({ fontFamilies: new Map() });
      });
      return () => {
        active = false;
      };
    }
    const controller = new AbortController();
    const disposables: Array<{ dispose(): void }> = [];
    queueMicrotask(() => {
      if (!controller.signal.aborted) setResources({ fontFamilies: new Map() });
    });
    void loadCanvasVisualResources(
      node,
      assetTransport,
      assetResources,
      controller.signal,
      disposables,
    ).then((next) => {
      if (!controller.signal.aborted) setResources(next);
    }).catch((error: unknown) => {
      if (!controller.signal.aborted && !isAbortError(error)) {
        setResources({ fontFamilies: new Map(), warning: 'Asset 预览暂不可用' });
      }
    });
    return () => {
      controller.abort();
      disposables.splice(0).forEach((resource) => resource.dispose());
    };
  }, [assetResources, assetTransport, node, signature]);

  return (
    <div className="te-canvas-visual" data-template-asset-warning={resources.warning ?? undefined}>
      <TemplateEditorVisualNode
        node={node}
        widthMm={widthMm}
        heightMm={heightMm}
        pixelsPerMm={CANVAS_PX_PER_MM}
        resources={{
          imagePreviewUrl: resources.imagePreviewUrl,
          imageAlt: resources.imageAlt,
          resolveFontFamily: (assetId) => resources.fontFamilies.get(assetId),
        }}
      />
      {resources.warning ? <span className="te-canvas-asset-warning">{resources.warning}</span> : null}
    </div>
  );
}

async function loadCanvasVisualResources(
  node: Readonly<Record<string, unknown>>,
  transport: TemplateEditorAssetTransport,
  factory: TemplateEditorCanvasAssetResources,
  signal: AbortSignal,
  disposables: Array<{ dispose(): void }>,
): Promise<CanvasVisualResourceState> {
  if (node.kind === 'image') {
    const assetId = assetIdFromRef(node.imageRef);
    if (!assetId) return { fontFamilies: new Map(), warning: '图片 AssetRef 无效' };
    const resolution = await resolveTemplateAssetRef({ assetId }, 'IMAGE', transport, signal);
    if (resolution.state !== 'active') {
      return { fontFamilies: new Map(), warning: assetResolutionWarning(resolution, '图片') };
    }
    const image = factory.createImage(await transport.previewCurrent(assetId, signal));
    if (signal.aborted) {
      image.dispose();
      throw abortError();
    }
    disposables.push(image);
    return {
      imagePreviewUrl: image.url,
      imageAlt: `${resolution.asset.displayName} · 本地草稿预览`,
      fontFamilies: new Map(),
    };
  }
  if (node.kind !== 'text') return { fontFamilies: new Map() };
  const assetIds = textFontAssetIds(node);
  const fontFamilies = new Map<string, string>();
  const warnings: string[] = [];
  for (const assetId of assetIds) {
    const resolution = await resolveTemplateAssetRef({ assetId }, 'FONT', transport, signal);
    if (resolution.state !== 'active') {
      warnings.push(assetResolutionWarning(resolution, '字体'));
      continue;
    }
    const font = await factory.loadFont(
      assetId,
      await transport.previewCurrent(assetId, signal),
    );
    if (!font) {
      warnings.push('当前浏览器无法加载字体 Asset');
      continue;
    }
    if (signal.aborted) {
      font.dispose();
      throw abortError();
    }
    disposables.push(font);
    fontFamilies.set(assetId, font.family);
  }
  return {
    fontFamilies,
    ...(warnings.length > 0 ? { warning: warnings[0] } : {}),
  };
}

function visualAssetSignature(node: Readonly<Record<string, unknown>>): string {
  if (node.kind === 'image') return assetIdFromRef(node.imageRef) ?? 'invalid-image-ref';
  return node.kind === 'text' ? textFontAssetIds(node).join('|') : '';
}

function textFontAssetIds(node: Readonly<Record<string, unknown>>): string[] {
  if (!Array.isArray(node.runs)) return [];
  return [...new Set(node.runs.map((run) => assetIdFromRef(objectOrNull(run)?.fontRef))
    .filter((assetId): assetId is string => assetId !== null))];
}

function assetIdFromRef(value: unknown): string | null {
  const ref = objectOrNull(value);
  return typeof ref?.assetId === 'string' && ref.assetId.length > 0 ? ref.assetId : null;
}

function assetResolutionWarning(resolution: TemplateAssetResolution, label: string): string {
  switch (resolution.state) {
    case 'missing': return `${label} Asset 不存在`;
    case 'deleted': return `${label} Asset 已删除`;
    case 'kind-mismatch': return `${label} Asset 类型不匹配`;
    case 'unavailable': return `${label} Asset 暂不可读取`;
    case 'active': return '';
  }
}

const TEMPLATE_CANVAS_DROP_KINDS: ReadonlySet<string> = new Set([
  'frame', 'stack', 'text', 'image', 'rect', 'ellipse', 'line', 'shape',
  'polygon', 'polyline', 'path', 'qrCode', 'barcode',
]);

function isTemplateCanvasDropKind(value: unknown): value is TemplateCanvasDropKind {
  return typeof value === 'string' && TEMPLATE_CANVAS_DROP_KINDS.has(value);
}

function isAbortError(error: unknown): boolean {
  return typeof error === 'object' && error !== null && 'name' in error && error.name === 'AbortError';
}

function abortError(): DOMException {
  return new DOMException('Asset preview was aborted', 'AbortError');
}

function nodeGeometrySummary(node: Record<string, unknown>): string {
  const placement = objectOrNull(node.placement);
  const width = positiveTemplateNumber(placement?.widthMm);
  const height = positiveTemplateNumber(placement?.heightMm);
  const x = finiteTemplateNumber(placement?.xMm);
  const y = finiteTemplateNumber(placement?.yMm);
  if (width !== null && height !== null && x !== null && y !== null) {
    return `${formatNumber(width)}×${formatNumber(height)} mm @ ${formatNumber(x)}, ${formatNumber(y)}`;
  }
  return typeof placement?.type === 'string' ? placement.type : 'Canvas root';
}

function cornerRadiusValue(value: unknown, pixelsPerMm: number, kind: string): string | undefined {
  if (kind === 'ellipse') return '50%';
  const radii = objectOrNull(value);
  if (!radii) return undefined;
  const values = ['topLeftMm', 'topRightMm', 'bottomRightMm', 'bottomLeftMm']
    .map((key) => Math.max(0, finiteTemplateNumber(radii[key]) ?? 0) * pixelsPerMm);
  return `${values[0]}px ${values[1]}px ${values[2]}px ${values[3]}px`;
}

function boundedOpacity(value: unknown): number {
  const opacity = finiteTemplateNumber(value);
  return opacity === null ? 1 : Math.min(1, Math.max(0, opacity));
}

function colorValue(value: unknown): string | null {
  return typeof value === 'string' && /^#[0-9A-Fa-f]{8}$/.test(value) ? value : null;
}

function formatNumber(value: number): string {
  return Number.isInteger(value) ? String(value) : value.toFixed(2).replace(/0+$/, '').replace(/\.$/, '');
}

function isInteractiveShortcutTarget(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) return false;
  return target.closest([
    'button',
    'a',
    'input',
    'textarea',
    'select',
    '[contenteditable="true"]',
    '[role="button"]',
    '[role="link"]',
    '[role="menuitem"]',
  ].join(',')) !== null;
}

function clampMenuCoordinate(requested: number, viewportSize: number, menuSize: number): number {
  const inset = 8;
  if (!Number.isFinite(requested) || !Number.isFinite(viewportSize) || viewportSize <= 0) {
    return inset;
  }
  return Math.max(inset, Math.min(requested, Math.max(inset, viewportSize - menuSize - inset)));
}

function isCanvasControlTarget(target: EventTarget | null): boolean {
  return target instanceof Element && target.closest('button, a, input, textarea, select, [role="menu"]') !== null;
}

function restoreCanvasContextFocus(
  triggerElement: HTMLElement,
  viewport: HTMLElement | null,
): void {
  if (!triggerElement.closest('[aria-hidden="true"]') && focusConnectedElement(triggerElement)) return;
  focusConnectedElement(viewport);
}

function focusConnectedElement(element: HTMLElement | null): boolean {
  if (!element?.isConnected) return false;
  element.focus({ preventScroll: true });
  return document.activeElement === element;
}

function releaseGeometryPointerCapture(interaction: CanvasGeometryInteraction): void {
  if (interaction.captureTarget.hasPointerCapture?.(interaction.pointerId)) {
    interaction.captureTarget.releasePointerCapture?.(interaction.pointerId);
  }
}
