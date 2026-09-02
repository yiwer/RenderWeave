import type {
  DesignAbsolutePlacement,
  DesignBarcodeNode,
  DesignCornerRadii,
  DesignEllipseNode,
  DesignImageNode,
  DesignLineNode,
  DesignPathCommand,
  DesignPathNode,
  DesignPointMm,
  DesignPolygonNode,
  DesignPolylineNode,
  DesignQrCodeNode,
  DesignRectNode,
  DesignStrokeMm,
  DesignTextNode,
} from '../../api/generated';

const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const COLOR = /^#[0-9A-Fa-f]{8}$/;

const DEFAULT_X_MM = 25.4;
const DEFAULT_Y_MM = 25.4;
const DEFAULT_FILL_COLOR = '#2563EBFF';
const DEFAULT_STROKE_COLOR = '#172033FF';

export type TemplateVisualLeafKind =
  | 'text'
  | 'image'
  | 'rect'
  | 'ellipse'
  | 'line'
  | 'polygon'
  | 'polyline'
  | 'path'
  | 'qrCode'
  | 'barcode';

export type TemplateShapePreset = 'diamond' | 'triangle' | 'star' | 'arrow';

export type TemplateVisualNode =
  | DesignTextNode
  | DesignImageNode
  | DesignRectNode
  | DesignEllipseNode
  | DesignLineNode
  | DesignPolygonNode
  | DesignPolylineNode
  | DesignPathNode
  | DesignQrCodeNode
  | DesignBarcodeNode;

interface TemplateVisualNodeInputBase {
  readonly nodeId: string;
  readonly ordinal: number;
  readonly at?: Readonly<{ xMm: number; yMm: number }>;
}

export type BuildTemplateVisualNodeInput =
  | (TemplateVisualNodeInputBase & { readonly kind: 'text'; readonly fontAssetId: string })
  | (TemplateVisualNodeInputBase & { readonly kind: 'image'; readonly imageAssetId: string })
  | (TemplateVisualNodeInputBase & {
    readonly kind: Exclude<TemplateVisualLeafKind, 'text' | 'image'>;
  });

export interface BuildTemplateShapePresetNodeInput extends TemplateVisualNodeInputBase {
  readonly preset: TemplateShapePreset;
}

export type TemplateVisualNodeBuildResult =
  | { readonly state: 'built'; readonly node: TemplateVisualNode }
  | {
    readonly state: 'rejected';
    readonly code: 'NODE_ID_INVALID' | 'ORDINAL_INVALID' | 'POSITION_INVALID'
      | 'ASSET_ID_REQUIRED' | 'ASSET_ID_INVALID';
    readonly message: string;
  };

export type TemplateVisualPropertyChange =
  | { readonly property: 'text'; readonly value: string }
  | { readonly property: 'fontRef'; readonly assetId: string }
  | { readonly property: 'fontSizePt'; readonly value: number }
  | { readonly property: 'textColor'; readonly value: string }
  | {
    readonly property: 'decoration';
    readonly value: 'NONE' | 'UNDERLINE' | 'LINE_THROUGH';
  }
  | { readonly property: 'letterSpacingPt'; readonly value: number }
  | { readonly property: 'letterSpacingFactor'; readonly value: number }
  | { readonly property: 'imageRef'; readonly assetId: string }
  | { readonly property: 'fit'; readonly value: 'CONTAIN' | 'COVER' | 'FILL' }
  | { readonly property: 'sampling'; readonly value: 'LINEAR' | 'NEAREST' }
  | { readonly property: 'fillColor'; readonly value: string }
  | { readonly property: 'strokeColor'; readonly value: string }
  | { readonly property: 'strokeWidthMm'; readonly value: number }
  | { readonly property: 'cornerRadiusMm'; readonly value: number }
  | { readonly property: 'start'; readonly value: DesignPointMm }
  | { readonly property: 'end'; readonly value: DesignPointMm }
  | { readonly property: 'points'; readonly value: readonly DesignPointMm[] }
  | { readonly property: 'commands'; readonly value: readonly DesignPathCommand[] }
  | { readonly property: 'fillRule'; readonly value: 'NONZERO' | 'EVEN_ODD' }
  | { readonly property: 'content'; readonly value: string }
  | { readonly property: 'errorCorrectionLevel'; readonly value: 'L' | 'M' | 'Q' | 'H' }
  | {
    readonly property: 'format';
    readonly value: 'EAN_8' | 'EAN_13' | 'UPC_A' | 'CODE_128';
  }
  | { readonly property: 'barcodeValue'; readonly value: string }
  | { readonly property: 'foregroundColor'; readonly value: string }
  | { readonly property: 'backgroundColor'; readonly value: string };

export type TemplateVisualPropertyUpdateResult =
  | { readonly state: 'updated'; readonly node: Record<string, unknown> }
  | {
    readonly state: 'rejected';
    readonly node: Readonly<Record<string, unknown>>;
    readonly code: 'NODE_KIND_UNSUPPORTED' | 'PROPERTY_NOT_SUPPORTED' | 'PROPERTY_INVALID'
      | 'ASSET_ID_INVALID' | 'TEXT_RUN_INVALID' | 'TEXT_MULTI_RUN_UNSUPPORTED';
    readonly message: string;
  };

/**
 * Builds one exact renderweave-design/1.0 visual leaf. Text and Image have no
 * assetless fallback: callers must select a real catalog Asset before building.
 */
export function buildTemplateVisualNode(
  input: BuildTemplateVisualNodeInput,
): TemplateVisualNodeBuildResult {
  const commonProblem = validateBuildInput(input);
  if (commonProblem) return commonProblem;

  const size = templateVisualDefaultSize(input.kind);
  const common = {
    nodeId: input.nodeId,
    bindings: [],
    placement: fixedAbsolutePlacement(input.at, size),
  };
  const displayName = `${visualKindLabel(input.kind)} ${input.ordinal}`;

  switch (input.kind) {
    case 'text':
      return built({
        ...common,
        kind: 'text',
        displayName,
        runs: [{
          text: '文本',
          fontRef: { assetId: input.fontAssetId },
          fontSizePt: 12,
          color: '#000000FF',
          decoration: 'NONE',
          letterSpacingPt: 0,
        }],
        writingMode: 'HORIZONTAL_TB',
        horizontalAlign: 'LEFT',
        verticalAlign: 'TOP',
        lineBreak: 'WORD',
        overflow: 'CLIP',
        lineHeight: { type: 'FACTOR', factor: 1.2 },
        padding: zeroPadding(),
        fitMode: 'NONE',
      });
    case 'image':
      return built({
        ...common,
        kind: 'image',
        displayName,
        imageRef: { assetId: input.imageAssetId },
        fit: 'CONTAIN',
        sampling: 'LINEAR',
      });
    case 'rect':
      return built({
        ...common, kind: 'rect', displayName, fill: { color: DEFAULT_FILL_COLOR },
      });
    case 'ellipse':
      return built({
        ...common, kind: 'ellipse', displayName, fill: { color: DEFAULT_FILL_COLOR },
      });
    case 'line':
      return built({
        ...common,
        kind: 'line',
        displayName,
        start: { xMm: 0, yMm: 0 },
        end: { xMm: size.widthMm, yMm: size.heightMm },
        stroke: defaultStroke(),
      });
    case 'polygon':
      return built({
        ...common,
        kind: 'polygon',
        displayName,
        points: [
          { xMm: size.widthMm / 2, yMm: 0 },
          { xMm: size.widthMm, yMm: size.heightMm },
          { xMm: 0, yMm: size.heightMm },
        ],
        fill: { color: DEFAULT_FILL_COLOR },
      });
    case 'polyline':
      return built({
        ...common,
        kind: 'polyline',
        displayName,
        points: [
          { xMm: 0, yMm: size.heightMm },
          { xMm: size.widthMm / 2, yMm: 0 },
          { xMm: size.widthMm, yMm: size.heightMm },
        ],
        stroke: defaultStroke(),
      });
    case 'path':
      return built({
        ...common,
        kind: 'path',
        displayName,
        commands: [
          { type: 'MOVE_TO', xMm: 0, yMm: size.heightMm },
          {
            type: 'CUBIC_TO',
            c1xMm: size.widthMm * 0.25,
            c1yMm: 0,
            c2xMm: size.widthMm * 0.75,
            c2yMm: 0,
            xMm: size.widthMm,
            yMm: size.heightMm,
          },
          { type: 'CLOSE' },
        ],
        fill: { color: DEFAULT_FILL_COLOR },
        fillRule: 'NONZERO',
      });
    case 'qrCode':
      return built({
        ...common,
        kind: 'qrCode',
        displayName,
        content: 'RenderWeave',
        errorCorrectionLevel: 'M',
        foregroundColor: '#000000FF',
        backgroundColor: '#FFFFFFFF',
      });
    case 'barcode':
      return built({
        ...common,
        kind: 'barcode',
        displayName,
        format: 'CODE_128',
        value: 'RENDERWEAVE',
        foregroundColor: '#000000FF',
        backgroundColor: '#FFFFFFFF',
      });
  }
}

/** A Shape is an editor preset only; this function always persists Polygon. */
export function buildTemplateShapePresetNode(
  input: BuildTemplateShapePresetNodeInput,
): TemplateVisualNodeBuildResult {
  const commonProblem = validateBuildInput({ ...input, kind: 'polygon' });
  if (commonProblem) return commonProblem;
  const size = templateShapePresetDefaultSize(input.preset);
  return built({
    nodeId: input.nodeId,
    kind: 'polygon',
    displayName: `${shapePresetLabel(input.preset)} ${input.ordinal}`,
    bindings: [],
    placement: fixedAbsolutePlacement(input.at, size),
    points: shapePresetPoints(input.preset, size),
    fill: { color: DEFAULT_FILL_COLOR },
  });
}

/**
 * Replaces one authored visual property without rebuilding the node shell.
 * Unknown understood-by-a-future-client members and existing bindings remain
 * byte-value-equivalent in the returned object. Text edits are deliberately
 * restricted to one Run so rich text is never flattened or silently lost.
 */
export function updateTemplateVisualNodeProperty(
  node: Readonly<Record<string, unknown>>,
  change: TemplateVisualPropertyChange,
): TemplateVisualPropertyUpdateResult {
  const kind = node.kind;
  if (!isVisualLeafKind(kind)) {
    return updateRejected(node, 'NODE_KIND_UNSUPPORTED', '该节点不是受支持的视觉叶节点。');
  }

  switch (change.property) {
    case 'text':
      if (hasForbiddenTextControl(change.value)) {
        return updateRejected(node, 'PROPERTY_INVALID', '文本只允许 LF 换行，不允许其他控制字符。');
      }
      return updateSingleTextRun(node, { text: change.value });
    case 'fontRef':
      if (!UUID_V4.test(change.assetId)) {
        return updateRejected(node, 'ASSET_ID_INVALID', '字体 Asset 必须是 canonical lowercase UUID v4。');
      }
      return updateSingleTextRun(node, { fontRef: { assetId: change.assetId } });
    case 'fontSizePt':
      if (!positiveFinite(change.value)) {
        return updateRejected(node, 'PROPERTY_INVALID', '字体大小必须是正有限值。');
      }
      return updateSingleTextRun(node, { fontSizePt: change.value });
    case 'textColor': {
      const color = normalizedColor(change.value);
      if (!color) return updateRejected(node, 'PROPERTY_INVALID', '文字颜色必须是 #RRGGBBAA。');
      return updateSingleTextRun(node, { color });
    }
    case 'decoration':
      return updateSingleTextRun(node, { decoration: change.value });
    case 'letterSpacingPt':
      if (!finite(change.value)) {
        return updateRejected(node, 'PROPERTY_INVALID', '字距必须是有限值。');
      }
      return updateSingleTextRun(node, { letterSpacingPt: change.value }, ['letterSpacingFactor']);
    case 'letterSpacingFactor':
      if (!finite(change.value)) {
        return updateRejected(node, 'PROPERTY_INVALID', '相对字距必须是有限值。');
      }
      return updateSingleTextRun(node, { letterSpacingFactor: change.value }, ['letterSpacingPt']);
    case 'imageRef':
      if (kind !== 'image') return unsupportedProperty(node, change.property);
      if (!UUID_V4.test(change.assetId)) {
        return updateRejected(node, 'ASSET_ID_INVALID', '图片 Asset 必须是 canonical lowercase UUID v4。');
      }
      return updated({ ...node, imageRef: { assetId: change.assetId } });
    case 'fit':
      return kind === 'image'
        ? updated({ ...node, fit: change.value })
        : unsupportedProperty(node, change.property);
    case 'sampling':
      return kind === 'image'
        ? updated({ ...node, sampling: change.value })
        : unsupportedProperty(node, change.property);
    case 'fillColor': {
      if (!hasFill(kind)) return unsupportedProperty(node, change.property);
      const color = normalizedColor(change.value);
      if (!color) return updateRejected(node, 'PROPERTY_INVALID', '填充颜色必须是 #RRGGBBAA。');
      return updated({ ...node, fill: { ...recordOrEmpty(node.fill), color } });
    }
    case 'strokeColor': {
      if (!hasStroke(kind)) return unsupportedProperty(node, change.property);
      const color = normalizedColor(change.value);
      if (!color) return updateRejected(node, 'PROPERTY_INVALID', '描边颜色必须是 #RRGGBBAA。');
      return updated({
        ...node,
        stroke: { ...defaultStroke(), ...recordOrEmpty(node.stroke), color },
      });
    }
    case 'strokeWidthMm':
      if (!hasStroke(kind)) return unsupportedProperty(node, change.property);
      if (!positiveFinite(change.value)) {
        return updateRejected(node, 'PROPERTY_INVALID', '描边宽度必须是正有限值。');
      }
      return updated({
        ...node,
        stroke: { ...defaultStroke(), ...recordOrEmpty(node.stroke), widthMm: change.value },
      });
    case 'cornerRadiusMm':
      if (kind !== 'rect') return unsupportedProperty(node, change.property);
      if (!nonNegativeFinite(change.value)) {
        return updateRejected(node, 'PROPERTY_INVALID', '圆角必须是非负有限值。');
      }
      return updated({ ...node, cornerRadii: equalCornerRadii(change.value) });
    case 'start':
    case 'end': {
      if (kind !== 'line') return unsupportedProperty(node, change.property);
      if (!validPoint(change.value)) {
        return updateRejected(node, 'PROPERTY_INVALID', '线段端点必须包含有限的 xMm/yMm。');
      }
      const other = change.property === 'start' ? node.end : node.start;
      if (!validPoint(other) || samePoint(change.value, other)) {
        return updateRejected(node, 'PROPERTY_INVALID', '线段起点和终点不能重合。');
      }
      return updated({ ...node, [change.property]: { ...change.value } });
    }
    case 'points': {
      if (kind !== 'polygon' && kind !== 'polyline') {
        return unsupportedProperty(node, change.property);
      }
      if (!validPointSequence(change.value, kind)) {
        return updateRejected(node, 'PROPERTY_INVALID', '点序列不满足当前几何节点约束。');
      }
      return updated({ ...node, points: change.value.map((point) => ({ ...point })) });
    }
    case 'commands':
      if (kind !== 'path') return unsupportedProperty(node, change.property);
      if (!validPathCommands(change.value)) {
        return updateRejected(node, 'PROPERTY_INVALID', 'Path commands 不满足有序命令约束。');
      }
      return updated({ ...node, commands: change.value.map((command) => ({ ...command })) });
    case 'fillRule':
      return kind === 'path'
        ? updated({ ...node, fillRule: change.value })
        : unsupportedProperty(node, change.property);
    case 'content':
      if (kind !== 'qrCode') return unsupportedProperty(node, change.property);
      if (change.value.length === 0) {
        return updateRejected(node, 'PROPERTY_INVALID', '二维码内容不能为空。');
      }
      return updated({ ...node, content: change.value });
    case 'errorCorrectionLevel':
      return kind === 'qrCode'
        ? updated({ ...node, errorCorrectionLevel: change.value })
        : unsupportedProperty(node, change.property);
    case 'format':
      if (kind !== 'barcode') return unsupportedProperty(node, change.property);
      if (typeof node.value !== 'string' || !validBarcode(change.value, node.value)) {
        return updateRejected(node, 'PROPERTY_INVALID', '现有条形码值不适用于所选格式。');
      }
      return updated({ ...node, format: change.value });
    case 'barcodeValue':
      if (kind !== 'barcode') return unsupportedProperty(node, change.property);
      if (!isBarcodeFormat(node.format) || !validBarcode(node.format, change.value)) {
        return updateRejected(node, 'PROPERTY_INVALID', '条形码值不满足当前格式约束。');
      }
      return updated({ ...node, value: change.value });
    case 'foregroundColor':
    case 'backgroundColor': {
      if (kind !== 'qrCode' && kind !== 'barcode') {
        return unsupportedProperty(node, change.property);
      }
      const color = normalizedColor(change.value);
      if (!color) return updateRejected(node, 'PROPERTY_INVALID', '颜色必须是 #RRGGBBAA。');
      return updated({ ...node, [change.property]: color });
    }
  }
}

export function templateVisualDefaultSize(
  kind: TemplateVisualLeafKind,
): Readonly<{ widthMm: number; heightMm: number }> {
  switch (kind) {
    case 'text': return { widthMm: 60, heightMm: 20 };
    case 'image': return { widthMm: 40, heightMm: 30 };
    case 'rect':
    case 'ellipse': return { widthMm: 25.4, heightMm: 25.4 };
    case 'line': return { widthMm: 40, heightMm: 10 };
    case 'polygon': return { widthMm: 30, heightMm: 25 };
    case 'polyline': return { widthMm: 30, heightMm: 20 };
    case 'path': return { widthMm: 32, heightMm: 24 };
    case 'qrCode': return { widthMm: 25, heightMm: 25 };
    case 'barcode': return { widthMm: 50, heightMm: 20 };
  }
}

function validateBuildInput(
  input: TemplateVisualNodeInputBase & {
    readonly kind: TemplateVisualLeafKind;
    readonly fontAssetId?: string;
    readonly imageAssetId?: string;
  },
): Extract<TemplateVisualNodeBuildResult, { state: 'rejected' }> | null {
  if (!UUID_V4.test(input.nodeId)) {
    return buildRejected('NODE_ID_INVALID', 'nodeId 必须是 canonical lowercase UUID v4。');
  }
  if (!Number.isSafeInteger(input.ordinal) || input.ordinal < 1) {
    return buildRejected('ORDINAL_INVALID', '节点序号必须是正安全整数。');
  }
  if (input.at && (!finite(input.at.xMm) || !finite(input.at.yMm))) {
    return buildRejected('POSITION_INVALID', '插入坐标必须是有限值。');
  }
  const assetId = input.kind === 'text'
    ? input.fontAssetId
    : input.kind === 'image'
      ? input.imageAssetId
      : undefined;
  if ((input.kind === 'text' || input.kind === 'image') && !assetId) {
    return buildRejected('ASSET_ID_REQUIRED', 'Text/Image 必须先选择真实 Asset。');
  }
  if (assetId && !UUID_V4.test(assetId)) {
    return buildRejected('ASSET_ID_INVALID', 'Asset 必须是 canonical lowercase UUID v4。');
  }
  return null;
}

function fixedAbsolutePlacement(
  at: Readonly<{ xMm: number; yMm: number }> | undefined,
  size: Readonly<{ widthMm: number; heightMm: number }>,
): DesignAbsolutePlacement {
  return {
    type: 'ABSOLUTE',
    xMm: at?.xMm ?? DEFAULT_X_MM,
    yMm: at?.yMm ?? DEFAULT_Y_MM,
    widthMode: 'FIXED',
    widthMm: size.widthMm,
    heightMode: 'FIXED',
    heightMm: size.heightMm,
  };
}

function defaultStroke(): DesignStrokeMm {
  return { color: DEFAULT_STROKE_COLOR, widthMm: 0.5, cap: 'ROUND', join: 'ROUND' };
}

function zeroPadding() {
  return { topMm: 0, rightMm: 0, bottomMm: 0, leftMm: 0 };
}

function equalCornerRadii(value: number): DesignCornerRadii {
  return {
    topLeftMm: value,
    topRightMm: value,
    bottomRightMm: value,
    bottomLeftMm: value,
  };
}

function templateShapePresetDefaultSize(
  preset: TemplateShapePreset,
): Readonly<{ widthMm: number; heightMm: number }> {
  return preset === 'arrow' ? { widthMm: 40, heightMm: 24 } : { widthMm: 30, heightMm: 30 };
}

function shapePresetPoints(
  preset: TemplateShapePreset,
  size: Readonly<{ widthMm: number; heightMm: number }>,
): DesignPointMm[] {
  const { widthMm: width, heightMm: height } = size;
  switch (preset) {
    case 'diamond':
      return [
        { xMm: width / 2, yMm: 0 },
        { xMm: width, yMm: height / 2 },
        { xMm: width / 2, yMm: height },
        { xMm: 0, yMm: height / 2 },
      ];
    case 'triangle':
      return [
        { xMm: width / 2, yMm: 0 },
        { xMm: width, yMm: height },
        { xMm: 0, yMm: height },
      ];
    case 'star': {
      const centerX = width / 2;
      const centerY = height / 2;
      const outer = Math.min(width, height) / 2;
      const inner = outer * 0.42;
      return Array.from({ length: 10 }, (_, index) => {
        const angle = -Math.PI / 2 + (Math.PI * index) / 5;
        const radius = index % 2 === 0 ? outer : inner;
        return {
          xMm: centerX + Math.cos(angle) * radius,
          yMm: centerY + Math.sin(angle) * radius,
        };
      });
    }
    case 'arrow':
      return [
        { xMm: 0, yMm: height * 0.3 },
        { xMm: width * 0.58, yMm: height * 0.3 },
        { xMm: width * 0.58, yMm: 0 },
        { xMm: width, yMm: height / 2 },
        { xMm: width * 0.58, yMm: height },
        { xMm: width * 0.58, yMm: height * 0.7 },
        { xMm: 0, yMm: height * 0.7 },
      ];
  }
}

function updateSingleTextRun(
  node: Readonly<Record<string, unknown>>,
  patch: Readonly<Record<string, unknown>>,
  removedMembers: readonly string[] = [],
): TemplateVisualPropertyUpdateResult {
  if (node.kind !== 'text') return unsupportedProperty(node, Object.keys(patch)[0] ?? 'Text');
  if (!Array.isArray(node.runs)) {
    return updateRejected(node, 'TEXT_RUN_INVALID', 'Text 缺少可编辑的 runs[]。');
  }
  if (node.runs.length !== 1) {
    return updateRejected(
      node,
      'TEXT_MULTI_RUN_UNSUPPORTED',
      '当前简化编辑器只编辑单 Run；多 Run 内容保持原样且不可编辑。',
    );
  }
  const run = recordOrNull(node.runs[0]);
  if (!run) return updateRejected(node, 'TEXT_RUN_INVALID', 'Text Run 不是合法对象。');
  const nextRun = { ...run, ...patch };
  for (const member of removedMembers) delete nextRun[member];
  return updated({ ...node, runs: [nextRun] });
}

function validPointSequence(
  points: readonly DesignPointMm[],
  kind: 'polygon' | 'polyline',
): boolean {
  const minimum = kind === 'polygon' ? 3 : 2;
  if (points.length < minimum || !points.every(validPoint)) return false;
  for (let index = 1; index < points.length; index += 1) {
    if (samePoint(points[index], points[index - 1])) return false;
  }
  if (kind === 'polyline') return true;
  if (samePoint(points[0], points.at(-1))) return false;
  const first = points[0];
  const second = points[1];
  if (!first || !second) return false;
  return points.slice(2).some((point) => (
    (second.xMm - first.xMm) * (point.yMm - first.yMm)
      - (second.yMm - first.yMm) * (point.xMm - first.xMm)
  ) !== 0);
}

function validPathCommands(commands: readonly DesignPathCommand[]): boolean {
  if (commands.length < 2 || commands[0]?.type !== 'MOVE_TO') return false;
  let hasDrawing = false;
  let needsMove = false;
  for (const command of commands) {
    if (!validPathCommandNumbers(command)) return false;
    if (command.type === 'MOVE_TO') {
      needsMove = false;
      continue;
    }
    if (command.type === 'CLOSE') {
      if (needsMove) return false;
      needsMove = true;
      continue;
    }
    if (needsMove) return false;
    hasDrawing = true;
  }
  return hasDrawing;
}

function validPathCommandNumbers(command: DesignPathCommand): boolean {
  switch (command.type) {
    case 'MOVE_TO':
    case 'LINE_TO':
      return finite(command.xMm) && finite(command.yMm);
    case 'QUAD_TO':
      return finite(command.cxMm) && finite(command.cyMm)
        && finite(command.xMm) && finite(command.yMm);
    case 'CUBIC_TO':
      return finite(command.c1xMm) && finite(command.c1yMm)
        && finite(command.c2xMm) && finite(command.c2yMm)
        && finite(command.xMm) && finite(command.yMm);
    case 'CLOSE':
      return true;
  }
}

function validBarcode(
  format: 'EAN_8' | 'EAN_13' | 'UPC_A' | 'CODE_128',
  value: string,
): boolean {
  if (format === 'CODE_128') {
    return value.length >= 1 && value.length <= 128
      && [...value].every((character) => {
        const code = character.codePointAt(0) ?? 0;
        return code >= 0x20 && code <= 0x7e;
      });
  }
  const length = format === 'EAN_8' ? 8 : format === 'EAN_13' ? 13 : 12;
  if (value.length !== length || !/^\d+$/.test(value)) return false;
  let sum = 0;
  for (let index = 0; index < length - 1; index += 1) {
    const digit = Number(value[index]);
    const oddPosition = index % 2 === 0;
    const weightThree = format === 'EAN_13' ? !oddPosition : oddPosition;
    sum += digit * (weightThree ? 3 : 1);
  }
  return Number(value[length - 1]) === (10 - (sum % 10)) % 10;
}

function isBarcodeFormat(value: unknown): value is 'EAN_8' | 'EAN_13' | 'UPC_A' | 'CODE_128' {
  return value === 'EAN_8' || value === 'EAN_13' || value === 'UPC_A' || value === 'CODE_128';
}

function isVisualLeafKind(value: unknown): value is TemplateVisualLeafKind {
  return value === 'text' || value === 'image' || value === 'rect' || value === 'ellipse'
    || value === 'line' || value === 'polygon' || value === 'polyline' || value === 'path'
    || value === 'qrCode' || value === 'barcode';
}

function hasFill(kind: TemplateVisualLeafKind): kind is 'rect' | 'ellipse' | 'polygon' | 'path' {
  return kind === 'rect' || kind === 'ellipse' || kind === 'polygon' || kind === 'path';
}

function hasStroke(
  kind: TemplateVisualLeafKind,
): kind is 'rect' | 'ellipse' | 'line' | 'polygon' | 'polyline' | 'path' {
  return kind === 'rect' || kind === 'ellipse' || kind === 'line' || kind === 'polygon'
    || kind === 'polyline' || kind === 'path';
}

function visualKindLabel(kind: TemplateVisualLeafKind): string {
  switch (kind) {
    case 'text': return '文本';
    case 'image': return '图片';
    case 'rect': return '矩形';
    case 'ellipse': return '椭圆';
    case 'line': return '直线';
    case 'polygon': return '多边形';
    case 'polyline': return '折线';
    case 'path': return '路径';
    case 'qrCode': return '二维码';
    case 'barcode': return '条形码';
  }
}

function shapePresetLabel(preset: TemplateShapePreset): string {
  switch (preset) {
    case 'diamond': return '菱形';
    case 'triangle': return '三角形';
    case 'star': return '星形';
    case 'arrow': return '箭头';
  }
}

function validPoint(value: unknown): value is DesignPointMm {
  const record = recordOrNull(value);
  return record !== null && finite(record.xMm) && finite(record.yMm);
}

function samePoint(left: unknown, right: unknown): boolean {
  return validPoint(left) && validPoint(right)
    && left.xMm === right.xMm && left.yMm === right.yMm;
}

function normalizedColor(value: string): string | null {
  return COLOR.test(value) ? value.toUpperCase() : null;
}

function hasForbiddenTextControl(value: string): boolean {
  for (const character of value) {
    const code = character.codePointAt(0) ?? 0;
    if (code < 0x20 && code !== 0x0a) return true;
  }
  return false;
}

function finite(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value);
}

function positiveFinite(value: unknown): value is number {
  return finite(value) && value > 0;
}

function nonNegativeFinite(value: unknown): value is number {
  return finite(value) && value >= 0;
}

function recordOrNull(value: unknown): Record<string, unknown> | null {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
    ? value as Record<string, unknown>
    : null;
}

function recordOrEmpty(value: unknown): Record<string, unknown> {
  return recordOrNull(value) ?? {};
}

function built(node: TemplateVisualNode): TemplateVisualNodeBuildResult {
  return { state: 'built', node };
}

function buildRejected(
  code: Extract<TemplateVisualNodeBuildResult, { state: 'rejected' }>['code'],
  message: string,
): Extract<TemplateVisualNodeBuildResult, { state: 'rejected' }> {
  return { state: 'rejected', code, message };
}

function updated(node: Record<string, unknown>): TemplateVisualPropertyUpdateResult {
  return { state: 'updated', node };
}

function unsupportedProperty(
  node: Readonly<Record<string, unknown>>,
  property: string,
): TemplateVisualPropertyUpdateResult {
  return updateRejected(node, 'PROPERTY_NOT_SUPPORTED', `当前节点不支持属性 ${property}。`);
}

function updateRejected(
  node: Readonly<Record<string, unknown>>,
  code: Extract<TemplateVisualPropertyUpdateResult, { state: 'rejected' }>['code'],
  message: string,
): Extract<TemplateVisualPropertyUpdateResult, { state: 'rejected' }> {
  return { state: 'rejected', node, code, message };
}
