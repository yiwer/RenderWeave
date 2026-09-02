const DECIMAL_TOKEN = /^(-?)(0|[1-9][0-9]*)(?:\.([0-9]+))?(?:[eE]([+-]?[0-9]+))?$/;
const MAX_NUMBER_TOKEN_UTF8_BYTES = 256;
const MAX_CANONICAL_UTF8_BYTES = 16 * 1024 * 1024;
const encoder = new TextEncoder();

export type TemplateCanonicalDecimalProblemCode =
  | 'INVALID_JSON'
  | 'NUMBER_TOKEN_TOO_LARGE'
  | 'CANONICAL_SIZE_EXCEEDED';

export class TemplateCanonicalDecimalError extends Error {
  constructor(readonly code: TemplateCanonicalDecimalProblemCode) {
    super(code);
    this.name = 'TemplateCanonicalDecimalError';
  }
}

export function canonicalTemplateDecimal(token: string): string {
  if (encoder.encode(token).byteLength > MAX_NUMBER_TOKEN_UTF8_BYTES) {
    throw new TemplateCanonicalDecimalError('NUMBER_TOKEN_TOO_LARGE');
  }
  const match = DECIMAL_TOKEN.exec(token);
  if (!match) throw new TemplateCanonicalDecimalError('INVALID_JSON');

  const negative = match[1] === '-';
  const integer = match[2] ?? '0';
  const fraction = match[3] ?? '';
  const exponent = BigInt(match[4] ?? '0');
  let digits = integer + fraction;
  if (/^0+$/.test(digits)) return '0';

  let decimalPosition = BigInt(integer.length) + exponent;
  const leading = digits.match(/^0+/)?.[0].length ?? 0;
  if (leading > 0) {
    digits = digits.slice(leading);
    decimalPosition -= BigInt(leading);
  }
  digits = digits.replace(/0+$/, '');

  const digitLength = BigInt(digits.length);
  let bodyLength: bigint;
  if (decimalPosition <= 0n) {
    bodyLength = 2n + (-decimalPosition) + digitLength;
  } else if (decimalPosition >= digitLength) {
    bodyLength = decimalPosition;
  } else {
    bodyLength = digitLength + 1n;
  }
  if (bodyLength + (negative ? 1n : 0n) > BigInt(MAX_CANONICAL_UTF8_BYTES)) {
    throw new TemplateCanonicalDecimalError('CANONICAL_SIZE_EXCEEDED');
  }

  let body: string;
  if (decimalPosition <= 0n) {
    body = `0.${'0'.repeat(Number(-decimalPosition))}${digits}`;
  } else if (decimalPosition >= digitLength) {
    body = digits + '0'.repeat(Number(decimalPosition - digitLength));
  } else {
    const at = Number(decimalPosition);
    body = `${digits.slice(0, at)}.${digits.slice(at)}`;
  }
  return negative ? `-${body}` : body;
}
