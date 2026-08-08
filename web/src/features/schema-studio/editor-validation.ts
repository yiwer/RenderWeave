import type {
  ArrayEditorValue,
  DecimalEditorValue,
  EditorField,
  EditorValue,
  EnumInput,
  OptionalInput,
  OrderedEditorValue,
  TextEditorValue,
} from './editor-types';
import type { EditorSession } from './editor-session';

export interface EditorDiagnostic {
  code: string;
  pointer: string;
  message: string;
  rowKey?: string;
}

const SCHEMA_KEY = /^[a-z0-9][a-z0-9-]{0,62}$/;
const VERSION_TAG = /^[a-z0-9][a-z0-9._-]{0,63}$/;
const DECIMAL_TOKEN = /^-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?$/;
const DATE_TOKEN = /^(\d{4})-(\d{2})-(\d{2})$/;
const TIME_TOKEN = /^(\d{2}):(\d{2}):(\d{2})$/;

export function localDiagnostics(session: EditorSession): EditorDiagnostic[] {
  const diagnostics: EditorDiagnostic[] = [];
  if (!SCHEMA_KEY.test(session.schemaKey) || session.schemaKey.startsWith('system-')) {
    add(diagnostics, 'SCHEMA_KEY_INVALID', '/schemaKey',
      'schemaKey 需为 1–63 位小写字母、数字或连字符，且不能使用 system- 前缀。');
  }
  const displayLength = codePointLength(session.displayName.trim());
  if (displayLength < 1 || displayLength > 128) {
    add(diagnostics, 'METADATA_VALUE_INVALID', '/definition/displayName',
      '显示名称去除首尾空白后需为 1–128 个 Unicode code points。');
  }
  if (codePointLength(session.description.trim()) > 2_048) {
    add(diagnostics, 'METADATA_VALUE_INVALID', '/definition/description', '说明最多 2048 个字符。');
  }
  if (session.fields.length > 256) {
    add(diagnostics, 'FIELD_LIMIT_EXCEEDED', '/definition/fields', '一个 Schema 最多包含 256 个直接字段。');
  }

  const keyCounts = new Map<string, number>();
  session.fields.forEach((field) => keyCounts.set(field.fieldKey, (keyCounts.get(field.fieldKey) ?? 0) + 1));
  session.fields.forEach((field, index) => {
    const base = `/definition/fields/${index}`;
    const bytes = new TextEncoder().encode(field.fieldKey).length;
    if (!field.fieldKey || bytes > 128 || /\p{Cc}/u.test(field.fieldKey)) {
      add(diagnostics, 'FIELD_KEY_INVALID', `${base}/fieldKey`,
        'fieldKey 不能为空、不能含控制字符，且最多 128 UTF-8 bytes。', field.rowKey);
    } else if ((keyCounts.get(field.fieldKey) ?? 0) > 1) {
      add(diagnostics, 'FIELD_KEY_DUPLICATE', `${base}/fieldKey`,
        `fieldKey “${field.fieldKey}” 在当前 Schema 中重复。`, field.rowKey);
    }
    if (field.displayName && invalidTrimmedLength(field.displayName, 128)) {
      add(diagnostics, 'METADATA_VALUE_INVALID', `${base}/displayName`,
        '字段显示名称去除首尾空白后需为 1–128 个字符。', field.rowKey);
    }
    if (codePointLength(field.description.trim()) > 2_048) {
      add(diagnostics, 'METADATA_VALUE_INVALID', `${base}/description`,
        '字段说明最多 2048 个字符。', field.rowKey);
    }
    validateValue(field.value, `${base}/value`, field, diagnostics, false);
  });
  return diagnostics;
}

export function countDraftReferences(session: EditorSession): number {
  return session.fields.filter((field) => {
    const value = field.value.type === 'array' ? field.value.items : field.value;
    return value.type === 'reference' && value.referenceKind === 'draft';
  }).length;
}

function validateValue(
  value: EditorValue,
  pointer: string,
  field: EditorField,
  diagnostics: EditorDiagnostic[],
  arrayItem: boolean,
) {
  switch (value.type) {
    case 'text':
      validateText(value, pointer, field, diagnostics);
      break;
    case 'decimal':
      validateDecimal(value, pointer, field, diagnostics);
      break;
    case 'date':
    case 'time':
      validateOrdered(value, pointer, field, diagnostics);
      break;
    case 'boolean':
      if (value.constValue.enabled && !['true', 'false'].includes(value.constValue.value)) {
        fieldProblem(diagnostics, 'CONSTRAINT_VALUE_INVALID', `${pointer}/constraints/const`,
          '布尔 const 只能为 true 或 false。', field);
      }
      break;
    case 'reference':
      if (!SCHEMA_KEY.test(value.schemaKey)
          || (value.referenceKind === 'draft' && value.schemaKey.startsWith('system-'))) {
        fieldProblem(diagnostics, 'REFERENCE_SCHEMA_KEY_INVALID', `${pointer}/ref/schemaKey`,
          '请选择或填写一个合法的目标 schemaKey。', field);
      }
      if (value.referenceKind === 'static' && !VERSION_TAG.test(value.versionTag)) {
        fieldProblem(diagnostics, 'REFERENCE_VERSION_TAG_INVALID', `${pointer}/ref/versionTag`,
          'StaticSchema 引用必须填写合法 versionTag。', field);
      }
      break;
    case 'array':
      if (arrayItem) {
        fieldProblem(diagnostics, 'ARRAY_NESTED_UNSUPPORTED', `${pointer}/type`,
          '数组元素不能再次是数组。', field);
        return;
      }
      validateArray(value, pointer, field, diagnostics);
      break;
  }
}

function validateText(
  value: TextEditorValue,
  pointer: string,
  field: EditorField,
  diagnostics: EditorDiagnostic[],
) {
  const min = parseIntegerInput(value.minLength, 65_536, `${pointer}/constraints/minLength`, field, diagnostics);
  const max = parseIntegerInput(value.maxLength, 65_536, `${pointer}/constraints/maxLength`, field, diagnostics);
  if (min !== undefined && max !== undefined && min > max) {
    fieldProblem(diagnostics, 'CONSTRAINT_RANGE_INVALID', `${pointer}/constraints`,
      'minLength 不能大于 maxLength。', field);
  }
  let compiled: RegExp | undefined;
  if (value.pattern.enabled) {
    const patternPointer = `${pointer}/constraints/pattern`;
    if (codePointLength(value.pattern.value) > 1_024) {
      fieldProblem(diagnostics, 'CONSTRAINT_VALUE_INVALID', patternPointer,
        'pattern 最多 1024 个 Unicode code points。', field);
    } else if (unsafePattern(value.pattern.value)) {
      fieldProblem(diagnostics, 'REGEX_UNSAFE', patternPointer,
        'pattern 不能使用反向引用、lookaround、inline flag 或高风险嵌套量词。', field);
    } else {
      try {
        compiled = new RegExp(value.pattern.value);
      } catch {
        fieldProblem(diagnostics, 'REGEX_INVALID', patternPointer, 'pattern 不是合法正则表达式。', field);
      }
    }
  }
  validateEnumAndConst(value.enumValues, value.constValue, pointer, field, diagnostics, (literal, literalPointer) => {
    const length = codePointLength(literal);
    if ((min !== undefined && length < min) || (max !== undefined && length > max)
        || (compiled && !compiled.test(literal))) {
      fieldProblem(diagnostics, 'CONSTRAINT_LITERAL_VIOLATION', literalPointer,
        'enum/const 值必须同时满足长度与 pattern 约束。', field);
    }
  });
}

function validateDecimal(
  value: DecimalEditorValue,
  pointer: string,
  field: EditorField,
  diagnostics: EditorDiagnostic[],
) {
  const parsed = {
    min: parseDecimalInput(value.min, `${pointer}/constraints/min`, field, diagnostics),
    exclusiveMin: parseDecimalInput(value.exclusiveMin, `${pointer}/constraints/exclusiveMin`, field, diagnostics),
    max: parseDecimalInput(value.max, `${pointer}/constraints/max`, field, diagnostics),
    exclusiveMax: parseDecimalInput(value.exclusiveMax, `${pointer}/constraints/exclusiveMax`, field, diagnostics),
    multipleOf: parseDecimalInput(value.multipleOf, `${pointer}/constraints/multipleOf`, field, diagnostics),
  };
  validateBounds(value, parsed, pointer, field, diagnostics);
  if (parsed.multipleOf && parsed.multipleOf.coefficient <= 0n) {
    fieldProblem(diagnostics, 'CONSTRAINT_VALUE_INVALID', `${pointer}/constraints/multipleOf`,
      'multipleOf 必须大于 0。', field);
  }
  validateEnumAndConst(value.enumValues, value.constValue, pointer, field, diagnostics, (literal, literalPointer) => {
    const decimal = parseExactDecimal(literal);
    if (!decimal) {
      fieldProblem(diagnostics, 'CONSTRAINT_VALUE_INVALID', literalPointer,
        'decimal enum/const 必须是合法且在精度范围内的 JSON number。', field);
      return;
    }
    if (!decimalSatisfies(decimal, parsed)) {
      fieldProblem(diagnostics, 'CONSTRAINT_LITERAL_VIOLATION', literalPointer,
        'enum/const 值必须同时满足 decimal 边界与 multipleOf。', field);
    }
  }, decimalEqual);
}

function validateOrdered(
  value: OrderedEditorValue,
  pointer: string,
  field: EditorField,
  diagnostics: EditorDiagnostic[],
) {
  const parser = value.type === 'date' ? parseDate : parseTime;
  const parsed = {
    min: parseOrderedInput(value.min, `${pointer}/constraints/min`, parser, value.type, field, diagnostics),
    exclusiveMin: parseOrderedInput(value.exclusiveMin, `${pointer}/constraints/exclusiveMin`, parser, value.type, field, diagnostics),
    max: parseOrderedInput(value.max, `${pointer}/constraints/max`, parser, value.type, field, diagnostics),
    exclusiveMax: parseOrderedInput(value.exclusiveMax, `${pointer}/constraints/exclusiveMax`, parser, value.type, field, diagnostics),
  };
  validateBounds(value, parsed, pointer, field, diagnostics);
  validateEnumAndConst(value.enumValues, value.constValue, pointer, field, diagnostics, (literal, literalPointer) => {
    const ordered = parser(literal);
    if (ordered === undefined) {
      fieldProblem(diagnostics, 'CONSTRAINT_VALUE_INVALID', literalPointer,
        `${value.type === 'date' ? '日期' : '时间'}值格式无效。`, field);
      return;
    }
    if (!orderedSatisfies(ordered, parsed)) {
      fieldProblem(diagnostics, 'CONSTRAINT_LITERAL_VIOLATION', literalPointer,
        'enum/const 值必须同时满足上下界。', field);
    }
  });
}

function validateArray(
  value: ArrayEditorValue,
  pointer: string,
  field: EditorField,
  diagnostics: EditorDiagnostic[],
) {
  const min = parseIntegerInput(value.minItems, 10_000, `${pointer}/constraints/minItems`, field, diagnostics);
  const max = parseIntegerInput(value.maxItems, 10_000, `${pointer}/constraints/maxItems`, field, diagnostics);
  if (min !== undefined && max !== undefined && min > max) {
    fieldProblem(diagnostics, 'CONSTRAINT_RANGE_INVALID', `${pointer}/constraints`,
      'minItems 不能大于 maxItems。', field);
  }
  if (value.uniqueItems && value.items.type === 'reference') {
    fieldProblem(diagnostics, 'ARRAY_UNIQUE_ITEMS_UNSUPPORTED', `${pointer}/constraints/uniqueItems`,
      '对象数组不支持 uniqueItems。', field);
  }
  validateValue(value.items, `${pointer}/items`, field, diagnostics, true);
}

function validateEnumAndConst(
  enumValues: EnumInput,
  constValue: OptionalInput,
  pointer: string,
  field: EditorField,
  diagnostics: EditorDiagnostic[],
  validateLiteral: (literal: string, pointer: string) => void,
  equal: (left: string, right: string) => boolean = (left, right) => left === right,
) {
  if (enumValues.enabled && constValue.enabled) {
    fieldProblem(diagnostics, 'CONSTRAINT_CONFLICT', `${pointer}/constraints`,
      'enum 与 const 不能同时启用。', field);
  }
  if (enumValues.enabled) {
    if (enumValues.values.length < 1 || enumValues.values.length > 256) {
      fieldProblem(diagnostics, 'CONSTRAINT_ENUM_INVALID', `${pointer}/constraints/enum`,
        'enum 必须包含 1–256 个值。', field);
    }
    enumValues.values.forEach((literal, index) => {
      if (enumValues.values.slice(0, index).some((earlier) => equal(earlier, literal))) {
        fieldProblem(diagnostics, 'CONSTRAINT_ENUM_DUPLICATE', `${pointer}/constraints/enum/${index}`,
          'enum 值按类型相等后必须唯一。', field);
      }
      validateLiteral(literal, `${pointer}/constraints/enum/${index}`);
    });
  }
  if (constValue.enabled) validateLiteral(constValue.value, `${pointer}/constraints/const`);
}

type Comparable = ExactDecimal | number;

function validateBounds<T extends Comparable>(
  inputs: { min: OptionalInput; exclusiveMin: OptionalInput; max: OptionalInput; exclusiveMax: OptionalInput },
  parsed: { min?: T; exclusiveMin?: T; max?: T; exclusiveMax?: T },
  pointer: string,
  field: EditorField,
  diagnostics: EditorDiagnostic[],
) {
  if (inputs.min.enabled && inputs.exclusiveMin.enabled) {
    fieldProblem(diagnostics, 'CONSTRAINT_CONFLICT', `${pointer}/constraints`,
      'min 与 exclusiveMin 只能选择一个。', field);
  }
  if (inputs.max.enabled && inputs.exclusiveMax.enabled) {
    fieldProblem(diagnostics, 'CONSTRAINT_CONFLICT', `${pointer}/constraints`,
      'max 与 exclusiveMax 只能选择一个。', field);
  }
  const lower = parsed.min ?? parsed.exclusiveMin;
  const upper = parsed.max ?? parsed.exclusiveMax;
  if (lower !== undefined && upper !== undefined) {
    const comparison = compareValue(lower, upper);
    if (comparison > 0 || (comparison === 0 && (parsed.exclusiveMin !== undefined || parsed.exclusiveMax !== undefined))) {
      fieldProblem(diagnostics, 'CONSTRAINT_RANGE_INVALID', `${pointer}/constraints`,
        '约束范围不能为空；相等上下界只有均为 inclusive 时才允许。', field);
    }
  }
}

function parseIntegerInput(
  input: OptionalInput,
  maximum: number,
  pointer: string,
  field: EditorField,
  diagnostics: EditorDiagnostic[],
): number | undefined {
  if (!input.enabled) return undefined;
  if (!/^\d+$/.test(input.value.trim())) {
    fieldProblem(diagnostics, 'CONSTRAINT_VALUE_INVALID', pointer,
      `请输入 0–${maximum} 的整数。`, field);
    return undefined;
  }
  const value = Number(input.value.trim());
  if (!Number.isSafeInteger(value) || value < 0 || value > maximum) {
    fieldProblem(diagnostics, 'CONSTRAINT_VALUE_INVALID', pointer,
      `请输入 0–${maximum} 的整数。`, field);
    return undefined;
  }
  return value;
}

function parseDecimalInput(
  input: OptionalInput,
  pointer: string,
  field: EditorField,
  diagnostics: EditorDiagnostic[],
): ExactDecimal | undefined {
  if (!input.enabled) return undefined;
  const parsed = parseExactDecimal(input.value);
  if (!parsed) {
    fieldProblem(diagnostics, 'CONSTRAINT_VALUE_INVALID', pointer,
      '请输入合法 JSON number；token≤256 bytes、precision≤128、normalized scale 为 -64..64。', field);
  }
  return parsed;
}

function parseOrderedInput(
  input: OptionalInput,
  pointer: string,
  parser: (value: string) => number | undefined,
  type: 'date' | 'time',
  field: EditorField,
  diagnostics: EditorDiagnostic[],
): number | undefined {
  if (!input.enabled) return undefined;
  const parsed = parser(input.value);
  if (parsed === undefined) {
    fieldProblem(diagnostics, 'CONSTRAINT_VALUE_INVALID', pointer,
      type === 'date' ? '日期必须是有效的 YYYY-MM-DD。' : '时间必须是有效的 HH:mm:ss。', field);
  }
  return parsed;
}

interface ExactDecimal {
  coefficient: bigint;
  scale: number;
  precision: number;
}

export function parseExactDecimal(raw: string): ExactDecimal | undefined {
  const token = raw.trim();
  if (new TextEncoder().encode(token).length > 256 || !DECIMAL_TOKEN.test(token)) return undefined;
  const match = /^(-?)(\d+)(?:\.(\d+))?(?:[eE]([+-]?\d+))?$/.exec(token);
  if (!match) return undefined;
  const negative = match[1] === '-';
  const integer = match[2] ?? '';
  const fraction = match[3] ?? '';
  const exponent = Number(match[4] ?? 0);
  if (!Number.isSafeInteger(exponent)) return undefined;
  let digits = `${integer}${fraction}`.replace(/^0+/, '') || '0';
  let scale = fraction.length - exponent;
  while (digits.length > 1 && digits.endsWith('0')) {
    digits = digits.slice(0, -1);
    scale -= 1;
  }
  if (digits === '0') scale = 0;
  if (digits.length > 128 || scale < -64 || scale > 64) return undefined;
  const coefficient = BigInt(digits) * (negative && digits !== '0' ? -1n : 1n);
  return { coefficient, scale, precision: digits.length };
}

function compareDecimal(left: ExactDecimal, right: ExactDecimal): number {
  const scale = Math.max(left.scale, right.scale);
  const leftValue = left.coefficient * 10n ** BigInt(scale - left.scale);
  const rightValue = right.coefficient * 10n ** BigInt(scale - right.scale);
  return leftValue < rightValue ? -1 : leftValue > rightValue ? 1 : 0;
}

function decimalEqual(left: string, right: string): boolean {
  const parsedLeft = parseExactDecimal(left);
  const parsedRight = parseExactDecimal(right);
  return parsedLeft !== undefined && parsedRight !== undefined && compareDecimal(parsedLeft, parsedRight) === 0;
}

function decimalMultipleOf(value: ExactDecimal, multiple: ExactDecimal): boolean {
  if (multiple.coefficient <= 0n) return false;
  const scale = Math.max(value.scale, multiple.scale);
  const valueInteger = value.coefficient * 10n ** BigInt(scale - value.scale);
  const multipleInteger = multiple.coefficient * 10n ** BigInt(scale - multiple.scale);
  return valueInteger % multipleInteger === 0n;
}

function decimalSatisfies(
  value: ExactDecimal,
  constraints: {
    min?: ExactDecimal; exclusiveMin?: ExactDecimal; max?: ExactDecimal;
    exclusiveMax?: ExactDecimal; multipleOf?: ExactDecimal;
  },
): boolean {
  return !(constraints.min && compareDecimal(value, constraints.min) < 0)
    && !(constraints.exclusiveMin && compareDecimal(value, constraints.exclusiveMin) <= 0)
    && !(constraints.max && compareDecimal(value, constraints.max) > 0)
    && !(constraints.exclusiveMax && compareDecimal(value, constraints.exclusiveMax) >= 0)
    && !(constraints.multipleOf && !decimalMultipleOf(value, constraints.multipleOf));
}

function orderedSatisfies(
  value: number,
  constraints: { min?: number; exclusiveMin?: number; max?: number; exclusiveMax?: number },
): boolean {
  return !(constraints.min !== undefined && value < constraints.min)
    && !(constraints.exclusiveMin !== undefined && value <= constraints.exclusiveMin)
    && !(constraints.max !== undefined && value > constraints.max)
    && !(constraints.exclusiveMax !== undefined && value >= constraints.exclusiveMax);
}

function compareValue(left: Comparable, right: Comparable): number {
  if (typeof left === 'number' && typeof right === 'number') return left - right;
  return compareDecimal(left as ExactDecimal, right as ExactDecimal);
}

function parseDate(value: string): number | undefined {
  const match = DATE_TOKEN.exec(value);
  if (!match) return undefined;
  const year = Number(match[1]);
  const month = Number(match[2]);
  const day = Number(match[3]);
  if (year < 1 || year > 9_999 || month < 1 || month > 12) return undefined;
  const days = [31, leapYear(year) ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];
  if (day < 1 || day > (days[month - 1] ?? 0)) return undefined;
  return year * 10_000 + month * 100 + day;
}

function parseTime(value: string): number | undefined {
  const match = TIME_TOKEN.exec(value);
  if (!match) return undefined;
  const hour = Number(match[1]);
  const minute = Number(match[2]);
  const second = Number(match[3]);
  return hour <= 23 && minute <= 59 && second <= 59
    ? hour * 3_600 + minute * 60 + second
    : undefined;
}

function leapYear(year: number): boolean {
  return year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0);
}

function unsafePattern(pattern: string): boolean {
  return /\\[1-9]/.test(pattern)
    || /\\k</.test(pattern)
    || /\(\?(?:[=!<]|[a-zA-Z-]+:)/.test(pattern)
    || /\([^)]*[+*][^)]*\)[+*{]/.test(pattern);
}

function invalidTrimmedLength(value: string, maximum: number): boolean {
  const length = codePointLength(value.trim());
  return length < 1 || length > maximum;
}

function codePointLength(value: string): number {
  return [...value].length;
}

function fieldProblem(
  diagnostics: EditorDiagnostic[],
  code: string,
  pointer: string,
  message: string,
  field: EditorField,
) {
  add(diagnostics, code, pointer, message, field.rowKey);
}

function add(
  diagnostics: EditorDiagnostic[],
  code: string,
  pointer: string,
  message: string,
  rowKey?: string,
) {
  diagnostics.push({ code, pointer, message, ...(rowKey ? { rowKey } : {}) });
}
