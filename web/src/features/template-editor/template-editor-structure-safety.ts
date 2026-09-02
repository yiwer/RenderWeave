import type { StructuredEditorCommand } from './template-editor-model';
import { objectOrNull } from './template-editor-model';
import {
  expectedTemplateChildPlacement,
  isTemplateDesignContainerKind,
  isTemplateDesignNodeKind,
} from './template-editor-node-contract';

export type TemplateStructureSafetyProblemCode =
  | 'STRUCTURE_IDENTITY_INVALID'
  | 'STRUCTURE_LEXICAL_SCOPE_INVALID'
  | 'STRUCTURE_REFERENCE_INVALID'
  | 'STRUCTURE_CONTENT_MODEL_INVALID';

export type TemplateStructureSafetyProof =
  | { state: 'safe' }
  | {
    state: 'rejected';
    code: TemplateStructureSafetyProblemCode;
    message: string;
    nodeId?: string;
    pointer?: string;
  };

/**
 * Local fail-closed proof for discrete tree commands. This is deliberately narrower
 * than the server DesignDslAuthority: it only proves invariants that a browser tree
 * edit can preserve without schema/dependency resolution.
 */
export function proveTemplateStructureCommandSafety(
  afterDesignDsl: Readonly<Record<string, unknown>>,
  command: StructuredEditorCommand,
): TemplateStructureSafetyProof {
  if (command.kind !== 'insert-node'
    && command.kind !== 'delete-node'
    && command.kind !== 'move-node') return { state: 'safe' };
  const afterRoot = objectOrNull(afterDesignDsl.designRoot);
  if (!afterRoot) {
    return unprovenContentModel('/designRoot', '命令结果缺少可证明的 DesignRoot。');
  }
  const identityProof = proveUniqueIdentities(afterDesignDsl, afterRoot);
  if (identityProof.state === 'rejected') return identityProof;
  const contentModelProof = proveTreeContentModel(afterRoot);
  if (contentModelProof.state === 'rejected') return contentModelProof;
  if (command.kind === 'delete-node') {
    if (!afterRoot || !Array.isArray(afterDesignDsl.definitions)) {
      return unprovenContentModel('/designRoot', '删除后的 DesignDSL 结构无法完成安全证明。');
    }
    const loopIds = collectRepeatLoopIds(afterRoot);
    for (let index = 0; index < afterDesignDsl.definitions.length; index += 1) {
      const definition = objectOrNull(afterDesignDsl.definitions[index]);
      const domain = definition ? objectOrNull(definition.domain) : null;
      if (!definition || (domain && domain.kind === 'loop'
        && (typeof domain.loopId !== 'string' || !loopIds.has(domain.loopId)))) {
        const pointer = `/definitions/${index}/domain/loopId`;
        const loopId = domain && typeof domain.loopId === 'string' ? domain.loopId : 'unknown';
        return {
          state: 'rejected',
          code: 'STRUCTURE_REFERENCE_INVALID',
          pointer,
          message: `删除会让 ${pointer} 引用不存在的 loopId ${loopId}，已拒绝整个命令。`,
        };
      }
    }
  }
  const referenceProof = proveLocalLexicalReferences(afterDesignDsl, afterRoot);
  if (referenceProof.state === 'rejected') return referenceProof;
  return { state: 'safe' };
}

function collectRepeatLoopIds(node: Record<string, unknown>, target = new Set<string>()): Set<string> {
  if (node.kind === 'repeat' && typeof node.loopId === 'string') target.add(node.loopId);
  if (!Array.isArray(node.children)) return target;
  for (const candidate of node.children) {
    const child = objectOrNull(candidate);
    if (child) collectRepeatLoopIds(child, target);
  }
  return target;
}

function proveUniqueIdentities(
  designDsl: Readonly<Record<string, unknown>>,
  root: Record<string, unknown>,
): TemplateStructureSafetyProof {
  if (!Array.isArray(designDsl.definitions)) {
    return unprovenContentModel('/definitions', '命令结果缺少可证明的 definitions[]。');
  }
  const definitions = new Set<string>();
  for (let index = 0; index < designDsl.definitions.length; index += 1) {
    const definition = objectOrNull(designDsl.definitions[index]);
    const pointer = `/definitions/${index}/definitionId`;
    const problem = recordIdentity(definitions, definition?.definitionId, pointer, 'definitionId');
    if (problem) return problem;
  }

  const nodeIds = new Set<string>();
  const bindingIds = new Set<string>();
  const loopIds = new Set<string>();
  const useIds = new Set<string>();
  const visit = (node: Record<string, unknown>, pointer: string): TemplateStructureSafetyProof | null => {
    let problem = recordIdentity(nodeIds, node.nodeId, `${pointer}/nodeId`, 'nodeId');
    if (problem) return problem;
    if (!Array.isArray(node.bindings)) {
      return unprovenContentModel(`${pointer}/bindings`, '节点 bindings[] 无法完成身份安全证明。');
    }
    for (let index = 0; index < node.bindings.length; index += 1) {
      const binding = objectOrNull(node.bindings[index]);
      problem = recordIdentity(
        bindingIds,
        binding?.bindingId,
        `${pointer}/bindings/${index}/bindingId`,
        'bindingId',
      );
      if (problem) return problem;
    }
    if (node.kind === 'repeat') {
      problem = recordIdentity(loopIds, node.loopId, `${pointer}/loopId`, 'loopId');
      if (problem) return problem;
    }
    if (node.kind === 'templateUse') {
      problem = recordIdentity(useIds, node.useId, `${pointer}/useId`, 'useId');
      if (problem) return problem;
    }
    if (!Array.isArray(node.children)) return null;
    for (let index = 0; index < node.children.length; index += 1) {
      const child = objectOrNull(node.children[index]);
      if (!child) {
        return unprovenContentModel(
          `${pointer}/children/${index}`,
          'children[] 含无法证明的节点。',
        );
      }
      const childProblem = visit(child, `${pointer}/children/${index}`);
      if (childProblem) return childProblem;
    }
    return null;
  };
  return visit(root, '/designRoot') ?? { state: 'safe' };
}

function proveTreeContentModel(root: Record<string, unknown>): TemplateStructureSafetyProof {
  if (root.kind !== 'canvas' || !Array.isArray(root.children)) {
    return unprovenContentModel('/designRoot', 'DesignRoot 必须是拥有 children[] 的唯一 Canvas。');
  }
  const visitChildren = (
    parent: Record<string, unknown>,
    pointer: string,
  ): TemplateStructureSafetyProof | null => {
    if (!isTemplateDesignContainerKind(parent.kind)
      || !Array.isArray(parent.children)) {
      return unprovenContentModel(`${pointer}/children`, '父节点 ContentModel 不允许 children[]。');
    }
    if ((parent.kind === 'repeat' || parent.kind === 'conditional')
      && parent.children.length === 0) {
      return unprovenContentModel(`${pointer}/children`, `${parent.kind} 必须保留 authored child。`);
    }
    const expectedPlacement = expectedTemplateChildPlacement(parent.kind);
    if (!expectedPlacement) {
      return unprovenContentModel(`${pointer}/kind`, '父节点 placement variant 无法证明。');
    }
    for (let index = 0; index < parent.children.length; index += 1) {
      const childPointer = `${pointer}/children/${index}`;
      const child = objectOrNull(parent.children[index]);
      if (!child || !isTemplateDesignNodeKind(child.kind)
        || child.kind === 'canvas') {
        return unprovenContentModel(childPointer, 'children[] 含不受支持的 Design Node。');
      }
      const placement = objectOrNull(child.placement);
      if (!placement || placement.type !== expectedPlacement) {
        return unprovenContentModel(
          `${childPointer}/placement/type`,
          `节点 ${String(child.nodeId)} 必须使用 ${expectedPlacement} placement。`,
          typeof child.nodeId === 'string' ? child.nodeId : undefined,
        );
      }
      if (isTemplateDesignContainerKind(child.kind)) {
        const nestedProblem = visitChildren(child, childPointer);
        if (nestedProblem) return nestedProblem;
      } else if (child.children !== undefined) {
        return unprovenContentModel(
          `${childPointer}/children`,
          `叶节点 ${String(child.nodeId)} 不能拥有 children[]。`,
          typeof child.nodeId === 'string' ? child.nodeId : undefined,
        );
      }
    }
    return null;
  };
  return visitChildren(root, '/designRoot') ?? { state: 'safe' };
}

interface DefinitionScope {
  readonly loopId: string | null;
}

function proveLocalLexicalReferences(
  designDsl: Readonly<Record<string, unknown>>,
  root: Record<string, unknown>,
): TemplateStructureSafetyProof {
  if (!Array.isArray(designDsl.definitions)) {
    return unprovenContentModel('/definitions', 'definitions[] 无法完成引用安全证明。');
  }
  const loopScopes = collectRepeatLoopScopes(root);
  const definitions = new Map<string, DefinitionScope>();
  for (let index = 0; index < designDsl.definitions.length; index += 1) {
    const definition = objectOrNull(designDsl.definitions[index]);
    const definitionId = definition?.definitionId;
    if (!definition || typeof definitionId !== 'string') {
      return invalidReference(`/definitions/${index}/definitionId`, 'Definition identity 无法解析。');
    }
    if (definition.kind === 'custom') {
      definitions.set(definitionId, { loopId: null });
      continue;
    }
    const domain = definition.domain;
    if (domain === 'invocation') {
      definitions.set(definitionId, { loopId: null });
      continue;
    }
    const loopDomain = objectOrNull(domain);
    const loopId = loopDomain?.kind === 'loop' && typeof loopDomain.loopId === 'string'
      ? loopDomain.loopId
      : null;
    if (!loopId || !loopScopes.has(loopId)) {
      return invalidReference(
        `/definitions/${index}/domain/loopId`,
        `Definition ${definitionId} 引用了不存在的 loopId ${loopId ?? 'unknown'}。`,
      );
    }
    definitions.set(definitionId, { loopId });
  }

  const definitionProblem = proveDefinitionSources(
    designDsl.definitions,
    definitions,
    loopScopes,
  );
  if (definitionProblem) return definitionProblem;

  const visitNode = (
    node: Record<string, unknown>,
    pointer: string,
    scope: readonly string[],
  ): TemplateStructureSafetyProof | null => {
    const nodeId = typeof node.nodeId === 'string' ? node.nodeId : undefined;
    if (!Array.isArray(node.bindings)) {
      return unprovenContentModel(`${pointer}/bindings`, '节点 bindings[] 无法完成引用安全证明。');
    }
    for (let index = 0; index < node.bindings.length; index += 1) {
      const binding = objectOrNull(node.bindings[index]);
      const sourcePointer = `${pointer}/bindings/${index}/source`;
      const sourceProblem = proveSource(
        binding?.source,
        sourcePointer,
        scope,
        definitions,
        new Set(['context', 'loopIndex', 'definition']),
        nodeId,
      );
      if (sourceProblem) return sourceProblem;
    }
    if (node.kind === 'repeat') {
      const itemsProblem = proveSource(
        node.items,
        `${pointer}/items`,
        scope,
        definitions,
        new Set(['literal', 'context', 'definition']),
        nodeId,
      );
      if (itemsProblem) return itemsProblem;
    } else if (node.kind === 'conditional') {
      const conditionProblem = proveSource(
        node.condition,
        `${pointer}/condition`,
        scope,
        definitions,
        new Set(['literal', 'context', 'definition']),
        nodeId,
      );
      if (conditionProblem) return conditionProblem;
    } else if (node.kind === 'templateUse') {
      const selector = objectOrNull(node.contextSelector);
      if (!selector) return invalidReference(`${pointer}/contextSelector`, 'ContextSelector 无法解析。', nodeId);
      if (selector.kind === 'context') {
        const domainProblem = proveDomain(
          selector.domain,
          `${pointer}/contextSelector/domain`,
          scope,
          nodeId,
        );
        if (domainProblem) return domainProblem;
      } else if (selector.kind !== 'empty') {
        return invalidReference(`${pointer}/contextSelector/kind`, 'ContextSelector kind 无法证明。', nodeId);
      }
      if (!Array.isArray(node.fills)) {
        return invalidReference(`${pointer}/fills`, 'TemplateUse fills[] 无法解析。', nodeId);
      }
      for (let index = 0; index < node.fills.length; index += 1) {
        const fill = objectOrNull(node.fills[index]);
        const fillProblem = proveSource(
          fill?.source,
          `${pointer}/fills/${index}/source`,
          scope,
          definitions,
          new Set(['context', 'loopIndex', 'definition']),
          nodeId,
        );
        if (fillProblem) return fillProblem;
      }
    }
    if (!Array.isArray(node.children)) return null;
    const childScope = node.kind === 'repeat' && typeof node.loopId === 'string'
      ? [...scope, node.loopId]
      : scope;
    for (let index = 0; index < node.children.length; index += 1) {
      const child = objectOrNull(node.children[index]);
      if (!child) return unprovenContentModel(`${pointer}/children/${index}`, 'child 无法解析。');
      const childProblem = visitNode(child, `${pointer}/children/${index}`, childScope);
      if (childProblem) return childProblem;
    }
    return null;
  };
  return visitNode(root, '/designRoot', []) ?? { state: 'safe' };
}

function collectRepeatLoopScopes(
  node: Record<string, unknown>,
  scope: readonly string[] = [],
  target = new Map<string, readonly string[]>(),
): Map<string, readonly string[]> {
  const childScope = node.kind === 'repeat' && typeof node.loopId === 'string'
    ? [...scope, node.loopId]
    : scope;
  if (node.kind === 'repeat' && typeof node.loopId === 'string') {
    target.set(node.loopId, childScope);
  }
  if (!Array.isArray(node.children)) return target;
  for (const candidate of node.children) {
    const child = objectOrNull(candidate);
    if (child) collectRepeatLoopScopes(child, childScope, target);
  }
  return target;
}

function proveDefinitionSources(
  values: readonly unknown[],
  definitions: ReadonlyMap<string, DefinitionScope>,
  loopScopes: ReadonlyMap<string, readonly string[]>,
): TemplateStructureSafetyProof | null {
  for (let index = 0; index < values.length; index += 1) {
    const definition = objectOrNull(values[index]);
    if (!definition || typeof definition.definitionId !== 'string') continue;
    const definitionScope = definitions.get(definition.definitionId);
    const scope = definitionScope?.loopId
      ? loopScopes.get(definitionScope.loopId) ?? []
      : [];
    const sources: Array<{ value: unknown; pointer: string }> = [];
    if (definition.kind === 'mapping') {
      sources.push(
        { value: definition.input, pointer: `/definitions/${index}/input` },
        { value: definition.otherwise, pointer: `/definitions/${index}/otherwise` },
      );
      if (Array.isArray(definition.cases)) {
        definition.cases.forEach((entry, caseIndex) => {
          sources.push({
            value: objectOrNull(entry)?.then,
            pointer: `/definitions/${index}/cases/${caseIndex}/then`,
          });
        });
      }
    } else if (definition.kind === 'expression' && Array.isArray(definition.inputs)) {
      definition.inputs.forEach((entry, inputIndex) => {
        sources.push({
          value: objectOrNull(entry)?.source,
          pointer: `/definitions/${index}/inputs/${inputIndex}/source`,
        });
      });
    }
    for (const source of sources) {
      const problem = proveSource(
        source.value,
        source.pointer,
        scope,
        definitions,
        new Set(['literal', 'context', 'loopIndex', 'definition', 'capability']),
      );
      if (problem) return problem;
    }
  }
  return null;
}

function proveSource(
  value: unknown,
  pointer: string,
  scope: readonly string[],
  definitions: ReadonlyMap<string, DefinitionScope>,
  allowedKinds: ReadonlySet<string>,
  nodeId?: string,
): TemplateStructureSafetyProof | null {
  const source = objectOrNull(value);
  const kind = source?.kind;
  if (!source || typeof kind !== 'string' || !allowedKinds.has(kind)) {
    return invalidReference(`${pointer}/kind`, 'ValueSource kind 无法完成本地安全证明。', nodeId);
  }
  if (kind === 'literal' || kind === 'capability') return null;
  if (kind === 'context') return proveDomain(source.domain, `${pointer}/domain`, scope, nodeId);
  if (kind === 'loopIndex') {
    const loopId = typeof source.loopId === 'string' ? source.loopId : null;
    if (!loopId || !scope.includes(loopId)) {
      return invalidLexical(
        `${pointer}/loopId`,
        `节点 ${nodeId ?? 'definition'} 的 loopId ${loopId ?? 'unknown'} 在当前位置不可达。`,
        nodeId,
      );
    }
    return null;
  }
  const definitionId = typeof source.definitionId === 'string' ? source.definitionId : null;
  const target = definitionId ? definitions.get(definitionId) : null;
  if (!definitionId || !target) {
    return invalidReference(
      `${pointer}/definitionId`,
      `ValueSource 引用了不存在的 definitionId ${definitionId ?? 'unknown'}。`,
      nodeId,
    );
  }
  if (target.loopId && !scope.includes(target.loopId)) {
    return invalidLexical(
      `${pointer}/definitionId`,
      `definitionId ${definitionId} 的 loop domain 在当前位置不可达。`,
      nodeId,
    );
  }
  return null;
}

function proveDomain(
  value: unknown,
  pointer: string,
  scope: readonly string[],
  nodeId?: string,
): TemplateStructureSafetyProof | null {
  if (value === 'invocation') return null;
  const domain = objectOrNull(value);
  if (domain?.kind === 'invocation') return null;
  const loopId = domain?.kind === 'loop' && typeof domain.loopId === 'string'
    ? domain.loopId
    : null;
  if (!loopId || !scope.includes(loopId)) {
    return invalidLexical(
      `${pointer}/loopId`,
      `context loopId ${loopId ?? 'unknown'} 在当前位置不可达。`,
      nodeId,
    );
  }
  return null;
}

function invalidReference(
  pointer: string,
  message: string,
  nodeId?: string,
): TemplateStructureSafetyProof {
  return { state: 'rejected', code: 'STRUCTURE_REFERENCE_INVALID', pointer, message, nodeId };
}

function invalidLexical(
  pointer: string,
  message: string,
  nodeId?: string,
): TemplateStructureSafetyProof {
  return { state: 'rejected', code: 'STRUCTURE_LEXICAL_SCOPE_INVALID', pointer, message, nodeId };
}

function recordIdentity(
  seen: Set<string>,
  value: unknown,
  pointer: string,
  label: string,
): Extract<TemplateStructureSafetyProof, { state: 'rejected' }> | null {
  if (typeof value !== 'string' || value.length === 0 || seen.has(value)) {
    const identity = typeof value === 'string' && value.length > 0 ? value : 'unknown';
    return {
      state: 'rejected',
      code: 'STRUCTURE_IDENTITY_INVALID',
      pointer,
      message: `${pointer} 的 ${label} ${identity} 缺失或不唯一，已拒绝整个命令。`,
    };
  }
  seen.add(value);
  return null;
}

function unprovenContentModel(
  pointer: string,
  message: string,
  nodeId?: string,
): TemplateStructureSafetyProof {
  return { state: 'rejected', code: 'STRUCTURE_CONTENT_MODEL_INVALID', pointer, message, nodeId };
}
