export type PersistedTemplateReadiness = 'READY' | 'INVALID' | 'STALE';
export type CheckedTemplateReadiness = 'READY' | 'INVALID';

export type EditorReadiness =
  | { state: 'checking' }
  | { state: 'checked'; value: CheckedTemplateReadiness }
  | { state: 'unavailable'; message: string };

export interface StaticSchemaIdentity {
  schemaKey: string;
  versionTag: string;
}

export interface CanonicalTemplateBaseline {
  templateId: string;
  revision: string;
  staticSchema: StaticSchemaIdentity;
  contentHash: string;
  persistedReadiness: PersistedTemplateReadiness;
  canonicalDesignDsl: string;
  designDsl: Record<string, unknown>;
}

export interface EditorNodeProjection {
  nodeId: string;
  kind: string;
  displayName: string;
  depth: number;
  childCount: number;
  value: Record<string, unknown>;
}

export interface StructuredEditorSession {
  mode: 'structured';
  baseline: CanonicalTemplateBaseline;
  readiness: EditorReadiness;
}

export interface CompatibilityEditorSession {
  mode: 'compatibility';
  baseline: CanonicalTemplateBaseline;
  readiness: EditorReadiness;
  reason: string;
}

export interface RawRepairEditorSession {
  mode: 'raw-repair';
  rawBuffer: string;
  problem: string;
  byteLength: number;
}

export type TemplateEditorSession =
  | StructuredEditorSession
  | CompatibilityEditorSession
  | RawRepairEditorSession;

const SUPPORTED_NODE_KINDS = new Set([
  'canvas',
  'group',
  'frame',
  'stack',
  'grid',
  'repeat',
  'text',
  'image',
  'rect',
  'ellipse',
  'line',
  'polygon',
  'polyline',
  'path',
  'qrCode',
  'barcode',
  'templateUse',
  'conditional',
]);

export const SUPPORTED_NODE_KIND_COUNT = SUPPORTED_NODE_KINDS.size;

export function createSessionFromBaseline(
  baseline: CanonicalTemplateBaseline,
  readiness: EditorReadiness,
): StructuredEditorSession | CompatibilityEditorSession {
  const compatibilityReason = compatibilityReasonOf(baseline.designDsl);
  if (compatibilityReason) {
    return {
      mode: 'compatibility',
      baseline,
      readiness,
      reason: compatibilityReason,
    };
  }
  return { mode: 'structured', baseline, readiness };
}

export function createRawRepairSession(
  rawBuffer: string,
  problem: string,
): RawRepairEditorSession {
  return {
    mode: 'raw-repair',
    rawBuffer,
    problem,
    byteLength: new TextEncoder().encode(rawBuffer).byteLength,
  };
}

export function projectStructuredNodes(
  session: StructuredEditorSession,
): EditorNodeProjection[] {
  const canvas = objectOrNull(session.baseline.designDsl.designRoot);
  if (!canvas) return [];
  const nodes: EditorNodeProjection[] = [];
  visitNode(canvas, 0, nodes);
  return nodes;
}

export function templateDisplayName(baseline: CanonicalTemplateBaseline): string {
  const displayName = baseline.designDsl.displayName;
  return typeof displayName === 'string' && displayName.trim()
    ? displayName
    : '未命名 Template';
}

export function profileIdentity(baseline: CanonicalTemplateBaseline): string {
  const dslVersion = typeof baseline.designDsl.dslVersion === 'string'
    ? baseline.designDsl.dslVersion
    : 'unknown DesignDSL';
  const expressionProfile = typeof baseline.designDsl.expressionProfile === 'string'
    ? baseline.designDsl.expressionProfile
    : 'unknown Expression';
  return `${dslVersion} · ${expressionProfile}`;
}

function compatibilityReasonOf(designDsl: Record<string, unknown>): string | null {
  if (designDsl.dslVersion !== 'renderweave-design/1.0') {
    return '客户端不理解该 DesignDSL Profile。';
  }
  if (designDsl.expressionProfile !== 'renderweave-expression/1.0') {
    return '客户端不理解该 Expression Profile。';
  }
  if (!Array.isArray(designDsl.definitions)) {
    return 'definitions 不是客户端理解的 closed wire。';
  }
  const canvas = objectOrNull(designDsl.designRoot);
  if (!canvas || canvas.kind !== 'canvas' || !supportedNodeTree(canvas)) {
    return 'DesignDSL 包含客户端不理解的 Node wire。';
  }
  return null;
}

function supportedNodeTree(node: Record<string, unknown>): boolean {
  if (typeof node.nodeId !== 'string' || typeof node.kind !== 'string') return false;
  if (!SUPPORTED_NODE_KINDS.has(node.kind)) return false;
  if (node.children === undefined) return true;
  if (!Array.isArray(node.children)) return false;
  return node.children.every((child) => {
    const childObject = objectOrNull(child);
    return childObject !== null && supportedNodeTree(childObject);
  });
}

function visitNode(
  node: Record<string, unknown>,
  depth: number,
  target: EditorNodeProjection[],
) {
  const children = Array.isArray(node.children)
    ? node.children.map(objectOrNull).filter((child): child is Record<string, unknown> => child !== null)
    : [];
  const nodeId = typeof node.nodeId === 'string' ? node.nodeId : 'unknown';
  const kind = typeof node.kind === 'string' ? node.kind : 'unknown';
  target.push({
    nodeId,
    kind,
    displayName: typeof node.displayName === 'string' && node.displayName.trim()
      ? node.displayName
      : kindLabel(kind),
    depth,
    childCount: children.length,
    value: node,
  });
  for (const child of children) visitNode(child, depth + 1, target);
}

function kindLabel(kind: string): string {
  const labels: Record<string, string> = {
    canvas: '画布', group: '分组', frame: '框架', stack: '堆叠', grid: '网格',
    repeat: '重复', text: '文本', image: '图片', rect: '矩形', ellipse: '椭圆',
    line: '线段', polygon: '多边形', polyline: '折线', path: '路径', qrCode: '二维码',
    barcode: '条码', templateUse: 'Template 引用', conditional: '条件',
  };
  return labels[kind] ?? kind;
}

export function objectOrNull(value: unknown): Record<string, unknown> | null {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
    ? value as Record<string, unknown>
    : null;
}
