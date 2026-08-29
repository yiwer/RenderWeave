import { Maximize2, Minus, Plus } from 'lucide-react';
import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
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
  wheelZoomCanvasViewport,
  zoomCanvasViewportAt,
  type CanvasViewportTransform,
} from './template-canvas-viewport';

export const CANVAS_PX_PER_MM = 4;

const FIT_PADDING = 48;

interface AuthoredCanvasNode {
  readonly nodeId: string;
  readonly kind: string;
  readonly displayName: string;
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
}

export function TemplateEditorCanvas({
  workingCopy,
  nodes,
  selectedNodeId,
  onSelectNode,
}: {
  workingCopy: CanonicalDesignWorkingCopy;
  nodes: readonly EditorNodeProjection[];
  selectedNodeId: string;
  onSelectNode: (nodeId: string) => void;
}) {
  const canvas = objectOrNull(workingCopy.designDsl.designRoot);
  const widthMm = positiveNumber(canvas?.widthMm) ?? 210;
  const heightMm = positiveNumber(canvas?.heightMm) ?? 297;
  const artboardSize = useMemo(() => ({
    width: widthMm * CANVAS_PX_PER_MM,
    height: heightMm * CANVAS_PX_PER_MM,
  }), [heightMm, widthMm]);
  const authoredNodes = useMemo(
    () => projectAuthoredCanvasNodes(canvas, CANVAS_PX_PER_MM),
    [canvas],
  );
  const selected = nodes.find((node) => node.nodeId === selectedNodeId) ?? nodes[0];
  const viewportRef = useRef<HTMLDivElement>(null);
  const viewModeRef = useRef<'fit' | 'custom'>('fit');
  const panRef = useRef<{ pointerId: number; x: number; y: number } | null>(null);
  const spacePressedRef = useRef(false);
  const [panning, setPanning] = useState(false);
  const [transform, setTransform] = useState<CanvasViewportTransform>({
    scale: 1,
    x: FIT_PADDING,
    y: FIT_PADDING,
  });

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
    event.currentTarget.focus({ preventScroll: true });
    const accepted = event.button === 1 || (event.button === 0 && spacePressedRef.current);
    if (!accepted) return;
    event.preventDefault();
    event.currentTarget.setPointerCapture?.(event.pointerId);
    panRef.current = { pointerId: event.pointerId, x: event.clientX, y: event.clientY };
    viewModeRef.current = 'custom';
    setPanning(true);
  };

  const movePan = (event: ReactPointerEvent<HTMLDivElement>) => {
    const current = panRef.current;
    if (!current || current.pointerId !== event.pointerId) return;
    const delta = { x: event.clientX - current.x, y: event.clientY - current.y };
    panRef.current = { pointerId: current.pointerId, x: event.clientX, y: event.clientY };
    setTransform((viewport) => panCanvasViewport(viewport, delta));
  };

  const endPan = (event: ReactPointerEvent<HTMLDivElement>) => {
    if (panRef.current?.pointerId !== event.pointerId) return;
    panRef.current = null;
    event.currentTarget.releasePointerCapture?.(event.pointerId);
    setPanning(false);
  };

  const background = colorValue(canvas?.backgroundColor) ?? 'var(--color-surface)';
  const percent = Math.round(transform.scale * 100);

  return (
    <div
      ref={viewportRef}
      className={`te-canvas-viewport${panning ? ' is-panning' : ''}`}
      data-template-canvas-viewport=""
      data-canvas-scale={transform.scale}
      tabIndex={0}
      aria-label="本地草稿画布视口"
      onBlur={() => {
        spacePressedRef.current = false;
        panRef.current = null;
        setPanning(false);
      }}
      onKeyDown={(event) => {
        if (event.key !== ' ' || event.repeat) return;
        event.preventDefault();
        spacePressedRef.current = true;
      }}
      onKeyUp={(event) => {
        if (event.key === ' ') spacePressedRef.current = false;
      }}
      onPointerDown={beginPan}
      onPointerMove={movePan}
      onPointerUp={endPan}
      onPointerCancel={endPan}
      onClick={(event) => {
        if (event.target === event.currentTarget && typeof canvas?.nodeId === 'string') {
          onSelectNode(canvas.nodeId);
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
            if (typeof canvas?.nodeId === 'string') onSelectNode(canvas.nodeId);
          }}
        >
          {authoredNodes.map((node) => {
            const isSelected = node.nodeId === selectedNodeId;
            return (
              <div
                key={node.nodeId}
                className={`te-canvas-node${isSelected ? ' is-selected' : ''}`}
                data-template-canvas-node-id={node.nodeId}
                data-template-canvas-node-kind={node.kind}
                style={{
                  left: node.xPx,
                  top: node.yPx,
                  width: node.widthPx,
                  height: node.heightPx,
                  background: node.fill ?? 'transparent',
                  borderColor: node.stroke ?? undefined,
                  borderWidth: node.stroke ? node.strokeWidthPx : undefined,
                  borderRadius: node.borderRadius,
                  opacity: node.opacity,
                  transform: node.transform,
                  transformOrigin: node.transformOrigin,
                }}
                title={`${node.displayName} · ${node.kind}`}
                onClick={(event) => {
                  event.stopPropagation();
                  onSelectNode(node.nodeId);
                }}
              >
                {isSelected ? (
                  <>
                    <span className="te-canvas-node-label">{node.displayName}</span>
                    <i data-handle="nw" /><i data-handle="ne" />
                    <i data-handle="se" /><i data-handle="sw" />
                  </>
                ) : null}
              </div>
            );
          })}
        </div>
      </div>
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
      const xMm = finiteNumber(placement.xMm);
      const yMm = finiteNumber(placement.yMm);
      const widthMm = positiveNumber(placement.widthMm);
      const heightMm = positiveNumber(placement.heightMm);
      if (xMm === null || yMm === null || widthMm === null || heightMm === null
        || typeof child.nodeId !== 'string' || typeof child.kind !== 'string') {
        continue;
      }
      const x = originXmm + xMm;
      const y = originYmm + yMm;
      const fill = objectOrNull(child.fill);
      const stroke = objectOrNull(child.stroke);
      const transform = objectOrNull(child.transform);
      const scaleX = finiteNumber(transform?.scaleX) ?? 1;
      const scaleY = finiteNumber(transform?.scaleY) ?? 1;
      const rotation = finiteNumber(transform?.rotationDeg) ?? 0;
      const originX = finiteNumber(transform?.originX) ?? 0.5;
      const originY = finiteNumber(transform?.originY) ?? 0.5;
      projected.push({
        nodeId: child.nodeId,
        kind: child.kind,
        displayName: typeof child.displayName === 'string' && child.displayName.length > 0
          ? child.displayName
          : child.kind,
        xPx: x * pixelsPerMm,
        yPx: y * pixelsPerMm,
        widthPx: widthMm * pixelsPerMm,
        heightPx: heightMm * pixelsPerMm,
        fill: colorValue(fill?.color),
        stroke: colorValue(stroke?.color),
        strokeWidthPx: (positiveNumber(stroke?.widthMm) ?? 0.25) * pixelsPerMm,
        borderRadius: cornerRadiusValue(child.cornerRadii, pixelsPerMm, child.kind),
        opacity: boundedOpacity(child.opacity),
        transform: rotation !== 0 || scaleX !== 1 || scaleY !== 1
          ? `rotate(${rotation}deg) scale(${scaleX}, ${scaleY})`
          : undefined,
        transformOrigin: `${originX * 100}% ${originY * 100}%`,
      });
      if (Array.isArray(child.children)) visit(child.children, x, y);
    }
  };
  visit(canvas.children, 0, 0);
  return projected;
}

function nodeGeometrySummary(node: Record<string, unknown>): string {
  const placement = objectOrNull(node.placement);
  const width = positiveNumber(placement?.widthMm);
  const height = positiveNumber(placement?.heightMm);
  const x = finiteNumber(placement?.xMm);
  const y = finiteNumber(placement?.yMm);
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
    .map((key) => Math.max(0, finiteNumber(radii[key]) ?? 0) * pixelsPerMm);
  return `${values[0]}px ${values[1]}px ${values[2]}px ${values[3]}px`;
}

function boundedOpacity(value: unknown): number {
  const opacity = finiteNumber(value);
  return opacity === null ? 1 : Math.min(1, Math.max(0, opacity));
}

function colorValue(value: unknown): string | null {
  return typeof value === 'string' && /^#[0-9A-Fa-f]{8}$/.test(value) ? value : null;
}

function finiteNumber(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}

function positiveNumber(value: unknown): number | null {
  const number = finiteNumber(value);
  return number !== null && number > 0 ? number : null;
}

function formatNumber(value: number): string {
  return Number.isInteger(value) ? String(value) : value.toFixed(2).replace(/0+$/, '').replace(/\.$/, '');
}
