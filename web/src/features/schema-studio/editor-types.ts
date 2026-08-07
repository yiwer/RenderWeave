import { LosslessNumber, stringify } from 'lossless-json';

export type EditorView = 'form' | 'map';
export type EditorValueType = 'text' | 'decimal' | 'date' | 'time' | 'boolean' | 'reference' | 'array';
export type EditorScalarType = Exclude<EditorValueType, 'array'>;

export interface OptionalInput {
  enabled: boolean;
  value: string;
}

export interface EnumInput {
  enabled: boolean;
  values: string[];
}

export interface TextEditorValue {
  type: 'text';
  minLength: OptionalInput;
  maxLength: OptionalInput;
  pattern: OptionalInput;
  enumValues: EnumInput;
  constValue: OptionalInput;
}

export interface DecimalEditorValue {
  type: 'decimal';
  min: OptionalInput;
  exclusiveMin: OptionalInput;
  max: OptionalInput;
  exclusiveMax: OptionalInput;
  multipleOf: OptionalInput;
  enumValues: EnumInput;
  constValue: OptionalInput;
}

export interface OrderedEditorValue {
  type: 'date' | 'time';
  min: OptionalInput;
  exclusiveMin: OptionalInput;
  max: OptionalInput;
  exclusiveMax: OptionalInput;
  enumValues: EnumInput;
  constValue: OptionalInput;
}

export interface BooleanEditorValue {
  type: 'boolean';
  constValue: OptionalInput;
}

export interface ReferenceEditorValue {
  type: 'reference';
  referenceKind: 'draft' | 'static';
  schemaKey: string;
  versionTag: string;
}

export type EditorScalarValue =
  | TextEditorValue
  | DecimalEditorValue
  | OrderedEditorValue
  | BooleanEditorValue
  | ReferenceEditorValue;

export interface ArrayEditorValue {
  type: 'array';
  minItems: OptionalInput;
  maxItems: OptionalInput;
  uniqueItems: boolean;
  items: EditorScalarValue;
}

export type EditorValue = EditorScalarValue | ArrayEditorValue;

export interface EditorField {
  /** In-memory occurrence identity only. Never serialized into the DSL. */
  rowKey: string;
  fieldKey: string;
  displayName: string;
  description: string;
  required: boolean;
  value: EditorValue;
}

export interface PersistedDefinition {
  dslVersion: 'renderweave-schema/1.0';
  displayName: string;
  description?: string;
  fields: PersistedField[];
}

export interface PersistedField {
  fieldKey: string;
  displayName?: string;
  description?: string;
  required: boolean;
  value: PersistedValue;
}

export interface PersistedTextValue {
  type: 'text';
  constraints?: {
    minLength?: number;
    maxLength?: number;
    pattern?: string;
    enum?: string[];
    const?: string;
  };
}

export interface PersistedDecimalValue {
  type: 'decimal';
  constraints?: {
    min?: string;
    exclusiveMin?: string;
    max?: string;
    exclusiveMax?: string;
    multipleOf?: string;
    enum?: string[];
    const?: string;
  };
}

export interface PersistedOrderedValue {
  type: 'date' | 'time';
  constraints?: {
    min?: string;
    exclusiveMin?: string;
    max?: string;
    exclusiveMax?: string;
    enum?: string[];
    const?: string;
  };
}

export interface PersistedBooleanValue {
  type: 'boolean';
  constraints?: { const?: boolean };
}

export interface PersistedReferenceValue {
  type: 'reference';
  ref: { schemaKey: string; versionTag?: string };
}

export interface PersistedArrayValue {
  type: 'array';
  constraints?: { minItems?: number; maxItems?: number; uniqueItems?: boolean };
  items: PersistedScalarValue;
}

export type PersistedScalarValue =
  | PersistedTextValue
  | PersistedDecimalValue
  | PersistedOrderedValue
  | PersistedBooleanValue
  | PersistedReferenceValue;
export type PersistedValue = PersistedScalarValue | PersistedArrayValue;

export interface DraftSnapshot {
  schemaKey: string;
  revision: number;
  definition: PersistedDefinition;
  creationSource: string;
  createdAt: string;
  updatedAt: string;
  savedAt: string;
  resolvedRevisions: Record<string, number>;
}

export const editorTypeLabels: Record<EditorValueType, string> = {
  text: '文本',
  decimal: '数值',
  date: '日期',
  time: '时间',
  boolean: '布尔',
  reference: '引用',
  array: '数组',
};

export function optionalInput(value = '', enabled = false): OptionalInput {
  return { enabled, value };
}

export function enumInput(values: string[] = [], enabled = false): EnumInput {
  return { enabled, values: [...values] };
}

export function createEditorValue(type: EditorValueType): EditorValue {
  if (type === 'array') {
    return {
      type: 'array',
      minItems: optionalInput(),
      maxItems: optionalInput(),
      uniqueItems: false,
      items: createEditorScalarValue('text'),
    };
  }
  return createEditorScalarValue(type);
}

export function createEditorScalarValue(type: EditorScalarType): EditorScalarValue {
  switch (type) {
    case 'text':
      return {
        type,
        minLength: optionalInput(),
        maxLength: optionalInput(),
        pattern: optionalInput(),
        enumValues: enumInput(),
        constValue: optionalInput(),
      };
    case 'decimal':
      return {
        type,
        min: optionalInput(),
        exclusiveMin: optionalInput(),
        max: optionalInput(),
        exclusiveMax: optionalInput(),
        multipleOf: optionalInput(),
        enumValues: enumInput(),
        constValue: optionalInput(),
      };
    case 'date':
    case 'time':
      return {
        type,
        min: optionalInput(),
        exclusiveMin: optionalInput(),
        max: optionalInput(),
        exclusiveMax: optionalInput(),
        enumValues: enumInput(),
        constValue: optionalInput(),
      };
    case 'boolean':
      return { type, constValue: optionalInput('true') };
    case 'reference':
      return { type, referenceKind: 'draft', schemaKey: '', versionTag: '' };
  }
}

export function editorValueFromPersisted(value: PersistedValue): EditorValue {
  if (value.type === 'array') {
    return {
      type: 'array',
      minItems: fromOptional(value.constraints?.minItems),
      maxItems: fromOptional(value.constraints?.maxItems),
      uniqueItems: value.constraints?.uniqueItems ?? false,
      items: editorScalarFromPersisted(value.items),
    };
  }
  return editorScalarFromPersisted(value);
}

function editorScalarFromPersisted(value: PersistedScalarValue): EditorScalarValue {
  switch (value.type) {
    case 'text':
      return {
        type: 'text',
        minLength: fromOptional(value.constraints?.minLength),
        maxLength: fromOptional(value.constraints?.maxLength),
        pattern: fromOptional(value.constraints?.pattern),
        enumValues: fromEnum(value.constraints?.enum),
        constValue: fromOptional(value.constraints?.const),
      };
    case 'decimal':
      return {
        type: 'decimal',
        min: fromOptional(value.constraints?.min),
        exclusiveMin: fromOptional(value.constraints?.exclusiveMin),
        max: fromOptional(value.constraints?.max),
        exclusiveMax: fromOptional(value.constraints?.exclusiveMax),
        multipleOf: fromOptional(value.constraints?.multipleOf),
        enumValues: fromEnum(value.constraints?.enum),
        constValue: fromOptional(value.constraints?.const),
      };
    case 'date':
    case 'time':
      return {
        type: value.type,
        min: fromOptional(value.constraints?.min),
        exclusiveMin: fromOptional(value.constraints?.exclusiveMin),
        max: fromOptional(value.constraints?.max),
        exclusiveMax: fromOptional(value.constraints?.exclusiveMax),
        enumValues: fromEnum(value.constraints?.enum),
        constValue: fromOptional(value.constraints?.const),
      };
    case 'boolean':
      return {
        type: 'boolean',
        constValue: value.constraints && 'const' in value.constraints
          ? optionalInput(String(value.constraints.const), true)
          : optionalInput('true'),
      };
    case 'reference':
      return {
        type: 'reference',
        referenceKind: value.ref.versionTag === undefined ? 'draft' : 'static',
        schemaKey: value.ref.schemaKey,
        versionTag: value.ref.versionTag ?? '',
      };
  }
}

function fromOptional(value: string | number | undefined): OptionalInput {
  return value === undefined ? optionalInput() : optionalInput(String(value), true);
}

function fromEnum(values: string[] | undefined): EnumInput {
  return values === undefined ? enumInput() : enumInput(values, true);
}

/** Builds a value that lossless-json serializes with decimal lexemes as JSON numbers. */
export function definitionJsonValue(
  displayName: string,
  description: string,
  fields: EditorField[],
): unknown {
  const normalizedDescription = description.trim();
  return {
    dslVersion: 'renderweave-schema/1.0',
    displayName: displayName.trim(),
    ...(normalizedDescription ? { description: normalizedDescription } : {}),
    fields: fields.map((field) => {
      const normalizedName = field.displayName.trim();
      const normalizedFieldDescription = field.description.trim();
      return {
        fieldKey: field.fieldKey,
        ...(normalizedName ? { displayName: normalizedName } : {}),
        ...(normalizedFieldDescription ? { description: normalizedFieldDescription } : {}),
        required: field.required,
        value: valueJson(field.value),
      };
    }),
  };
}

export function serializeDefinition(
  displayName: string,
  description: string,
  fields: EditorField[],
  pretty = false,
): string {
  const serialized = stringify(
    definitionJsonValue(displayName, description, fields),
    null,
    pretty ? 2 : undefined,
  );
  if (serialized === undefined) throw new Error('Definition cannot be serialized');
  return serialized;
}

function valueJson(value: EditorValue): unknown {
  if (value.type === 'array') {
    const constraints = compact({
      minItems: enabledInteger(value.minItems),
      maxItems: enabledInteger(value.maxItems),
      uniqueItems: value.uniqueItems ? true : undefined,
    });
    return {
      type: 'array',
      ...(constraints ? { constraints } : {}),
      items: scalarValueJson(value.items),
    };
  }
  return scalarValueJson(value);
}

function scalarValueJson(value: EditorScalarValue): unknown {
  switch (value.type) {
    case 'text': {
      const constraints = compact({
        minLength: enabledInteger(value.minLength),
        maxLength: enabledInteger(value.maxLength),
        pattern: enabledString(value.pattern),
        enum: value.enumValues.enabled ? [...value.enumValues.values] : undefined,
        const: enabledString(value.constValue),
      });
      return { type: 'text', ...(constraints ? { constraints } : {}) };
    }
    case 'decimal': {
      const constraints = compact({
        min: enabledDecimal(value.min),
        exclusiveMin: enabledDecimal(value.exclusiveMin),
        max: enabledDecimal(value.max),
        exclusiveMax: enabledDecimal(value.exclusiveMax),
        multipleOf: enabledDecimal(value.multipleOf),
        enum: value.enumValues.enabled
          ? value.enumValues.values.map((entry) => new LosslessNumber(entry.trim()))
          : undefined,
        const: enabledDecimal(value.constValue),
      });
      return { type: 'decimal', ...(constraints ? { constraints } : {}) };
    }
    case 'date':
    case 'time': {
      const constraints = compact({
        min: enabledString(value.min),
        exclusiveMin: enabledString(value.exclusiveMin),
        max: enabledString(value.max),
        exclusiveMax: enabledString(value.exclusiveMax),
        enum: value.enumValues.enabled ? [...value.enumValues.values] : undefined,
        const: enabledString(value.constValue),
      });
      return { type: value.type, ...(constraints ? { constraints } : {}) };
    }
    case 'boolean': {
      const constraints = value.constValue.enabled
        ? { const: value.constValue.value === 'true' }
        : undefined;
      return { type: 'boolean', ...(constraints ? { constraints } : {}) };
    }
    case 'reference':
      return {
        type: 'reference',
        ref: {
          schemaKey: value.schemaKey,
          ...(value.referenceKind === 'static' ? { versionTag: value.versionTag } : {}),
        },
      };
  }
}

function enabledInteger(input: OptionalInput): number | undefined {
  return input.enabled ? Number(input.value.trim()) : undefined;
}

function enabledString(input: OptionalInput): string | undefined {
  return input.enabled ? input.value : undefined;
}

function enabledDecimal(input: OptionalInput): LosslessNumber | undefined {
  return input.enabled ? new LosslessNumber(input.value.trim()) : undefined;
}

function compact(value: Record<string, unknown>): Record<string, unknown> | undefined {
  const entries = Object.entries(value).filter(([, entry]) => entry !== undefined);
  return entries.length > 0 ? Object.fromEntries(entries) : undefined;
}

export function cloneEditorFields(fields: EditorField[]): EditorField[] {
  return JSON.parse(JSON.stringify(fields)) as EditorField[];
}

export function summarizeEditorValue(value: EditorValue): string {
  switch (value.type) {
    case 'text':
      return summarizeParts([
        value.minLength.enabled ? `minLength ${value.minLength.value}` : '',
        value.maxLength.enabled ? `maxLength ${value.maxLength.value}` : '',
        value.pattern.enabled ? 'pattern' : '',
        value.enumValues.enabled ? `enum ${value.enumValues.values.length}` : '',
        value.constValue.enabled ? 'const' : '',
      ]);
    case 'decimal':
      return summarizeParts([
        value.min.enabled ? `min ${value.min.value}` : '',
        value.exclusiveMin.enabled ? `> ${value.exclusiveMin.value}` : '',
        value.max.enabled ? `max ${value.max.value}` : '',
        value.exclusiveMax.enabled ? `< ${value.exclusiveMax.value}` : '',
        value.multipleOf.enabled ? `multipleOf ${value.multipleOf.value}` : '',
        value.enumValues.enabled ? `enum ${value.enumValues.values.length}` : '',
        value.constValue.enabled ? `const ${value.constValue.value}` : '',
      ]);
    case 'date':
    case 'time':
      return summarizeParts([
        value.min.enabled ? `min ${value.min.value}` : '',
        value.exclusiveMin.enabled ? `> ${value.exclusiveMin.value}` : '',
        value.max.enabled ? `max ${value.max.value}` : '',
        value.exclusiveMax.enabled ? `< ${value.exclusiveMax.value}` : '',
        value.enumValues.enabled ? `enum ${value.enumValues.values.length}` : '',
        value.constValue.enabled ? `const ${value.constValue.value}` : '',
      ], value.type === 'date' ? 'YYYY-MM-DD' : 'HH:mm:ss');
    case 'boolean':
      return value.constValue.enabled ? `const ${value.constValue.value}` : '未设置约束';
    case 'reference':
      return value.referenceKind === 'static'
        ? `${value.schemaKey || '未选择'}@${value.versionTag || '未填写版本'}`
        : `${value.schemaKey || '未选择'} · Draft live`;
    case 'array':
      return summarizeParts([
        `Array[${editorTypeLabels[value.items.type]}]`,
        value.minItems.enabled ? `minItems ${value.minItems.value}` : '',
        value.maxItems.enabled ? `maxItems ${value.maxItems.value}` : '',
        value.uniqueItems ? 'uniqueItems' : '',
      ]);
  }
}

function summarizeParts(parts: string[], fallback = '未设置约束'): string {
  const present = parts.filter(Boolean);
  return present.length > 0 ? present.join(' · ') : fallback;
}
