import { inspectDesignDslWire } from './template-design-dsl-wire';
import { createStructuredEditorSession } from './template-editor-session';

export { SUPPORTED_NODE_KIND_COUNT } from './template-design-dsl-wire';

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

export interface CanonicalDesignWorkingCopy {
  canonicalDesignDsl: string;
  designDsl: Record<string, unknown>;
}

export interface SetTemplateDisplayNameCommand {
  kind: 'set-template-display-name';
  before: string;
  after: string;
}

export interface InsertNodeCommand {
  kind: 'insert-node';
  parentNodeId: string;
  childIndex: number;
  node: Readonly<Record<string, unknown>>;
}

export interface DeleteNodeCommand {
  kind: 'delete-node';
  parentNodeId: string;
  childIndex: number;
  node: Readonly<Record<string, unknown>>;
}

/**
 * Replaces authored members on one node without copying its descendant tree into history.
 * The before/after shells deliberately omit `children`; replay preserves the current children.
 */
export interface ReplaceNodeShellCommand {
  kind: 'replace-node-shell';
  nodeId: string;
  before: Readonly<Record<string, unknown>>;
  after: Readonly<Record<string, unknown>>;
}

export interface NodeShellReplacement {
  nodeId: string;
  before: Readonly<Record<string, unknown>>;
  after: Readonly<Record<string, unknown>>;
}

/** Replaces several authored node shells as one compact, reversible history step. */
export interface ReplaceNodeShellsCommand {
  kind: 'replace-node-shells';
  replacements: readonly NodeShellReplacement[];
}

/** A compact reversible mutation of one set-like DesignDSL definition. */
export interface ReplaceDefinitionCommand {
  kind: 'replace-definition';
  definitionId: string;
  before: Readonly<Record<string, unknown>> | null;
  after: Readonly<Record<string, unknown>> | null;
}

/** A compact reversible mutation of one node-local set-like Binding. */
export interface ReplaceNodeBindingCommand {
  kind: 'replace-node-binding';
  nodeId: string;
  bindingId: string;
  before: Readonly<Record<string, unknown>> | null;
  after: Readonly<Record<string, unknown>> | null;
}

export interface NodeTreeLocation {
  parentNodeId: string;
  childIndex: number;
  placement: Readonly<Record<string, unknown>>;
}

/** A compact reversible reparent/reorder record; the moved subtree itself is not duplicated. */
export interface MoveNodeCommand {
  kind: 'move-node';
  nodeId: string;
  before: NodeTreeLocation;
  after: NodeTreeLocation;
  /** Owner Group shell changes required to keep world coordinates stable across a reparent. */
  groupCompensations?: readonly NodeShellReplacement[];
}

export type StructuredEditorCommand =
  | SetTemplateDisplayNameCommand
  | InsertNodeCommand
  | DeleteNodeCommand
  | ReplaceNodeShellCommand
  | ReplaceNodeShellsCommand
  | ReplaceDefinitionCommand
  | ReplaceNodeBindingCommand
  | MoveNodeCommand;

export interface StructuredEditorHistory {
  past: readonly StructuredEditorCommand[];
  future: readonly StructuredEditorCommand[];
}

export interface StructuredEditorSession {
  mode: 'structured';
  baseline: CanonicalTemplateBaseline;
  workingCopy: CanonicalDesignWorkingCopy;
  readiness: EditorReadiness;
  history: StructuredEditorHistory;
  previewGeneration: number;
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

export class MalformedDesignDslWireError extends Error {
  readonly path: string;

  constructor(path: string) {
    super(`${path} is not a safe closed DesignDSL wire`);
    this.name = 'MalformedDesignDslWireError';
    this.path = path;
  }
}

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
  return createStructuredEditorSession(baseline, readiness);
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
  const canvas = objectOrNull(session.workingCopy.designDsl.designRoot);
  if (!canvas) return [];
  const nodes: EditorNodeProjection[] = [];
  visitNode(canvas, 0, nodes);
  return nodes;
}

export function templateDisplayName(
  source: CanonicalTemplateBaseline | CanonicalDesignWorkingCopy,
): string {
  const displayName = source.designDsl.displayName;
  return typeof displayName === 'string' && displayName.length > 0
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
  const inspection = inspectDesignDslWire(designDsl);
  if (inspection.status === 'supported') return null;
  if (inspection.status === 'unsupported-profile') {
    return inspection.path === 'dslVersion'
      ? '客户端不理解该 DesignDSL Profile。'
      : '客户端不理解该 Expression Profile。';
  }
  if (inspection.status === 'malformed') {
    throw new MalformedDesignDslWireError(inspection.path);
  }
  return `DesignDSL 包含客户端不理解的 closed wire：${inspection.path}。`;
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
    displayName: typeof node.displayName === 'string' && node.displayName.length > 0
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
