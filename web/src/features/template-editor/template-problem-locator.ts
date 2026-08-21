import type { EditorNodeProjection } from './template-editor-model';

const MAX_POINTER_LENGTH = 2048;
const MAX_POINTER_SEGMENTS = 256;
const CANONICAL_ARRAY_INDEX = /^(?:0|[1-9][0-9]*)$/;

export type TemplateProblemLocation =
  | {
    readonly state: 'located';
    readonly target:
      | { readonly kind: 'template-display-name'; readonly label: string }
      | { readonly kind: 'node'; readonly nodeId: string; readonly label: string }
      | { readonly kind: 'definitions'; readonly label: string };
    readonly precision: 'exact' | 'owning-node' | 'section';
  }
  | {
    readonly state: 'unavailable';
    readonly reason:
      | 'SUMMARY_ONLY'
      | 'POINTER_LIMIT_EXCEEDED'
      | 'MALFORMED_POINTER'
      | 'INVALID_ARRAY_INDEX'
      | 'TARGET_NOT_FOUND'
      | 'UNSUPPORTED_TARGET';
  };

type UnavailableLocation = Extract<TemplateProblemLocation, { state: 'unavailable' }>;

interface DecodedPointer {
  readonly state: 'decoded';
  readonly segments: readonly string[];
}

interface PointerFailure {
  readonly state: 'unavailable';
  readonly reason: 'POINTER_LIMIT_EXCEEDED' | 'MALFORMED_POINTER';
}

interface NodeTarget {
  readonly kind: 'node';
  readonly nodeId: string;
  readonly label: string;
}

/**
 * Projects a server-owned canonical pointer onto the editor's already-renderable,
 * stable targets. It intentionally has no fuzzy path matching or selector building.
 */
export function locateTemplateProblem(
  designDsl: Record<string, unknown>,
  nodes: readonly EditorNodeProjection[],
  canonicalPointer: string,
): TemplateProblemLocation {
  if (canonicalPointer === '') {
    return unavailable('SUMMARY_ONLY');
  }
  const decoded = decodePointer(canonicalPointer);
  if (decoded.state === 'unavailable') return decoded;

  const [rootSegment] = decoded.segments;
  if (rootSegment === 'displayName') {
    return decoded.segments.length === 1
      && own(designDsl, 'displayName')
      && typeof designDsl.displayName === 'string'
      ? {
        state: 'located',
        target: { kind: 'template-display-name', label: 'Template 名称' },
        precision: 'exact',
      }
      : unavailable('TARGET_NOT_FOUND');
  }

  if (rootSegment === 'definitions') {
    const walked = walkPointer(designDsl, decoded.segments);
    if (walked.failure === 'INVALID_ARRAY_INDEX') return unavailable(walked.failure);
    return {
      state: 'located',
      target: { kind: 'definitions', label: '定义' },
      precision: 'section',
    };
  }

  if (rootSegment !== 'designRoot') return unavailable('UNSUPPORTED_TARGET');

  const projectedNodes = unambiguousNodeTargets(nodes);
  let current: unknown = designDsl;
  let deepestTarget: NodeTarget | null = null;
  let exactWalk = true;
  let traversedUnprojectedNode = false;

  for (const segment of decoded.segments) {
    if (Array.isArray(current)) {
      const index = arrayIndex(segment);
      if (index === 'invalid') return unavailable('INVALID_ARRAY_INDEX');
      if (index >= current.length) return unavailable('TARGET_NOT_FOUND');
      current = current[index];
    } else if (isRecord(current)) {
      if (!own(current, segment)) {
        exactWalk = false;
        break;
      }
      current = current[segment];
    } else {
      exactWalk = false;
      break;
    }

    if (isRecord(current) && isAuthoredNode(current)) {
      const target = projectedNodes.get(current.nodeId);
      if (target) {
        deepestTarget = target;
        traversedUnprojectedNode = false;
      } else {
        traversedUnprojectedNode = true;
      }
    }
  }

  if (deepestTarget === null) return unavailable('TARGET_NOT_FOUND');
  return {
    state: 'located',
    target: deepestTarget,
    precision: exactWalk && !traversedUnprojectedNode ? 'exact' : 'owning-node',
  };
}

function decodePointer(pointer: string): DecodedPointer | PointerFailure {
  if (pointer.length > MAX_POINTER_LENGTH) return pointerFailure('POINTER_LIMIT_EXCEEDED');
  if (!pointer.startsWith('/')) return pointerFailure('MALFORMED_POINTER');
  const encodedSegments = pointer.slice(1).split('/');
  if (encodedSegments.length > MAX_POINTER_SEGMENTS) {
    return pointerFailure('POINTER_LIMIT_EXCEEDED');
  }

  const segments: string[] = [];
  for (const encoded of encodedSegments) {
    let decoded = '';
    for (let index = 0; index < encoded.length; index += 1) {
      const character = encoded[index];
      if (character !== '~') {
        decoded += character;
        continue;
      }
      const escaped = encoded[index + 1];
      if (escaped === '0') decoded += '~';
      else if (escaped === '1') decoded += '/';
      else return pointerFailure('MALFORMED_POINTER');
      index += 1;
    }
    segments.push(decoded);
  }
  return { state: 'decoded', segments };
}

function pointerFailure(reason: PointerFailure['reason']): PointerFailure {
  return { state: 'unavailable', reason };
}

function walkPointer(
  root: Record<string, unknown>,
  segments: readonly string[],
): { readonly failure: 'INVALID_ARRAY_INDEX' | 'TARGET_NOT_FOUND' | null } {
  let current: unknown = root;
  for (const segment of segments) {
    if (Array.isArray(current)) {
      const index = arrayIndex(segment);
      if (index === 'invalid') return { failure: 'INVALID_ARRAY_INDEX' };
      if (index >= current.length) return { failure: 'TARGET_NOT_FOUND' };
      current = current[index];
    } else if (isRecord(current)) {
      if (!own(current, segment)) return { failure: 'TARGET_NOT_FOUND' };
      current = current[segment];
    } else {
      return { failure: 'TARGET_NOT_FOUND' };
    }
  }
  return { failure: null };
}

function unambiguousNodeTargets(
  nodes: readonly EditorNodeProjection[],
): ReadonlyMap<string, NodeTarget> {
  const targets = new Map<string, NodeTarget>();
  const ambiguous = new Set<string>();
  for (const node of nodes) {
    if (ambiguous.has(node.nodeId)) continue;
    if (targets.has(node.nodeId)) {
      targets.delete(node.nodeId);
      ambiguous.add(node.nodeId);
      continue;
    }
    targets.set(node.nodeId, {
      kind: 'node',
      nodeId: node.nodeId,
      label: node.displayName,
    });
  }
  return targets;
}

function arrayIndex(segment: string): number | 'invalid' {
  if (!CANONICAL_ARRAY_INDEX.test(segment)) return 'invalid';
  const index = Number(segment);
  return Number.isSafeInteger(index) ? index : Number.MAX_SAFE_INTEGER;
}

function isAuthoredNode(value: Record<string, unknown>): value is Record<string, unknown> & {
  nodeId: string;
  kind: string;
} {
  return typeof value.nodeId === 'string'
    && value.nodeId.length > 0
    && value.nodeId.length <= 1024
    && typeof value.kind === 'string'
    && value.kind.length > 0;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function own(value: Record<string, unknown>, key: string): boolean {
  return Object.prototype.hasOwnProperty.call(value, key);
}

function unavailable(reason: UnavailableLocation['reason']): UnavailableLocation {
  return { state: 'unavailable', reason };
}
