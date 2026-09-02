import { isLosslessNumber } from 'lossless-json';

const CANONICAL_ARRAY_INDEX = /^(?:0|[1-9][0-9]*)$/;
const MAX_IDENTITY_LENGTH = 1024;

export interface TemplateTargetPropertyRefDescriptor {
  readonly rootPropertyId: string;
  readonly selectors: readonly TemplateTargetPropertySelectorDescriptor[];
}

export type TemplateTargetPropertySelectorDescriptor =
  | { readonly kind: 'index'; readonly index: number }
  | { readonly kind: 'member'; readonly name: string };

export interface DecodedTemplateTargetPropertyRef {
  readonly targetPropertyRef: TemplateTargetPropertyRefDescriptor;
  readonly propertyPath: string;
}

/**
 * Decodes the closed TargetPropertyRef wire union and projects its canonical
 * server identity. Selector input order is not semantic: index always precedes
 * member in the canonical `root[index].member` path.
 */
export function decodeTemplateTargetPropertyRef(
  value: unknown,
): DecodedTemplateTargetPropertyRef | null {
  if (!isClosedRecord(value, ['rootPropertyId', 'selectors'])) return null;
  const rootPropertyId = stableIdentity(value.rootPropertyId);
  if (!rootPropertyId || !Array.isArray(value.selectors) || value.selectors.length > 2) {
    return null;
  }

  let index: number | null = null;
  let member: string | null = null;
  for (const candidate of value.selectors) {
    if (!isRecord(candidate) || typeof candidate.kind !== 'string') return null;
    if (candidate.kind === 'index') {
      if (!hasExactOwnKeys(candidate, ['kind', 'index']) || index !== null) return null;
      index = canonicalIndex(candidate.index);
      if (index === null) return null;
    } else if (candidate.kind === 'member') {
      if (!hasExactOwnKeys(candidate, ['kind', 'name']) || member !== null) return null;
      member = stableIdentity(candidate.name);
      if (!member) return null;
    } else {
      return null;
    }
  }

  const selectors: TemplateTargetPropertySelectorDescriptor[] = [];
  let propertyPath = rootPropertyId;
  if (index !== null) {
    selectors.push({ kind: 'index', index });
    propertyPath += `[${index}]`;
  }
  if (member !== null) {
    selectors.push({ kind: 'member', name: member });
    propertyPath += `.${member}`;
  }
  return {
    targetPropertyRef: { rootPropertyId, selectors },
    propertyPath,
  };
}

function canonicalIndex(value: unknown): number | null {
  if (typeof value === 'number') {
    return Number.isSafeInteger(value) && value >= 0 && !Object.is(value, -0) ? value : null;
  }
  if (!isLosslessNumber(value)) return null;
  const token = value.toString();
  if (!CANONICAL_ARRAY_INDEX.test(token)) return null;
  const index = Number(token);
  return Number.isSafeInteger(index) ? index : null;
}

function stableIdentity(value: unknown): string | null {
  return typeof value === 'string'
    && value.length > 0
    && value.length <= MAX_IDENTITY_LENGTH
    ? value
    : null;
}

function isClosedRecord(
  value: unknown,
  expectedKeys: readonly string[],
): value is Record<string, unknown> {
  return isRecord(value) && hasExactOwnKeys(value, expectedKeys);
}

function hasExactOwnKeys(
  value: Record<string, unknown>,
  expectedKeys: readonly string[],
): boolean {
  const keys = Object.keys(value);
  return keys.length === expectedKeys.length
    && expectedKeys.every((key) => Object.prototype.hasOwnProperty.call(value, key));
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}
