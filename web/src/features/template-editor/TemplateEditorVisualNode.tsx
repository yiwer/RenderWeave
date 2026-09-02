import { useId, type CSSProperties, type ReactElement } from 'react';

import {
  DEFAULT_TEMPLATE_CANVAS_PX_PER_MM,
  isTemplateEditorVisualNodeKind,
  projectDesignGeometryPointsToAuthoredBox,
  projectDesignPathCommandsToAuthoredBox,
  templatePointsToCanvasPixels,
  type TemplateEditorVisualDesignNode,
} from './template-editor-visual-projection';

export interface TemplateEditorVisualResources {
  /** A short-lived browser-only URL. It is never part of DesignDSL. */
  readonly imagePreviewUrl?: string | null;
  readonly imageAlt?: string;
  /** Returns an already-loaded browser-only FontFace family for one AssetRef. */
  readonly resolveFontFamily?: (assetId: string) => string | null | undefined;
}

export interface TemplateEditorVisualNodeProps {
  /**
   * Structured sessions normally pass a generated DesignNode. The record form keeps
   * local hard-invalid drafts inspectable without pretending this projector validates them.
   */
  readonly node: TemplateEditorVisualDesignNode | Readonly<Record<string, unknown>>;
  /** Current authored layout box; resize previews pass their temporary dimensions here. */
  readonly widthMm: number;
  readonly heightMm: number;
  readonly pixelsPerMm?: number;
  readonly resources?: TemplateEditorVisualResources;
}

const VISUAL_ROOT_STYLE: CSSProperties = {
  position: 'absolute',
  inset: 0,
  boxSizing: 'border-box',
  width: '100%',
  height: '100%',
  overflow: 'hidden',
  pointerEvents: 'none',
};

const SVG_SURFACE_STYLE: CSSProperties = {
  display: 'block',
  width: '100%',
  height: '100%',
  overflow: 'hidden',
};

const FREE_VECTOR_SURFACE_STYLE: CSSProperties = {
  ...SVG_SURFACE_STYLE,
  overflow: 'visible',
};

const RESOURCE_PLACEHOLDER_STYLE: CSSProperties = {
  ...VISUAL_ROOT_STYLE,
  display: 'grid',
  placeItems: 'center',
  padding: '6px',
  background: 'rgba(245, 242, 232, 0.9)',
  color: 'rgba(45, 52, 54, 0.72)',
  fontFamily: 'system-ui, sans-serif',
  fontSize: '11px',
  lineHeight: 1.25,
  textAlign: 'center',
};

const LOCAL_DRAFT_BADGE_STYLE: CSSProperties = {
  position: 'absolute',
  right: '4px',
  bottom: '4px',
  maxWidth: 'calc(100% - 8px)',
  overflow: 'hidden',
  border: '1px solid rgba(41, 55, 56, 0.18)',
  borderRadius: '3px',
  padding: '1px 4px',
  background: 'rgba(255, 255, 255, 0.9)',
  color: 'rgba(25, 42, 43, 0.82)',
  fontFamily: 'system-ui, sans-serif',
  fontSize: '8px',
  fontWeight: 700,
  lineHeight: 1.35,
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
};

const MIN_STROKE_CENTERLINE_EXTENT_MM = 0.000001;

export function TemplateEditorVisualNode({
  node,
  widthMm,
  heightMm,
  pixelsPerMm = DEFAULT_TEMPLATE_CANVAS_PX_PER_MM,
  resources,
}: TemplateEditorVisualNodeProps) {
  const reactId = useId();
  const vectorClipId = `rw-vector-clip-${reactId.replace(/[^a-zA-Z0-9_-]/g, '')}`;
  const value = node as unknown as Readonly<Record<string, unknown>>;
  const kind = typeof value.kind === 'string' ? value.kind : '';
  if (!isTemplateEditorVisualNodeKind(kind)) return null;

  const safeWidthMm = positiveDimension(widthMm);
  const safeHeightMm = positiveDimension(heightMm);
  switch (kind) {
    case 'text':
      return renderText(value, safeWidthMm, safeHeightMm, pixelsPerMm, resources);
    case 'image':
      return renderImage(value, resources);
    case 'rect':
    case 'ellipse':
    case 'line':
    case 'polygon':
    case 'polyline':
    case 'path':
      return renderVector(value, kind, safeWidthMm, safeHeightMm, vectorClipId);
    case 'qrCode':
      return renderQrCodeDraft(value, widthMm, heightMm);
    case 'barcode':
      return renderBarcodeDraft(value);
    default:
      return null;
  }
}

function renderText(
  node: Readonly<Record<string, unknown>>,
  widthMm: number,
  heightMm: number,
  pixelsPerMm: number,
  resources: TemplateEditorVisualResources | undefined,
): ReactElement {
  const padding = readPadding(node.padding);
  const runs = Array.isArray(node.runs) ? node.runs : [];
  const horizontalAlign = node.horizontalAlign === 'CENTER'
    ? 'center'
    : node.horizontalAlign === 'RIGHT'
      ? 'right'
      : node.horizontalAlign === 'JUSTIFY' || node.horizontalAlign === 'SPACE_EVENLY'
        ? 'justify'
        : 'left';
  const verticalJustification = node.verticalAlign === 'CENTER'
    ? 'center'
    : node.verticalAlign === 'BOTTOM'
      ? 'flex-end'
      : node.verticalAlign === 'JUSTIFY' || node.verticalAlign === 'SPACE_EVENLY'
        ? 'space-between'
        : 'flex-start';
  const overflow = node.overflow === 'VISIBLE' ? 'visible' : 'hidden';
  const writingMode = node.writingMode === 'VERTICAL_RL' ? 'vertical-rl' : 'horizontal-tb';
  const maxLines = positiveInteger(node.maxLines);
  const lineBreak = node.lineBreak;
  const whiteSpace = lineBreak === 'NONE' ? 'pre' : 'pre-wrap';
  const textStroke = readTextStroke(node.stroke, pixelsPerMm);
  const bodyStyle: CSSProperties = {
    ...VISUAL_ROOT_STYLE,
    display: 'flex',
    flexDirection: 'column',
    justifyContent: verticalJustification,
    padding: `${padding.topMm * pixelsPerMm}px ${padding.rightMm * pixelsPerMm}px ${padding.bottomMm * pixelsPerMm}px ${padding.leftMm * pixelsPerMm}px`,
    overflow,
    color: '#111111',
    textAlign: horizontalAlign,
    writingMode,
  };
  const textFlowStyle: CSSProperties = {
    minWidth: 0,
    minHeight: 0,
    maxWidth: '100%',
    maxHeight: '100%',
    width: writingMode === 'horizontal-tb' ? '100%' : undefined,
    overflow: node.overflow === 'VISIBLE' ? 'visible' : 'hidden',
    overflowWrap: lineBreak === 'CHAR' ? 'anywhere' : 'normal',
    whiteSpace,
    wordBreak: lineBreak === 'CHAR' ? 'break-all' : 'normal',
    textOverflow: node.overflow === 'ELLIPSIS' ? 'ellipsis' : 'clip',
    ...(maxLines === null || writingMode !== 'horizontal-tb'
      ? {}
      : {
          display: '-webkit-box',
          WebkitBoxOrient: 'vertical',
          WebkitLineClamp: maxLines,
        }),
  };

  return (
    <div
      data-template-visual-kind="text"
      data-template-text-size-space="canvas-px"
      data-template-text-overflow={overflow}
      data-template-visual-box={`${formatNumber(widthMm)}x${formatNumber(heightMm)}`}
      style={bodyStyle}
    >
      <div style={textFlowStyle}>
        {runs.map((runValue, index) => {
          const run = recordOrNull(runValue);
          if (!run || typeof run.text !== 'string') return null;
          const fontSizePx = templatePointsToCanvasPixels(numberOr(run.fontSizePt, 12), pixelsPerMm);
          const fontRef = recordOrNull(run.fontRef);
          const assetId = typeof fontRef?.assetId === 'string' ? fontRef.assetId : '';
          const fontFamily = assetId.length > 0
            ? resources?.resolveFontFamily?.(assetId) ?? undefined
            : undefined;
          const decoration = run.decoration === 'UNDERLINE'
            ? 'underline'
            : run.decoration === 'LINE_THROUGH'
              ? 'line-through'
              : 'none';
          const letterSpacingPt = finiteNumber(run.letterSpacingPt);
          const letterSpacingFactor = finiteNumber(run.letterSpacingFactor);
          const letterSpacing = letterSpacingPt !== null
            ? `${templatePointsToCanvasPixels(letterSpacingPt, pixelsPerMm)}px`
            : letterSpacingFactor !== null
              ? `${fontSizePx * letterSpacingFactor}px`
              : undefined;
          const runStyle: CSSProperties = {
            color: validColor(run.color) ?? '#111111FF',
            fontFamily,
            fontSize: `${fontSizePx}px`,
            lineHeight: textLineHeight(node.lineHeight, pixelsPerMm),
            letterSpacing,
            textDecoration: decoration,
            WebkitTextStrokeColor: textStroke.color,
            WebkitTextStrokeWidth: textStroke.width,
          };
          return (
            <span
              key={`${assetId}:${index}`}
              data-template-text-run=""
              data-template-font-asset-id={assetId || undefined}
              data-template-font-preview={fontFamily ? 'ready' : 'fallback'}
              style={runStyle}
            >
              {run.text}
            </span>
          );
        })}
      </div>
    </div>
  );
}

function renderImage(
  node: Readonly<Record<string, unknown>>,
  resources: TemplateEditorVisualResources | undefined,
): ReactElement {
  const previewUrl = resources?.imagePreviewUrl;
  if (!previewUrl) {
    return (
      <div
        data-template-visual-kind="image"
        data-template-visual-resource="unavailable"
        style={RESOURCE_PLACEHOLDER_STYLE}
      >
        图片资产预览不可用
      </div>
    );
  }
  const objectFit = node.fit === 'COVER' ? 'cover' : node.fit === 'FILL' ? 'fill' : 'contain';
  const imageRendering = node.sampling === 'NEAREST' ? 'pixelated' : 'auto';
  const displayName = typeof node.displayName === 'string' && node.displayName.length > 0
    ? node.displayName
    : '图片';
  return (
    <img
      alt={resources.imageAlt ?? `${displayName} · 本地草稿预览`}
      data-template-visual-kind="image"
      data-template-visual-resource="ready"
      draggable={false}
      src={previewUrl}
      style={{
        ...VISUAL_ROOT_STYLE,
        display: 'block',
        objectFit,
        imageRendering,
      }}
    />
  );
}

function renderVector(
  node: Readonly<Record<string, unknown>>,
  kind: 'rect' | 'ellipse' | 'line' | 'polygon' | 'polyline' | 'path',
  widthMm: number,
  heightMm: number,
  clipId: string,
): ReactElement {
  const freeVector = kind === 'line' || kind === 'polygon' || kind === 'polyline' || kind === 'path';
  const stroke = readStroke(node.stroke);
  const fill = readFill(node.fill);
  let shape: ReactElement;
  switch (kind) {
    case 'rect': {
      const outerRadii = readCornerRadii(node.cornerRadii, widthMm, heightMm);
      const outerPath = roundedRectPath(0, 0, widthMm, heightMm, outerRadii);
      const inwardStroke = inwardRectStrokeGeometry(
        widthMm,
        heightMm,
        stroke.widthMm,
        outerRadii,
      );
      shape = (
        <>
          <defs>
            <clipPath id={clipId} clipPathUnits="userSpaceOnUse">
              <path d={outerPath} />
            </clipPath>
          </defs>
          <path data-template-vector-layer="fill" d={outerPath} fill={fill} />
          <path
            clipPath={`url(#${clipId})`}
            data-template-vector-layer="inward-stroke"
            d={inwardStroke.path}
            fill="none"
            {...svgStrokeProps(stroke)}
          />
        </>
      );
      break;
    }
    case 'ellipse': {
      const centerX = widthMm / 2;
      const centerY = heightMm / 2;
      const outerRadiusX = centerX;
      const outerRadiusY = centerY;
      const strokeRadiusX = inwardStrokeRadius(outerRadiusX, stroke.widthMm);
      const strokeRadiusY = inwardStrokeRadius(outerRadiusY, stroke.widthMm);
      shape = (
        <>
          <defs>
            <clipPath id={clipId} clipPathUnits="userSpaceOnUse">
              <ellipse cx={centerX} cy={centerY} rx={outerRadiusX} ry={outerRadiusY} />
            </clipPath>
          </defs>
          <ellipse
            cx={centerX}
            cy={centerY}
            data-template-vector-layer="fill"
            fill={fill}
            rx={outerRadiusX}
            ry={outerRadiusY}
          />
          <ellipse
            clipPath={`url(#${clipId})`}
            cx={centerX}
            cy={centerY}
            data-template-vector-layer="inward-stroke"
            fill="none"
            rx={strokeRadiusX}
            ry={strokeRadiusY}
            {...svgStrokeProps(stroke)}
          />
        </>
      );
      break;
    }
    case 'line': {
      const start = readPoint(node.start);
      const end = readPoint(node.end);
      const projected = projectDesignGeometryPointsToAuthoredBox([
        start ?? { xMm: 0, yMm: 0 },
        end ?? { xMm: widthMm, yMm: heightMm },
      ], {
        widthMm,
        heightMm,
      });
      const projectedStart = projected?.[0] ?? { xMm: 0, yMm: 0 };
      const projectedEnd = projected?.[1] ?? { xMm: widthMm, yMm: heightMm };
      shape = (
        <line
          x1={projectedStart.xMm}
          y1={projectedStart.yMm}
          x2={projectedEnd.xMm}
          y2={projectedEnd.yMm}
          {...svgStrokeProps(stroke)}
        />
      );
      break;
    }
    case 'polygon':
      shape = (
        <polygon
          points={projectedSvgPoints(node.points, widthMm, heightMm)}
          fill={fill}
          {...svgStrokeProps(stroke)}
        />
      );
      break;
    case 'polyline':
      shape = (
        <polyline
          points={projectedSvgPoints(node.points, widthMm, heightMm)}
          fill="none"
          {...svgStrokeProps(stroke)}
        />
      );
      break;
    case 'path':
      shape = (
        <path
          d={projectDesignPathCommandsToAuthoredBox(node.commands, {
            widthMm,
            heightMm,
          })}
          data-template-path-source="commands"
          fill={fill}
          fillRule={node.fillRule === 'EVEN_ODD' ? 'evenodd' : 'nonzero'}
          {...svgStrokeProps(stroke)}
        />
      );
      break;
  }
  return (
    <svg
      aria-hidden="true"
      data-template-stroke-projection={freeVector ? 'authored' : 'authored-inward'}
      data-template-visual-kind={kind}
      preserveAspectRatio="none"
      style={freeVector ? FREE_VECTOR_SURFACE_STYLE : SVG_SURFACE_STYLE}
      viewBox={`0 0 ${formatNumber(widthMm)} ${formatNumber(heightMm)}`}
    >
      {shape}
    </svg>
  );
}

function renderQrCodeDraft(
  node: Readonly<Record<string, unknown>>,
  widthMm: number,
  heightMm: number,
): ReactElement {
  if (!validQrLayoutBox(widthMm, heightMm)) {
    return (
      <div
        aria-label="二维码本地草稿无效：最终尺寸必须为正方形"
        data-template-preview-authority="non-certified-local-draft"
        data-template-preview-validity="invalid-layout"
        data-template-visual-kind="qrCode"
        role="img"
        style={{ ...RESOURCE_PLACEHOLDER_STYLE, gap: '4px' }}
      >
        <strong>二维码尺寸无效</strong>
        <span>最终尺寸必须为严格正方形</span>
      </div>
    );
  }
  const content = typeof node.content === 'string' ? node.content : '';
  const foreground = validColor(node.foregroundColor) ?? '#172B2CFF';
  const background = validColor(node.backgroundColor) ?? '#FFFFFFFF';
  const cells = draftQrCells(content);
  return (
    <div
      aria-label="二维码本地草稿示意，非认证输出"
      data-template-preview-authority="non-certified-local-draft"
      data-template-visual-kind="qrCode"
      role="img"
      style={{ ...VISUAL_ROOT_STYLE, background }}
    >
      <svg
        aria-hidden="true"
        preserveAspectRatio="xMidYMid meet"
        style={SVG_SURFACE_STYLE}
        viewBox="0 0 13 13"
      >
        <rect width="13" height="13" fill={background} />
        {cells.map((cell) => (
          <rect
            key={`${cell.x}:${cell.y}`}
            data-template-qr-cell=""
            x={cell.x}
            y={cell.y}
            width="1"
            height="1"
            fill={foreground}
          />
        ))}
        <path d="M 1 12 L 12 1" stroke={foreground} strokeOpacity="0.22" strokeWidth="0.65" />
      </svg>
      <span style={LOCAL_DRAFT_BADGE_STYLE}>本地草稿 · 非认证</span>
    </div>
  );
}

function renderBarcodeDraft(node: Readonly<Record<string, unknown>>): ReactElement {
  const value = typeof node.value === 'string' ? node.value : '';
  const foreground = validColor(node.foregroundColor) ?? '#172B2CFF';
  const background = validColor(node.backgroundColor) ?? '#FFFFFFFF';
  const bars = draftBarcodeBars(value);
  return (
    <div
      aria-label="条形码本地草稿示意，非认证输出"
      data-template-preview-authority="non-certified-local-draft"
      data-template-visual-kind="barcode"
      role="img"
      style={{ ...VISUAL_ROOT_STYLE, background }}
    >
      <svg aria-hidden="true" preserveAspectRatio="none" style={SVG_SURFACE_STYLE} viewBox="0 0 100 60">
        <rect width="100" height="60" fill={background} />
        {bars.map((bar, index) => (
          <rect
            key={`${bar.x}:${index}`}
            x={bar.x}
            y="5"
            width={bar.width}
            height={index % 4 === 0 ? 40 : 34}
            fill={foreground}
          />
        ))}
        <path d="M 4 51 L 96 9" stroke={foreground} strokeOpacity="0.14" strokeWidth="2" />
      </svg>
      <span style={LOCAL_DRAFT_BADGE_STYLE}>本地草稿 · 非认证</span>
    </div>
  );
}

function svgStrokeProps(stroke: StrokeProjection) {
  return {
    stroke: stroke.color,
    strokeWidth: stroke.widthMm,
    strokeLinecap: stroke.cap,
    strokeLinejoin: stroke.join,
    strokeMiterlimit: 4,
  } as const;
}

interface StrokeProjection {
  readonly color: string;
  readonly widthMm: number;
  readonly cap: 'butt' | 'round' | 'square';
  readonly join: 'miter' | 'round' | 'bevel';
}

function readStroke(value: unknown): StrokeProjection {
  const stroke = recordOrNull(value);
  return {
    color: validColor(stroke?.color) ?? 'none',
    widthMm: nonNegativeNumber(stroke?.widthMm) ?? 0,
    cap: stroke?.cap === 'ROUND' ? 'round' : stroke?.cap === 'SQUARE' ? 'square' : 'butt',
    join: stroke?.join === 'ROUND' ? 'round' : stroke?.join === 'BEVEL' ? 'bevel' : 'miter',
  };
}

interface InwardRectStrokeGeometry {
  readonly path: string;
}

function inwardRectStrokeGeometry(
  widthMm: number,
  heightMm: number,
  strokeWidthMm: number,
  outerRadii: CornerRadiiProjection,
): InwardRectStrokeGeometry {
  const halfStroke = strokeWidthMm / 2;
  const insetX = Math.min(
    halfStroke,
    Math.max(0, (widthMm - MIN_STROKE_CENTERLINE_EXTENT_MM) / 2),
  );
  const insetY = Math.min(
    halfStroke,
    Math.max(0, (heightMm - MIN_STROKE_CENTERLINE_EXTENT_MM) / 2),
  );
  const centerlineWidth = Math.max(
    MIN_STROKE_CENTERLINE_EXTENT_MM,
    widthMm - insetX * 2,
  );
  const centerlineHeight = Math.max(
    MIN_STROKE_CENTERLINE_EXTENT_MM,
    heightMm - insetY * 2,
  );
  const centerlineRadii = normalizeCornerRadii({
    topLeft: Math.max(0, outerRadii.topLeft - halfStroke),
    topRight: Math.max(0, outerRadii.topRight - halfStroke),
    bottomRight: Math.max(0, outerRadii.bottomRight - halfStroke),
    bottomLeft: Math.max(0, outerRadii.bottomLeft - halfStroke),
  }, centerlineWidth, centerlineHeight);
  return {
    path: roundedRectPath(
      insetX,
      insetY,
      centerlineWidth,
      centerlineHeight,
      centerlineRadii,
    ),
  };
}

function inwardStrokeRadius(outerRadius: number, strokeWidthMm: number): number {
  if (strokeWidthMm <= 0) return outerRadius;
  return Math.max(MIN_STROKE_CENTERLINE_EXTENT_MM, outerRadius - strokeWidthMm / 2);
}

function readTextStroke(value: unknown, pixelsPerMm: number) {
  const stroke = recordOrNull(value);
  const widthPt = nonNegativeNumber(stroke?.widthPt) ?? 0;
  return {
    color: validColor(stroke?.color) ?? 'transparent',
    width: `${templatePointsToCanvasPixels(widthPt, pixelsPerMm)}px`,
  };
}

function readFill(value: unknown): string {
  const fill = recordOrNull(value);
  return validColor(fill?.color) ?? 'none';
}

function readPoint(value: unknown): { xMm: number; yMm: number } | null {
  const point = recordOrNull(value);
  const xMm = finiteNumber(point?.xMm);
  const yMm = finiteNumber(point?.yMm);
  return xMm === null || yMm === null ? null : { xMm, yMm };
}

function projectedSvgPoints(
  value: unknown,
  widthMm: number,
  heightMm: number,
): string {
  const points = projectDesignGeometryPointsToAuthoredBox(value, {
    widthMm,
    heightMm,
  });
  return points?.map((point) => `${formatNumber(point.xMm)},${formatNumber(point.yMm)}`)
    .join(' ') ?? '';
}

interface CornerRadiiProjection {
  readonly topLeft: number;
  readonly topRight: number;
  readonly bottomRight: number;
  readonly bottomLeft: number;
}

function readCornerRadii(
  value: unknown,
  widthMm: number,
  heightMm: number,
): CornerRadiiProjection {
  const radii = recordOrNull(value);
  return normalizeCornerRadii({
    topLeft: nonNegativeNumber(radii?.topLeftMm) ?? 0,
    topRight: nonNegativeNumber(radii?.topRightMm) ?? 0,
    bottomRight: nonNegativeNumber(radii?.bottomRightMm) ?? 0,
    bottomLeft: nonNegativeNumber(radii?.bottomLeftMm) ?? 0,
  }, widthMm, heightMm);
}

function normalizeCornerRadii(
  radii: CornerRadiiProjection,
  widthMm: number,
  heightMm: number,
): CornerRadiiProjection {
  const scale = Math.min(
    1,
    radiusPairScale(widthMm, radii.topLeft, radii.topRight),
    radiusPairScale(widthMm, radii.bottomLeft, radii.bottomRight),
    radiusPairScale(heightMm, radii.topLeft, radii.bottomLeft),
    radiusPairScale(heightMm, radii.topRight, radii.bottomRight),
  );
  return {
    topLeft: radii.topLeft * scale,
    topRight: radii.topRight * scale,
    bottomRight: radii.bottomRight * scale,
    bottomLeft: radii.bottomLeft * scale,
  };
}

function radiusPairScale(limit: number, first: number, second: number): number {
  const maximum = Math.max(first, second);
  if (maximum === 0) return 1;
  return (limit / maximum) / (first / maximum + second / maximum);
}

function roundedRectPath(
  x: number,
  y: number,
  width: number,
  height: number,
  radii: CornerRadiiProjection,
): string {
  const right = x + Math.max(0, width);
  const bottom = y + Math.max(0, height);
  return [
    `M ${formatNumber(x + radii.topLeft)} ${formatNumber(y)}`,
    `H ${formatNumber(right - radii.topRight)}`,
    `Q ${formatNumber(right)} ${formatNumber(y)} ${formatNumber(right)} ${formatNumber(y + radii.topRight)}`,
    `V ${formatNumber(bottom - radii.bottomRight)}`,
    `Q ${formatNumber(right)} ${formatNumber(bottom)} ${formatNumber(right - radii.bottomRight)} ${formatNumber(bottom)}`,
    `H ${formatNumber(x + radii.bottomLeft)}`,
    `Q ${formatNumber(x)} ${formatNumber(bottom)} ${formatNumber(x)} ${formatNumber(bottom - radii.bottomLeft)}`,
    `V ${formatNumber(y + radii.topLeft)}`,
    `Q ${formatNumber(x)} ${formatNumber(y)} ${formatNumber(x + radii.topLeft)} ${formatNumber(y)}`,
    'Z',
  ].join(' ');
}

function textLineHeight(value: unknown, pixelsPerMm: number): number | string | undefined {
  const lineHeight = recordOrNull(value);
  if (lineHeight?.type === 'FACTOR') {
    const factor = finiteNumber(lineHeight.factor);
    return factor !== null && factor > 0 ? factor : undefined;
  }
  if (lineHeight?.type === 'FIXED') {
    const points = finiteNumber(lineHeight.valuePt);
    return points !== null && points > 0
      ? `${templatePointsToCanvasPixels(points, pixelsPerMm)}px`
      : undefined;
  }
  return undefined;
}

function readPadding(value: unknown) {
  const padding = recordOrNull(value);
  return {
    topMm: nonNegativeNumber(padding?.topMm) ?? 0,
    rightMm: nonNegativeNumber(padding?.rightMm) ?? 0,
    bottomMm: nonNegativeNumber(padding?.bottomMm) ?? 0,
    leftMm: nonNegativeNumber(padding?.leftMm) ?? 0,
  };
}

function draftQrCells(content: string): Array<{ x: number; y: number }> {
  let seed = draftSeed(content);
  const cells: Array<{ x: number; y: number }> = [];
  for (let y = 1; y < 12; y += 1) {
    for (let x = 1; x < 12; x += 1) {
      seed = (seed * 1664525 + 1013904223) >>> 0;
      if ((seed & 3) === 0 || (x < 4 && y < 4) || (x > 8 && y < 4)) cells.push({ x, y });
    }
  }
  return cells;
}

function validQrLayoutBox(widthMm: number, heightMm: number): boolean {
  return Number.isFinite(widthMm)
    && Number.isFinite(heightMm)
    && widthMm > 0
    && heightMm > 0
    && widthMm === heightMm;
}

function draftBarcodeBars(value: string): Array<{ x: number; width: number }> {
  let seed = draftSeed(value);
  const bars: Array<{ x: number; width: number }> = [];
  let x = 5;
  while (x < 94) {
    seed = (seed * 1103515245 + 12345) >>> 0;
    const width = 1 + (seed % 4);
    bars.push({ x, width });
    x += width + 1 + ((seed >>> 3) % 3);
  }
  return bars;
}

function draftSeed(value: string): number {
  let seed = 2166136261;
  for (const character of value) {
    seed ^= character.codePointAt(0) ?? 0;
    seed = Math.imul(seed, 16777619);
  }
  return seed >>> 0;
}

function validColor(value: unknown): string | null {
  return typeof value === 'string' && /^#[\dA-Fa-f]{8}$/.test(value) ? value : null;
}

function positiveDimension(value: number): number {
  return Number.isFinite(value) && value > 0 ? value : 1;
}

function positiveInteger(value: unknown): number | null {
  return typeof value === 'number' && Number.isSafeInteger(value) && value > 0 ? value : null;
}

function nonNegativeNumber(value: unknown): number | null {
  const number = finiteNumber(value);
  return number !== null && number >= 0 ? number : null;
}

function numberOr(value: unknown, fallback: number): number {
  return finiteNumber(value) ?? fallback;
}

function finiteNumber(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}

function recordOrNull(value: unknown): Readonly<Record<string, unknown>> | null {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
    ? value as Readonly<Record<string, unknown>>
    : null;
}

function formatNumber(value: number): string {
  return Number.isInteger(value)
    ? String(value)
    : value.toFixed(6).replace(/0+$/, '').replace(/\.$/, '');
}
