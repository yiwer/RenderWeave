/**
 * Browser-only Conditional projection for the T220 authoring prototype.
 *
 * It deliberately keeps authored nodes intact while exposing the runtime
 * distinction between an included true branch, a pruned false branch and an
 * evaluation error. The editor can therefore keep a pruned branch selectable
 * without pretending that it participates in output layout.
 */
import type { ConditionalPreviewSample, DesignerNode } from './model';

export type ConditionalSourceKind = 'LITERAL' | 'CONTEXT';
export type ConditionalPhase = 'SOURCE_REQUIRED' | 'CONTENT_REQUIRED' | 'READY';
export type ConditionalOutcome =
  | 'INCLUDED'
  | 'PRUNED_FALSE'
  | 'PRUNED_ABSENT'
  | 'ABSENT_ERROR'
  | 'INPUT_INVALID'
  | 'RENDER_DISABLED'
  | 'INVALID';

export interface PrototypeConditionalSource {
  id: 'literal-true' | 'literal-false' | 'promotion-enabled' | 'member-eligible';
  label: string;
  wire: string;
  kind: ConditionalSourceKind;
  optional: boolean;
  path?: '/promotionEnabled' | '/memberEligible';
  literalValue?: boolean;
}

export interface ConditionalSourceProof {
  valid: boolean;
  valueType: 'boolean' | null;
  sourceKind: ConditionalSourceKind | null;
  optional: boolean | null;
  message: string;
}

export interface ConditionalProjection {
  nodeId: string;
  phase: ConditionalPhase;
  outcome: ConditionalOutcome;
  source: PrototypeConditionalSource | null;
  sourceProof: ConditionalSourceProof;
  conditionValue: boolean | null;
  authoredChildIds: string[];
  participatesInLayout: boolean;
  evaluatesChildren: boolean;
  lowersToFrame: boolean;
  message: string;
}

export interface ConditionalRuntimeProjection {
  byNodeId: ReadonlyMap<string, ConditionalProjection>;
  /** Runtime layout must behave as if these authored nodes do not exist. */
  excludedNodeIds: ReadonlySet<string>;
  /** Editor paint keeps the Conditional host but suppresses these descendants. */
  hiddenDescendantIds: ReadonlySet<string>;
}

export const prototypeConditionalSources: readonly PrototypeConditionalSource[] = [
  {
    id: 'promotion-enabled',
    label: '促销开关',
    wire: 'context(invocation, /promotionEnabled)',
    kind: 'CONTEXT',
    optional: false,
    path: '/promotionEnabled',
  },
  {
    id: 'member-eligible',
    label: '会员资格（可缺失）',
    wire: 'context(invocation, /memberEligible)',
    kind: 'CONTEXT',
    optional: true,
    path: '/memberEligible',
  },
  {
    id: 'literal-true',
    label: '固定为 TRUE',
    wire: 'literal(true)',
    kind: 'LITERAL',
    optional: false,
    literalValue: true,
  },
  {
    id: 'literal-false',
    label: '固定为 FALSE',
    wire: 'literal(false)',
    kind: 'LITERAL',
    optional: false,
    literalValue: false,
  },
] as const;

function nodeProp(node: DesignerNode, label: string): string | undefined {
  return node.props.find((property) => property.label === label)?.value;
}

function normalizedSource(value: string | undefined): string {
  return (value ?? '').trim().replace(/\s+/g, ' ');
}

export function provePrototypeConditionalSource(node: DesignerNode): {
  source: PrototypeConditionalSource | null;
  proof: ConditionalSourceProof;
} {
  const wire = normalizedSource(nodeProp(node, 'condition'));
  const source = prototypeConditionalSources.find((candidate) => normalizedSource(candidate.wire) === wire) ?? null;
  if (!source) {
    return {
      source: null,
      proof: {
        valid: false,
        valueType: null,
        sourceKind: null,
        optional: null,
        message: wire === '' ? '先选择一个可静态证明为 boolean 的条件源' : `“${wire}”无法证明为 boolean`,
      },
    };
  }
  return {
    source,
    proof: {
      valid: true,
      valueType: 'boolean',
      sourceKind: source.kind,
      optional: source.optional,
      message: source.kind === 'LITERAL'
        ? `boolean 字面量 · 固定为 ${source.literalValue ? 'TRUE' : 'FALSE'}`
        : `${source.path} · boolean · ${source.optional ? 'optional' : 'required'}`,
    },
  };
}

export function projectPrototypeConditional(
  node: DesignerNode,
  sample: ConditionalPreviewSample,
): ConditionalProjection {
  const { source, proof } = provePrototypeConditionalSource(node);
  const authoredChildIds = node.children.map((child) => child.id);
  const base = {
    nodeId: node.id,
    source,
    sourceProof: proof,
    authoredChildIds,
  };

  if (!source) {
    return {
      ...base,
      phase: 'SOURCE_REQUIRED',
      outcome: 'INVALID',
      conditionValue: null,
      participatesInLayout: false,
      evaluatesChildren: false,
      lowersToFrame: false,
      message: proof.message,
    };
  }
  if (node.children.length === 0) {
    return {
      ...base,
      phase: 'CONTENT_REQUIRED',
      outcome: 'INVALID',
      conditionValue: null,
      participatesInLayout: false,
      evaluatesChildren: false,
      lowersToFrame: false,
      message: 'Conditional 只有 true 分支，children[] 至少需要一个 authored 节点',
    };
  }
  if (nodeProp(node, 'render') === 'false') {
    return {
      ...base,
      phase: 'READY',
      outcome: 'RENDER_DISABLED',
      conditionValue: null,
      participatesInLayout: false,
      evaluatesChildren: false,
      lowersToFrame: false,
      message: 'render:false 先于条件求值剪枝；子树不布局、不解析资源',
    };
  }

  if (source.kind === 'CONTEXT' && sample === 'absent') {
    if (!source.optional) {
      return {
        ...base,
        phase: 'READY',
        outcome: 'INPUT_INVALID',
        conditionValue: null,
        participatesInLayout: false,
        evaluatesChildren: false,
        lowersToFrame: false,
        message: `${source.path} 是 required；缺失样本会在 RootDocument 验证阶段失败`,
      };
    }
    if (nodeProp(node, 'absentPolicy') === 'FALSE') {
      return {
        ...base,
        phase: 'READY',
        outcome: 'PRUNED_ABSENT',
        conditionValue: null,
        participatesInLayout: false,
        evaluatesChildren: false,
        lowersToFrame: false,
        message: 'typed ABSENT 按 FALSE 处理；整个 true 分支在布局前剪枝',
      };
    }
    return {
      ...base,
      phase: 'READY',
      outcome: 'ABSENT_ERROR',
      conditionValue: null,
      participatesInLayout: false,
      evaluatesChildren: false,
      lowersToFrame: false,
      message: 'typed ABSENT 按 ERROR 终止 Evaluation；不会产生部分输出',
    };
  }

  const conditionValue = source.kind === 'LITERAL'
    ? Boolean(source.literalValue)
    : sample === 'true';
  if (!conditionValue) {
    return {
      ...base,
      phase: 'READY',
      outcome: 'PRUNED_FALSE',
      conditionValue: false,
      participatesInLayout: false,
      evaluatesChildren: false,
      lowersToFrame: false,
      message: '条件为 FALSE；整个子树在 Binding、布局、Asset 与输出前剪枝',
    };
  }
  return {
    ...base,
    phase: 'READY',
    outcome: 'INCLUDED',
    conditionValue: true,
    participatesInLayout: true,
    evaluatesChildren: true,
    lowersToFrame: true,
    message: '条件为 TRUE；节点降低为无填充、描边、内边距和裁剪的 Frame',
  };
}

function subtreeIds(node: DesignerNode): string[] {
  return [node.id, ...node.children.flatMap(subtreeIds)];
}

export function projectPrototypeConditionalRuntime(
  tree: DesignerNode,
  sample: ConditionalPreviewSample,
): ConditionalRuntimeProjection {
  const byNodeId = new Map<string, ConditionalProjection>();
  const excludedNodeIds = new Set<string>();
  const hiddenDescendantIds = new Set<string>();

  const visit = (node: DesignerNode) => {
    if (node.kind === 'conditional') {
      const projection = projectPrototypeConditional(node, sample);
      byNodeId.set(node.id, projection);
      if (projection.outcome !== 'INCLUDED') {
        subtreeIds(node).forEach((nodeId) => excludedNodeIds.add(nodeId));
        node.children.flatMap(subtreeIds).forEach((nodeId) => hiddenDescendantIds.add(nodeId));
        return;
      }
    }
    node.children.forEach(visit);
  };
  visit(tree);

  return { byNodeId, excludedNodeIds, hiddenDescendantIds };
}
