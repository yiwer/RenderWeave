import { isLosslessNumber } from 'lossless-json';

/** Projects an authored JSON number into the finite browser-number editing domain. */
export function finiteTemplateNumber(value: unknown): number | null {
  const candidate = typeof value === 'number'
    ? value
    : isLosslessNumber(value)
      ? Number(value.toString())
      : Number.NaN;
  return Number.isFinite(candidate) ? candidate : null;
}

/** Projects an authored JSON number into the positive browser-number editing domain. */
export function positiveTemplateNumber(value: unknown): number | null {
  const candidate = finiteTemplateNumber(value);
  return candidate !== null && candidate > 0 ? candidate : null;
}

/** Returns the exact authored token for form drafts whenever one exists. */
export function templateNumberDraft(value: unknown): string {
  if (isLosslessNumber(value)) return value.toString();
  return typeof value === 'number' && Number.isFinite(value) ? String(value) : '';
}

/** Compares an authored value with a finite browser-number edit without rewriting its token. */
export function sameTemplateNumber(value: unknown, candidate: number): boolean {
  if (!Number.isFinite(candidate)) return false;
  return finiteTemplateNumber(value) === candidate;
}
