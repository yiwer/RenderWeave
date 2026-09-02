import * as Dialog from '@radix-ui/react-dialog';
import { Braces, Database, Eye, PencilLine, Plus, X } from 'lucide-react';
import { LosslessNumber, stringify as losslessStringify } from 'lossless-json';
import { useEffect, useMemo, useRef, useState } from 'react';

import type { StaticSnapshot } from '../schema-studio/lossless-api';
import {
  projectTemplateStaticSchema,
  type TemplateDataAuthoringContext,
  type TemplateDataAuthoringIntent,
} from './template-editor-data-authoring';
import {
  defaultTemplateStaticSchemaTransport,
  loadExactTemplateStaticSchema,
  loadTemplateStaticSchemaBranches,
  type TemplateStaticSchemaTransport,
} from './template-editor-static-schema';
import { normalizeTemplateEditorDisplayName } from './template-editor-display-name';
import {
  canonicalTemplateDecimal,
  TemplateCanonicalDecimalError,
} from './template-canonical-decimal';

export type TemplateStaticSchemaView =
  | { readonly state: 'loading' }
  | { readonly state: 'ready'; readonly snapshot: StaticSnapshot }
  | { readonly state: 'error'; readonly message: string };

export interface TemplateEditorDataSourcesProps {
  readonly designDsl: Readonly<Record<string, unknown>>;
  readonly staticSchema: TemplateStaticSchemaView;
  readonly referenceTransport?: TemplateStaticSchemaTransport;
  readonly disabled: boolean;
  readonly onIntent: (
    intent: TemplateDataAuthoringIntent,
    context?: TemplateDataAuthoringContext,
  ) => boolean | void;
}

type DefinitionDialogState =
  | { readonly mode: 'create' }
  | { readonly mode: 'edit'; readonly definition: Readonly<Record<string, unknown>> }
  | { readonly mode: 'view'; readonly definition: Readonly<Record<string, unknown>> };

/**
 * Data authoring stays deliberately shallow: immutable StaticSchema is projected
 * read-only, while every mutable definition leaves through one semantic intent.
 */
export function TemplateEditorDataSources({
  designDsl,
  staticSchema,
  referenceTransport = defaultTemplateStaticSchemaTransport,
  disabled,
  onIntent,
}: TemplateEditorDataSourcesProps) {
  const definitions = definitionRecords(designDsl.definitions);
  const loopIds = repeatLoopIds(designDsl.designRoot);
  const [definitionDialog, setDefinitionDialog] = useState<DefinitionDialogState | null>(null);
  const definitionSubmitController = useRef<AbortController | null>(null);
  useEffect(() => () => definitionSubmitController.current?.abort(), []);
  return (
    <div className="te-data-sources">
      <header className="te-panel-heading" data-template-editor-location="definitions" tabIndex={-1}>
        <div>
          <span>DATA SOURCES</span>
          <h2>数据源</h2>
        </div>
        <small>{definitions.length} 个定义</small>
      </header>

      <section className="te-data-source-section" aria-labelledby="template-system-source-heading">
        <div className="te-data-source-section-heading">
          <div>
            <Database aria-hidden="true" size={15} />
            <h3 id="template-system-source-heading">系统数据源</h3>
          </div>
          <span>只读</span>
        </div>
        <SystemSchemaCard view={staticSchema} referenceTransport={referenceTransport} />
      </section>

      <section className="te-data-source-section" aria-labelledby="template-definition-source-heading">
        <div className="te-data-source-section-heading">
          <div>
            <Braces aria-hidden="true" size={15} />
            <h3 id="template-definition-source-heading">定义数据源</h3>
          </div>
          <button
            type="button"
            className="te-data-source-add"
            disabled={disabled}
            aria-label="新建定义数据源"
            onClick={() => setDefinitionDialog({ mode: 'create' })}
          >
            <Plus aria-hidden="true" size={14} />新建
          </button>
        </div>
        {definitions.length === 0 ? (
          <p className="te-empty-state">尚无定义数据源。</p>
        ) : (
          <div className="te-definition-groups">
            <DefinitionGroup
              label="自定义"
              definitions={definitions.filter((definition) => definition.kind === 'custom')}
              disabled={disabled}
              onOpen={setDefinitionDialog}
            />
            <DefinitionGroup
              label="映射"
              definitions={definitions.filter((definition) => definition.kind === 'mapping')}
              disabled={disabled}
              onOpen={setDefinitionDialog}
            />
            <DefinitionGroup
              label="表达式"
              definitions={definitions.filter((definition) => definition.kind === 'expression')}
              disabled={disabled}
              onOpen={setDefinitionDialog}
            />
          </div>
        )}
      </section>
      <DefinitionEditorDialog
        state={definitionDialog}
        loopIds={loopIds}
        definitions={definitions}
        onOpenChange={(open) => {
          if (!open) {
            definitionSubmitController.current?.abort();
            definitionSubmitController.current = null;
            setDefinitionDialog(null);
          }
        }}
        onSubmit={(intent) => {
          if (intent.definition.kind !== 'mapping') {
            if (onIntent(intent) !== false) setDefinitionDialog(null);
            return;
          }
          if (staticSchema.state !== 'ready') {
            return Promise.reject(new Error('系统数据源尚未就绪，无法验证映射来源。'));
          }
          definitionSubmitController.current?.abort();
          const controller = new AbortController();
          definitionSubmitController.current = controller;
          const branches = mappingSchemaBranchPointers(intent.definition, designDsl.designRoot);
          return loadTemplateStaticSchemaBranches(
            staticSchema.snapshot,
            branches,
            referenceTransport,
            controller.signal,
          ).then((closure) => {
            if (controller.signal.aborted) return;
            const referencedSchemas = closure.slice(1);
            const accepted = referencedSchemas.length === 0
              ? onIntent(intent)
              : onIntent(intent, { staticSchemas: referencedSchemas });
            if (accepted !== false) setDefinitionDialog(null);
          }).finally(() => {
            if (definitionSubmitController.current === controller) {
              definitionSubmitController.current = null;
            }
          });
        }}
      />
    </div>
  );
}

function DefinitionGroup({
  label,
  definitions,
  disabled,
  onOpen,
}: {
  label: '自定义' | '映射' | '表达式';
  definitions: readonly Readonly<Record<string, unknown>>[];
  disabled: boolean;
  onOpen: (state: DefinitionDialogState) => void;
}) {
  if (definitions.length === 0) return null;
  return (
    <section className="te-definition-group">
      <header><h4>{label}</h4><span>{definitions.length}</span></header>
      <ul className="te-data-source-cards" aria-label={`${label}定义`}>
        {definitions.map((definition, index) => {
          const editable = supportsDefinitionForm(definition);
          const displayName = stringMember(definition, 'displayName') ?? '未命名定义';
          return (
            <li
              key={stringMember(definition, 'definitionId') ?? index}
              data-template-definition-id={stringMember(definition, 'definitionId') ?? undefined}
            >
              <div>
                <strong>{displayName}</strong>
                <span>{definitionKindLabel(definition.kind)}</span>
              </div>
              <button
                type="button"
                disabled={editable && disabled}
                aria-label={`${editable ? '编辑' : '查看'}${displayName}`}
                onClick={() => onOpen({ mode: editable ? 'edit' : 'view', definition })}
              >
                {editable
                  ? <PencilLine aria-hidden="true" size={14} />
                  : <Eye aria-hidden="true" size={14} />}
                {editable ? '编辑' : '查看'}
              </button>
            </li>
          );
        })}
      </ul>
    </section>
  );
}

type DefinitionKind = 'custom' | 'mapping' | 'expression';
type BaseValueType = 'text' | 'decimal' | 'boolean' | 'date' | 'time' | 'color' | 'imageRef' | 'fontRef';
type DefinitionAuthoringIntent = Extract<
  TemplateDataAuthoringIntent,
  { readonly operation: 'create-definition' | 'update-definition' }
>;

interface DefinitionDraft {
  readonly kind: DefinitionKind;
  readonly displayName: string;
  readonly exposure: 'PUBLIC' | 'PRIVATE';
  readonly valueType: BaseValueType;
  readonly domain: string;
  readonly literal: string;
  readonly expression: string;
  readonly mapping: MappingDraft;
}

type NonCapabilityValueSourceDraft =
  | {
    readonly kind: 'literal';
    readonly valueType: BaseValueType;
    readonly literal: string;
  }
  | {
    readonly kind: 'context';
    readonly domain: string;
    readonly pointer: string;
  }
  | {
    readonly kind: 'loopIndex';
    readonly loopId: string;
  }
  | {
    readonly kind: 'definition';
    readonly definitionId: string;
  };

interface MappingOperandDraft {
  readonly valueType: BaseValueType;
  readonly literal: string;
}

interface MappingCaseDraft {
  readonly operator: MappingOperator;
  readonly operand: MappingOperandDraft | null;
  readonly then: NonCapabilityValueSourceDraft;
}

interface MappingDraft {
  readonly input: NonCapabilityValueSourceDraft;
  readonly cases: readonly MappingCaseDraft[];
  readonly otherwise: NonCapabilityValueSourceDraft;
}

type MappingOperator = typeof MAPPING_OPERATOR_OPTIONS[number]['value'];

const BASE_VALUE_TYPE_OPTIONS: ReadonlyArray<{ value: BaseValueType; label: string }> = [
  { value: 'text', label: '文本' },
  { value: 'decimal', label: '数值' },
  { value: 'boolean', label: '布尔' },
  { value: 'date', label: '日期' },
  { value: 'time', label: '时间' },
  { value: 'color', label: '颜色' },
  { value: 'imageRef', label: '图片 Asset' },
  { value: 'fontRef', label: '字体 Asset' },
];

const MAPPING_OPERATOR_OPTIONS = [
  { value: 'IS_ABSENT', label: '不存在' },
  { value: 'IS_PRESENT', label: '存在' },
  { value: 'EQ', label: '等于' },
  { value: 'NOT_EQ', label: '不等于' },
  { value: 'GT', label: '大于' },
  { value: 'GTE', label: '大于等于' },
  { value: 'LT', label: '小于' },
  { value: 'LTE', label: '小于等于' },
  { value: 'CONTAINS', label: '包含' },
  { value: 'STARTS_WITH', label: '开头是' },
  { value: 'ENDS_WITH', label: '结尾是' },
  { value: 'PATTERN_MATCH', label: '匹配模式' },
  { value: 'IS_BLANK', label: '为空白' },
  { value: 'IS_NOT_BLANK', label: '非空白' },
] as const;

const OPERATORS_WITHOUT_OPERAND = new Set<MappingOperator>(['IS_ABSENT', 'IS_PRESENT']);

function isBaseValueType(value: unknown): value is BaseValueType {
  return typeof value === 'string'
    && BASE_VALUE_TYPE_OPTIONS.some((option) => option.value === value);
}

function supportsDefinitionForm(definition: Readonly<Record<string, unknown>>): boolean {
  if (definition.kind === 'custom') return isBaseValueType(definition.valueType);
  if (definition.kind === 'mapping') {
    return isBaseValueType(definition.output) && mappingDraftFromDefinition(definition) !== null;
  }
  if (definition.kind === 'expression') return isBaseValueType(definition.output);
  return false;
}

function definitionValueTypeLabel(value: unknown): string {
  if (isBaseValueType(value)) {
    return BASE_VALUE_TYPE_OPTIONS.find((option) => option.value === value)?.label ?? value;
  }
  const record = objectMemberValue(value);
  if (record?.type === 'list') {
    const item = isBaseValueType(record.items)
      ? BASE_VALUE_TYPE_OPTIONS.find((option) => option.value === record.items)?.label
      : null;
    return `列表<${item ?? '未知'}>`;
  }
  if (record?.type === 'enum' && typeof record.catalogId === 'string') {
    return `枚举 · ${record.catalogId}`;
  }
  return '当前编辑器不支持的类型';
}

function authoredValueSummary(value: unknown): string {
  try {
    return losslessStringify(value) ?? '未提供';
  } catch {
    return '无法展示';
  }
}

function DefinitionEditorDialog({
  state,
  loopIds,
  definitions,
  onOpenChange,
  onSubmit,
}: {
  state: DefinitionDialogState | null;
  loopIds: readonly string[];
  definitions: readonly Readonly<Record<string, unknown>>[];
  onOpenChange: (open: boolean) => void;
  onSubmit: (intent: DefinitionAuthoringIntent) => void | Promise<void>;
}) {
  const existing = state?.mode === 'edit' || state?.mode === 'view'
    ? state.definition
    : undefined;
  const initial = useMemo(
    () => state?.mode === 'view' ? null : definitionDraft(existing),
    [existing, state?.mode],
  );
  return (
    <Dialog.Root open={state !== null} onOpenChange={onOpenChange}>
      {state ? (
        <Dialog.Portal>
          <Dialog.Overlay className="te-dialog-overlay" />
          <Dialog.Content className="te-dialog-content te-definition-dialog">
            {state.mode === 'view' ? (
              <DefinitionReadOnlyView
                definition={state.definition}
                onClose={() => onOpenChange(false)}
              />
            ) : initial ? (
              <DefinitionEditorForm
                key={`${state.mode}:${stringMember(existing ?? {}, 'definitionId') ?? 'new'}`}
                mode={state.mode}
                definitionId={stringMember(existing ?? {}, 'definitionId')}
                existing={existing}
                initial={initial}
                loopIds={loopIds}
                definitions={definitions}
                onCancel={() => onOpenChange(false)}
                onSubmit={onSubmit}
              />
            ) : null}
          </Dialog.Content>
        </Dialog.Portal>
      ) : null}
    </Dialog.Root>
  );
}

function DefinitionReadOnlyView({
  definition,
  onClose,
}: {
  definition: Readonly<Record<string, unknown>>;
  onClose: () => void;
}) {
  const displayName = stringMember(definition, 'displayName') ?? '未命名定义';
  const valueType = definition.kind === 'custom' ? definition.valueType : definition.output;
  return (
    <>
      <header>
        <div>
          <Dialog.Title>查看{displayName}</Dialog.Title>
          <Dialog.Description>当前类型仅支持只读查看；原 DesignDSL 保持不变。</Dialog.Description>
        </div>
        <Dialog.Close asChild>
          <button type="button" aria-label="关闭定义查看"><X aria-hidden="true" size={17} /></button>
        </Dialog.Close>
      </header>
      <dl className="te-node-inspector-facts">
        <div><dt>定义类型</dt><dd>{definitionKindLabel(definition.kind)}</dd></div>
        <div><dt>数据类型</dt><dd>{definitionValueTypeLabel(valueType)}</dd></div>
        {definition.kind === 'custom' ? (
          <div><dt>默认值</dt><dd><code>{authoredValueSummary(definition.defaultValue)}</code></dd></div>
        ) : null}
      </dl>
      <footer>
        <button type="button" onClick={onClose}>关闭</button>
      </footer>
    </>
  );
}

function DefinitionEditorForm({
  mode,
  definitionId,
  existing,
  initial,
  loopIds,
  definitions,
  onCancel,
  onSubmit,
}: {
  mode: 'create' | 'edit';
  definitionId: string | null;
  existing?: Readonly<Record<string, unknown>>;
  initial: DefinitionDraft;
  loopIds: readonly string[];
  definitions: readonly Readonly<Record<string, unknown>>[];
  onCancel: () => void;
  onSubmit: (intent: DefinitionAuthoringIntent) => void | Promise<void>;
}) {
  const [draft, setDraft] = useState(initial);
  const [problem, setProblem] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const title = mode === 'create' ? '新建定义数据源' : `编辑${initial.displayName || '定义数据源'}`;
  const update = <K extends keyof DefinitionDraft>(key: K, value: DefinitionDraft[K]) => {
    setDraft((current) => ({ ...current, [key]: value }));
    setProblem(null);
  };
  const updateMapping = (mapping: MappingDraft) => update('mapping', mapping);
  const updateValueType = (valueType: BaseValueType) => {
    setDraft((current) => ({
      ...current,
      valueType,
      mapping: current.kind === 'mapping' && mode === 'create'
        ? mappingWithOutput(current.mapping, valueType)
        : current.mapping,
    }));
    setProblem(null);
  };
  const submit = () => {
    const result = definitionFromDraft(draft, existing);
    if (result.state === 'invalid') {
      setProblem(result.message);
      return;
    }
    const intent: DefinitionAuthoringIntent = mode === 'create'
      ? { operation: 'create-definition', definition: result.definition }
      : {
        operation: 'update-definition',
        definitionId: definitionId ?? '',
        definition: result.definition,
      };
    try {
      const pending = onSubmit(intent);
      if (pending) {
        setSubmitting(true);
        void pending.catch((error: unknown) => {
          if (!isAbortError(error)) {
            setProblem(error instanceof Error
              ? error.message
              : '无法验证映射所需的系统数据源。');
          }
        }).finally(() => setSubmitting(false));
      }
    } catch (error) {
      setProblem(error instanceof Error ? error.message : '无法提交定义。');
    }
  };
  return (
    <>
      <header>
        <div>
          <Dialog.Title>{title}</Dialog.Title>
          <Dialog.Description>定义保存在当前 DesignDSL；保存 Template 后生效。</Dialog.Description>
        </div>
        <Dialog.Close asChild>
          <button type="button" aria-label="关闭定义编辑"><X aria-hidden="true" size={17} /></button>
        </Dialog.Close>
      </header>
      <div className="te-definition-form">
        <label>
          <span>定义类型</span>
          <select
            aria-label="定义类型"
            value={draft.kind}
            disabled={mode === 'edit'}
            onChange={(event) => update('kind', event.currentTarget.value as DefinitionKind)}
          >
            <option value="custom">定义数据</option>
            <option value="mapping">映射数据</option>
            <option value="expression">表达式数据</option>
          </select>
        </label>
        <label>
          <span>定义名称</span>
          <input
            aria-label="定义名称"
            value={draft.displayName}
            onChange={(event) => update('displayName', event.currentTarget.value)}
          />
        </label>
        {draft.kind === 'custom' ? (
          <label>
            <span>可见性</span>
            <select
              aria-label="可见性"
              value={draft.exposure}
              onChange={(event) => update('exposure', event.currentTarget.value as 'PUBLIC' | 'PRIVATE')}
            >
              <option value="PUBLIC">公开</option>
              <option value="PRIVATE">私有</option>
            </select>
          </label>
        ) : (
          <label>
            <span>定义域</span>
            <select
              aria-label="定义域"
              value={draft.domain}
              disabled={mode === 'edit'}
              onChange={(event) => update('domain', event.currentTarget.value)}
            >
              <option value="invocation">调用域</option>
              {mode === 'edit'
                && draft.domain.startsWith('loop:')
                && !loopIds.includes(draft.domain.slice('loop:'.length)) ? (
                  <option value={draft.domain}>
                    循环域 · {shortIdentity(draft.domain.slice('loop:'.length))}
                  </option>
                ) : null}
              {loopIds.map((loopId) => (
                <option key={loopId} value={`loop:${loopId}`}>循环域 · {shortIdentity(loopId)}</option>
              ))}
            </select>
          </label>
        )}
        <label>
          <span>{draft.kind === 'custom' ? '数据类型' : '输出类型'}</span>
          <select
            aria-label={draft.kind === 'custom' ? '数据类型' : '输出类型'}
            value={draft.valueType}
            disabled={draft.kind === 'mapping' && mode === 'edit'}
            onChange={(event) => updateValueType(event.currentTarget.value as BaseValueType)}
          >
            {BASE_VALUE_TYPE_OPTIONS.map((option) => (
              <option key={option.value} value={option.value}>{option.label}</option>
            ))}
          </select>
        </label>
        {draft.kind === 'expression' ? (
          <label>
            <span>表达式</span>
            <textarea aria-label="表达式" value={draft.expression} onChange={(event) => update('expression', event.currentTarget.value)} />
          </label>
        ) : null}
        {draft.kind === 'custom' ? (
          <DefinitionLiteralField
            label="默认值"
            valueType={draft.valueType}
            value={draft.literal}
            onChange={(value) => update('literal', value)}
          />
        ) : null}
        {draft.kind === 'mapping' ? (
          <MappingDefinitionFields
            draft={draft.mapping}
            output={draft.valueType}
            definitionDomain={draft.domain}
            loopIds={loopIds}
            definitions={definitions}
            currentDefinitionId={definitionId}
            onChange={updateMapping}
          />
        ) : null}
      </div>
      {problem ? <p className="te-definition-problem" role="alert">{problem}</p> : null}
      <footer>
        <button type="button" disabled={submitting} onClick={onCancel}>取消</button>
        <button type="button" disabled={submitting} className="is-primary" onClick={submit}>
          {mode === 'create' ? '创建定义' : '保存定义'}
        </button>
      </footer>
    </>
  );
}

function MappingDefinitionFields({
  draft,
  output,
  definitionDomain,
  loopIds,
  definitions,
  currentDefinitionId,
  onChange,
}: {
  draft: MappingDraft;
  output: BaseValueType;
  definitionDomain: string;
  loopIds: readonly string[];
  definitions: readonly Readonly<Record<string, unknown>>[];
  currentDefinitionId: string | null;
  onChange: (draft: MappingDraft) => void;
}) {
  const updateCase = (index: number, next: MappingCaseDraft) => {
    onChange({
      ...draft,
      cases: draft.cases.map((entry, entryIndex) => entryIndex === index ? next : entry),
    });
  };
  const moveCase = (index: number, offset: -1 | 1) => {
    const target = index + offset;
    if (target < 0 || target >= draft.cases.length) return;
    const cases = [...draft.cases];
    const moving = cases[index];
    const displaced = cases[target];
    if (!moving || !displaced) return;
    cases[index] = displaced;
    cases[target] = moving;
    onChange({ ...draft, cases });
  };
  const addCase = () => onChange({
    ...draft,
    cases: [...draft.cases, defaultMappingCase(output)],
  });
  const removeCase = (index: number) => {
    if (draft.cases.length <= 1) return;
    onChange({ ...draft, cases: draft.cases.filter((_, entryIndex) => entryIndex !== index) });
  };
  return (
    <section className="te-mapping-definition-fields" aria-label="映射规则">
      <ValueSourceFields
        label="映射输入"
        source={draft.input}
        definitionDomain={definitionDomain}
        loopIds={loopIds}
        definitions={definitions}
        currentDefinitionId={currentDefinitionId}
        onChange={(input) => onChange({ ...draft, input })}
      />
      <div className="te-mapping-cases" aria-label="有序映射分支">
        <header>
          <strong>分支</strong>
          <button type="button" onClick={addCase}>添加分支</button>
        </header>
        {draft.cases.map((mappingCase, index) => {
          const caseNumber = index + 1;
          return (
            <fieldset key={index} className="te-mapping-case">
              <legend>分支 {caseNumber}</legend>
              <label>
                <span>匹配方式</span>
                <select
                  aria-label={`分支 ${caseNumber} 匹配方式`}
                  value={mappingCase.operator}
                  onChange={(event) => {
                    const operator = event.currentTarget.value as MappingOperator;
                    updateCase(index, {
                      ...mappingCase,
                      operator,
                      operand: OPERATORS_WITHOUT_OPERAND.has(operator)
                        ? null
                        : mappingCase.operand ?? { valueType: 'text', literal: '' },
                    });
                  }}
                >
                  {MAPPING_OPERATOR_OPTIONS.map((option) => (
                    <option key={option.value} value={option.value}>{option.label}</option>
                  ))}
                </select>
              </label>
              {mappingCase.operand ? (
                <MappingOperandFields
                  label={`分支 ${caseNumber} 比较`}
                  operand={mappingCase.operand}
                  onChange={(operand) => updateCase(index, { ...mappingCase, operand })}
                />
              ) : null}
              <ValueSourceFields
                label={`分支 ${caseNumber} 结果`}
                source={mappingCase.then}
                requiredLiteralType={output}
                definitionDomain={definitionDomain}
                loopIds={loopIds}
                definitions={definitions}
                currentDefinitionId={currentDefinitionId}
                onChange={(then) => updateCase(index, { ...mappingCase, then })}
              />
              <div className="te-mapping-case-actions">
                <button
                  type="button"
                  aria-label={`上移分支 ${caseNumber}`}
                  disabled={index === 0}
                  onClick={() => moveCase(index, -1)}
                >上移</button>
                <button
                  type="button"
                  aria-label={`下移分支 ${caseNumber}`}
                  disabled={index === draft.cases.length - 1}
                  onClick={() => moveCase(index, 1)}
                >下移</button>
                <button
                  type="button"
                  aria-label={`删除分支 ${caseNumber}`}
                  disabled={draft.cases.length === 1}
                  onClick={() => removeCase(index)}
                >删除</button>
              </div>
            </fieldset>
          );
        })}
      </div>
      <ValueSourceFields
        label="缺省来源"
        source={draft.otherwise}
        requiredLiteralType={output}
        definitionDomain={definitionDomain}
        loopIds={loopIds}
        definitions={definitions}
        currentDefinitionId={currentDefinitionId}
        onChange={(otherwise) => onChange({ ...draft, otherwise })}
      />
    </section>
  );
}

function MappingOperandFields({
  label,
  operand,
  onChange,
}: {
  label: string;
  operand: MappingOperandDraft;
  onChange: (operand: MappingOperandDraft) => void;
}) {
  return (
    <fieldset className="te-value-source-fields">
      <legend>{label}</legend>
      <label>
        <span>类型</span>
        <select
          aria-label={`${label}类型`}
          value={operand.valueType}
          onChange={(event) => onChange({
            valueType: event.currentTarget.value as BaseValueType,
            literal: defaultLiteralDraft(event.currentTarget.value as BaseValueType),
          })}
        >
          {BASE_VALUE_TYPE_OPTIONS.map((option) => (
            <option key={option.value} value={option.value}>{option.label}</option>
          ))}
        </select>
      </label>
      <DefinitionLiteralField
        label={`${label}值`}
        valueType={operand.valueType}
        value={operand.literal}
        onChange={(literal) => onChange({ ...operand, literal })}
      />
    </fieldset>
  );
}

function ValueSourceFields({
  label,
  source,
  requiredLiteralType,
  definitionDomain,
  loopIds,
  definitions,
  currentDefinitionId,
  onChange,
}: {
  label: string;
  source: NonCapabilityValueSourceDraft;
  requiredLiteralType?: BaseValueType;
  definitionDomain: string;
  loopIds: readonly string[];
  definitions: readonly Readonly<Record<string, unknown>>[];
  currentDefinitionId: string | null;
  onChange: (source: NonCapabilityValueSourceDraft) => void;
}) {
  const definitionOptions = definitions.filter((definition) => (
    stringMember(definition, 'definitionId') !== currentDefinitionId
  ));
  const changeKind = (kind: NonCapabilityValueSourceDraft['kind']) => {
    onChange(defaultValueSourceDraft(kind, definitionDomain, requiredLiteralType ?? 'text', loopIds, definitionOptions));
  };
  return (
    <fieldset className="te-value-source-fields">
      <legend>{label}</legend>
      <label>
        <span>来源类型</span>
        <select
          aria-label={`${label}类型`}
          value={source.kind}
          onChange={(event) => changeKind(event.currentTarget.value as NonCapabilityValueSourceDraft['kind'])}
        >
          <option value="context">上下文属性</option>
          <option value="literal">固定值</option>
          {(loopIds.length > 0 || source.kind === 'loopIndex') ? <option value="loopIndex">循环序号</option> : null}
          {(definitionOptions.length > 0 || source.kind === 'definition') ? <option value="definition">定义</option> : null}
        </select>
      </label>
      {source.kind === 'context' ? (
        <>
          <DefinitionDomainField
            label={`${label}域`}
            value={source.domain}
            loopIds={loopIds}
            onChange={(domain) => onChange({ ...source, domain })}
          />
          <label>
            <span>属性指针</span>
            <input
              aria-label={`${label}指针`}
              value={source.pointer}
              onChange={(event) => onChange({ ...source, pointer: event.currentTarget.value })}
            />
          </label>
        </>
      ) : null}
      {source.kind === 'literal' ? (
        <>
          <label>
            <span>固定值类型</span>
            <select
              aria-label={`${label}值类型`}
              value={requiredLiteralType ?? source.valueType}
              disabled={requiredLiteralType !== undefined}
              onChange={(event) => {
                const valueType = event.currentTarget.value as BaseValueType;
                onChange({ kind: 'literal', valueType, literal: defaultLiteralDraft(valueType) });
              }}
            >
              {BASE_VALUE_TYPE_OPTIONS.map((option) => (
                <option key={option.value} value={option.value}>{option.label}</option>
              ))}
            </select>
          </label>
          <DefinitionLiteralField
            label={`${label}值`}
            valueType={requiredLiteralType ?? source.valueType}
            value={source.literal}
            onChange={(literal) => onChange({
              ...source,
              valueType: requiredLiteralType ?? source.valueType,
              literal,
            })}
          />
        </>
      ) : null}
      {source.kind === 'loopIndex' ? (
        <label>
          <span>循环</span>
          <select
            aria-label={`${label}循环`}
            value={source.loopId}
            onChange={(event) => onChange({ ...source, loopId: event.currentTarget.value })}
          >
            {!loopIds.includes(source.loopId) ? (
              <option value={source.loopId}>{shortIdentity(source.loopId)}</option>
            ) : null}
            {loopIds.map((loopId) => (
              <option key={loopId} value={loopId}>{shortIdentity(loopId)}</option>
            ))}
          </select>
        </label>
      ) : null}
      {source.kind === 'definition' ? (
        <label>
          <span>定义</span>
          <select
            aria-label={`${label}定义`}
            value={source.definitionId}
            onChange={(event) => onChange({ ...source, definitionId: event.currentTarget.value })}
          >
            {!definitionOptions.some((definition) => (
              stringMember(definition, 'definitionId') === source.definitionId
            )) ? <option value={source.definitionId}>{shortIdentity(source.definitionId)}</option> : null}
            {definitionOptions.map((definition) => {
              const definitionId = stringMember(definition, 'definitionId') ?? '';
              return (
                <option key={definitionId} value={definitionId}>
                  {stringMember(definition, 'displayName') ?? shortIdentity(definitionId)}
                </option>
              );
            })}
          </select>
        </label>
      ) : null}
    </fieldset>
  );
}

function DefinitionDomainField({
  label,
  value,
  loopIds,
  onChange,
}: {
  label: string;
  value: string;
  loopIds: readonly string[];
  onChange: (domain: string) => void;
}) {
  return (
    <label>
      <span>读取范围</span>
      <select aria-label={label} value={value} onChange={(event) => onChange(event.currentTarget.value)}>
        <option value="invocation">调用域</option>
        {value.startsWith('loop:') && !loopIds.includes(value.slice('loop:'.length)) ? (
          <option value={value}>循环域 · {shortIdentity(value.slice('loop:'.length))}</option>
        ) : null}
        {loopIds.map((loopId) => (
          <option key={loopId} value={`loop:${loopId}`}>循环域 · {shortIdentity(loopId)}</option>
        ))}
      </select>
    </label>
  );
}

function DefinitionLiteralField({
  label,
  valueType,
  value,
  onChange,
}: {
  label: string;
  valueType: BaseValueType;
  value: string;
  onChange: (value: string) => void;
}) {
  if (valueType === 'boolean') {
    return (
      <label>
        <span>{label}</span>
        <select aria-label={label} value={value || 'false'} onChange={(event) => onChange(event.currentTarget.value)}>
          <option value="false">否</option>
          <option value="true">是</option>
        </select>
      </label>
    );
  }
  return (
    <label>
      <span>{label}</span>
      <input
        aria-label={label}
        value={value}
        placeholder={valueType === 'date'
          ? 'YYYY-MM-DD'
          : valueType === 'time'
            ? 'HH:mm:ss'
            : valueType === 'color'
              ? '#RRGGBBAA'
              : valueType === 'imageRef' || valueType === 'fontRef'
                ? 'Asset UUID'
                : undefined}
        onChange={(event) => onChange(event.currentTarget.value)}
      />
    </label>
  );
}

function definitionDraft(existing?: Readonly<Record<string, unknown>>): DefinitionDraft {
  const kind = existing?.kind === 'mapping' || existing?.kind === 'expression'
    ? existing.kind : 'custom';
  const output = kind === 'custom' ? existing?.valueType : existing?.output;
  const valueType = typeof output === 'string'
    && BASE_VALUE_TYPE_OPTIONS.some((option) => option.value === output)
    ? output as BaseValueType : 'text';
  const domain = definitionDomainDraft(existing?.domain);
  return {
    kind,
    displayName: stringMember(existing ?? {}, 'displayName') ?? '',
    exposure: existing?.exposure === 'PRIVATE' ? 'PRIVATE' : 'PUBLIC',
    valueType,
    domain,
    literal: authoredLiteralDraft(definitionLiteral(existing, kind)),
    expression: stringMember(existing ?? {}, 'source') ?? '',
    mapping: kind === 'mapping'
      ? mappingDraftFromDefinition(existing ?? {}) ?? defaultMappingDraft(domain, valueType)
      : defaultMappingDraft(domain, valueType),
  };
}

function definitionFromDraft(
  draft: DefinitionDraft,
  existing?: Readonly<Record<string, unknown>>,
):
  | { state: 'valid'; definition: Record<string, unknown> }
  | { state: 'invalid'; message: string } {
  const normalizedDisplayName = normalizeTemplateEditorDisplayName(draft.displayName);
  if (normalizedDisplayName.state === 'invalid') {
    return { state: 'invalid', message: '定义名称必须是 1–128 个有效字符。' };
  }
  const displayName = normalizedDisplayName.value;
  if (draft.kind === 'expression') {
    if (!draft.expression.trim()) return { state: 'invalid', message: '请输入表达式。' };
    return {
      state: 'valid',
      definition: {
        kind: 'expression', displayName, domain: immutableDefinitionDomain(draft, existing),
        output: draft.valueType,
        inputs: existing?.kind === 'expression' && Array.isArray(existing.inputs)
          ? existing.inputs : [],
        source: draft.expression,
      },
    };
  }
  if (draft.kind === 'custom') {
    const literal = parseAuthoredLiteral(draft.valueType, draft.literal);
    if (literal.state === 'invalid') return literal;
    return {
      state: 'valid',
      definition: {
        kind: 'custom', displayName, exposure: draft.exposure,
        valueType: draft.valueType, defaultValue: literal.value,
      },
    };
  }
  const domain = immutableDefinitionDomain(draft, existing);
  const mapping = mappingFromDraft(draft.mapping, draft.valueType);
  if (mapping.state === 'invalid') return mapping;
  return {
    state: 'valid',
    definition: {
      kind: 'mapping', displayName, domain, output: draft.valueType,
      input: mapping.input,
      cases: mapping.cases,
      otherwise: mapping.otherwise,
    },
  };
}

function mappingDraftFromDefinition(
  definition: Readonly<Record<string, unknown>>,
): MappingDraft | null {
  if (definition.kind !== 'mapping' || !isBaseValueType(definition.output)
    || !hasExactMembers(definition, [
      'definitionId', 'kind', 'displayName', 'domain', 'output', 'input', 'cases', 'otherwise',
    ])
    || exactDefinitionDomainDraft(definition.domain) === null
    || !Array.isArray(definition.cases)
    || definition.cases.length === 0) return null;
  const input = valueSourceDraftFromValue(definition.input);
  const otherwise = valueSourceDraftFromValue(definition.otherwise, definition.output);
  const cases = definition.cases.map((mappingCase) => (
    mappingCaseDraftFromValue(mappingCase, definition.output as BaseValueType)
  ));
  if (!input || !otherwise || cases.some((entry) => entry === null)) return null;
  return { input, cases: cases as MappingCaseDraft[], otherwise };
}

function mappingCaseDraftFromValue(value: unknown, output: BaseValueType): MappingCaseDraft | null {
  const mappingCase = objectMemberValue(value);
  if (!mappingCase || !isMappingOperator(mappingCase.operator)) return null;
  const withoutOperand = OPERATORS_WITHOUT_OPERAND.has(mappingCase.operator);
  if (!hasExactMembers(mappingCase, withoutOperand
    ? ['operator', 'then']
    : ['operator', 'operand', 'then'])) return null;
  const then = valueSourceDraftFromValue(mappingCase.then, output);
  if (!then) return null;
  if (withoutOperand) return { operator: mappingCase.operator, operand: null, then };
  const operand = mappingOperandDraftFromValue(mappingCase.operand);
  return operand ? { operator: mappingCase.operator, operand, then } : null;
}

function mappingOperandDraftFromValue(value: unknown): MappingOperandDraft | null {
  const operand = objectMemberValue(value);
  if (!operand || !hasExactMembers(operand, ['valueType', 'value'])
    || !isBaseValueType(operand.valueType)) return null;
  const literal = editableLiteralDraft(operand.valueType, operand.value);
  return literal === null ? null : { valueType: operand.valueType, literal };
}

function valueSourceDraftFromValue(
  value: unknown,
  requiredLiteralType?: BaseValueType,
): NonCapabilityValueSourceDraft | null {
  const source = objectMemberValue(value);
  if (!source || typeof source.kind !== 'string') return null;
  if (source.kind === 'literal') {
    if (!hasExactMembers(source, ['kind', 'valueType', 'value'])
      || !isBaseValueType(source.valueType)
      || (requiredLiteralType !== undefined && source.valueType !== requiredLiteralType)) return null;
    const literal = editableLiteralDraft(source.valueType, source.value);
    return literal === null ? null : { kind: 'literal', valueType: source.valueType, literal };
  }
  if (source.kind === 'context') {
    const domain = exactDefinitionDomainDraft(source.domain);
    return hasExactMembers(source, ['kind', 'domain', 'pointer'])
      && domain !== null
      && typeof source.pointer === 'string'
      && validContextPointer(source.pointer)
      ? { kind: 'context', domain, pointer: source.pointer }
      : null;
  }
  if (source.kind === 'loopIndex') {
    return hasExactMembers(source, ['kind', 'loopId'])
      && typeof source.loopId === 'string'
      && UUID_V4.test(source.loopId)
      ? { kind: 'loopIndex', loopId: source.loopId }
      : null;
  }
  if (source.kind === 'definition') {
    return hasExactMembers(source, ['kind', 'definitionId'])
      && typeof source.definitionId === 'string'
      && UUID_V4.test(source.definitionId)
      ? { kind: 'definition', definitionId: source.definitionId }
      : null;
  }
  return null;
}

function mappingFromDraft(
  draft: MappingDraft,
  output: BaseValueType,
):
  | {
    state: 'valid';
    input: Record<string, unknown>;
    cases: Record<string, unknown>[];
    otherwise: Record<string, unknown>;
  }
  | { state: 'invalid'; message: string } {
  const input = valueSourceFromDraft(draft.input);
  if (input.state === 'invalid') return { state: 'invalid', message: `映射输入：${input.message}` };
  const otherwise = valueSourceFromDraft(draft.otherwise, output);
  if (otherwise.state === 'invalid') return { state: 'invalid', message: `缺省来源：${otherwise.message}` };
  if (draft.cases.length === 0) return { state: 'invalid', message: '映射至少需要一个分支。' };
  const cases: Record<string, unknown>[] = [];
  for (const [index, mappingCase] of draft.cases.entries()) {
    const then = valueSourceFromDraft(mappingCase.then, output);
    if (then.state === 'invalid') {
      return { state: 'invalid', message: `分支 ${index + 1} 结果：${then.message}` };
    }
    if (OPERATORS_WITHOUT_OPERAND.has(mappingCase.operator)) {
      cases.push({ operator: mappingCase.operator, then: then.source });
      continue;
    }
    if (!mappingCase.operand) {
      return { state: 'invalid', message: `分支 ${index + 1} 需要比较值。` };
    }
    const operand = parseAuthoredLiteral(mappingCase.operand.valueType, mappingCase.operand.literal);
    if (operand.state === 'invalid') {
      return { state: 'invalid', message: `分支 ${index + 1} 比较值：${operand.message}` };
    }
    cases.push({
      operator: mappingCase.operator,
      operand: { valueType: mappingCase.operand.valueType, value: operand.value },
      then: then.source,
    });
  }
  return { state: 'valid', input: input.source, cases, otherwise: otherwise.source };
}

function valueSourceFromDraft(
  draft: NonCapabilityValueSourceDraft,
  requiredLiteralType?: BaseValueType,
):
  | { state: 'valid'; source: Record<string, unknown> }
  | { state: 'invalid'; message: string } {
  if (draft.kind === 'literal') {
    const valueType = requiredLiteralType ?? draft.valueType;
    const literal = parseAuthoredLiteral(valueType, draft.literal);
    return literal.state === 'invalid'
      ? literal
      : { state: 'valid', source: { kind: 'literal', valueType, value: literal.value } };
  }
  if (draft.kind === 'context') {
    if (!validContextPointer(draft.pointer)) {
      return { state: 'invalid', message: '属性指针必须是非空 RFC 6901 pointer。' };
    }
    return {
      state: 'valid',
      source: { kind: 'context', domain: authoredDomain(draft.domain), pointer: draft.pointer },
    };
  }
  if (draft.kind === 'loopIndex') {
    return UUID_V4.test(draft.loopId)
      ? { state: 'valid', source: { kind: 'loopIndex', loopId: draft.loopId } }
      : { state: 'invalid', message: '请选择一个有效循环。' };
  }
  return UUID_V4.test(draft.definitionId)
    ? { state: 'valid', source: { kind: 'definition', definitionId: draft.definitionId } }
    : { state: 'invalid', message: '请选择一个有效定义。' };
}

function defaultMappingDraft(domain: string, output: BaseValueType): MappingDraft {
  return {
    input: { kind: 'context', domain, pointer: '/' },
    cases: [defaultMappingCase(output)],
    otherwise: { kind: 'context', domain, pointer: '/' },
  };
}

function defaultMappingCase(output: BaseValueType): MappingCaseDraft {
  return {
    operator: 'IS_ABSENT',
    operand: null,
    then: { kind: 'literal', valueType: output, literal: defaultLiteralDraft(output) },
  };
}

function defaultValueSourceDraft(
  kind: NonCapabilityValueSourceDraft['kind'],
  domain: string,
  literalType: BaseValueType,
  loopIds: readonly string[],
  definitions: readonly Readonly<Record<string, unknown>>[],
): NonCapabilityValueSourceDraft {
  switch (kind) {
    case 'literal':
      return { kind, valueType: literalType, literal: defaultLiteralDraft(literalType) };
    case 'context':
      return { kind, domain, pointer: '/' };
    case 'loopIndex':
      return { kind, loopId: loopIds[0] ?? '' };
    case 'definition':
      return { kind, definitionId: stringMember(definitions[0] ?? {}, 'definitionId') ?? '' };
  }
}

function mappingWithOutput(draft: MappingDraft, output: BaseValueType): MappingDraft {
  const adapt = (source: NonCapabilityValueSourceDraft): NonCapabilityValueSourceDraft => (
    source.kind === 'literal'
      ? { kind: 'literal', valueType: output, literal: defaultLiteralDraft(output) }
      : source
  );
  return {
    input: draft.input,
    cases: draft.cases.map((mappingCase) => ({ ...mappingCase, then: adapt(mappingCase.then) })),
    otherwise: adapt(draft.otherwise),
  };
}

function defaultLiteralDraft(valueType: BaseValueType): string {
  switch (valueType) {
    case 'decimal': return '0';
    case 'boolean': return 'false';
    case 'date': return '1970-01-01';
    case 'time': return '00:00:00';
    case 'color': return '#000000FF';
    case 'imageRef':
    case 'fontRef': return '00000000-0000-4000-8000-000000000000';
    case 'text': return '';
  }
}

function editableLiteralDraft(valueType: BaseValueType, value: unknown): string | null {
  if (valueType === 'decimal') {
    return typeof value === 'number' || value instanceof LosslessNumber ? String(value) : null;
  }
  if (valueType === 'boolean') return typeof value === 'boolean' ? String(value) : null;
  if (valueType === 'imageRef' || valueType === 'fontRef') {
    const asset = objectMemberValue(value);
    return asset && hasExactMembers(asset, ['assetId'])
      && typeof asset.assetId === 'string' && UUID_V4.test(asset.assetId)
      ? asset.assetId : null;
  }
  if (typeof value !== 'string') return null;
  const parsed = parseAuthoredLiteral(valueType, value);
  return parsed.state === 'valid' ? value : null;
}

function exactDefinitionDomainDraft(value: unknown): string | null {
  if (value === 'invocation') return 'invocation';
  const domain = objectMemberValue(value);
  return domain && hasExactMembers(domain, ['kind', 'loopId'])
    && domain.kind === 'loop'
    && typeof domain.loopId === 'string'
    && UUID_V4.test(domain.loopId)
    ? `loop:${domain.loopId}` : null;
}

function isMappingOperator(value: unknown): value is MappingOperator {
  return typeof value === 'string'
    && MAPPING_OPERATOR_OPTIONS.some((option) => option.value === value);
}

function hasExactMembers(
  value: Readonly<Record<string, unknown>>,
  members: readonly string[],
): boolean {
  const keys = Object.keys(value);
  return keys.length === members.length && members.every((member) => keys.includes(member));
}

function validContextPointer(value: string): boolean {
  return /^\/(?:[^~/]|~[01])+(?:\/(?:[^~/]|~[01])*)*$/.test(value);
}

function mappingSchemaBranchPointers(
  definition: Readonly<Record<string, unknown>>,
  designRoot: unknown,
): readonly string[] {
  const sources: unknown[] = [definition.input, definition.otherwise];
  if (Array.isArray(definition.cases)) {
    for (const entry of definition.cases) {
      const mappingCase = objectMemberValue(entry);
      if (mappingCase) sources.push(mappingCase.then);
    }
  }
  const pointers = new Set<string>();
  for (const value of sources) {
    const source = objectMemberValue(value);
    if (source?.kind !== 'context' || typeof source.pointer !== 'string') continue;
    if (source.domain === 'invocation') {
      pointers.add(source.pointer);
      continue;
    }
    const domain = objectMemberValue(source.domain);
    if (domain?.kind !== 'loop' || typeof domain.loopId !== 'string') continue;
    const prefix = loopInvocationPointer(designRoot, domain.loopId, new Set());
    if (prefix === null) continue;
    pointers.add(joinContextPointers(prefix, source.pointer));
  }
  return Object.freeze([...pointers]);
}

function loopInvocationPointer(
  designRoot: unknown,
  loopId: string,
  visited: Set<string>,
): string | null {
  if (visited.has(loopId)) return null;
  visited.add(loopId);
  const repeat = findRepeatByLoopId(designRoot, loopId);
  const items = objectMember(repeat, 'items');
  if (items?.kind !== 'context' || typeof items.pointer !== 'string') return null;
  if (items.domain === 'invocation') return items.pointer;
  const domain = objectMemberValue(items.domain);
  if (domain?.kind !== 'loop' || typeof domain.loopId !== 'string') return null;
  const parent = loopInvocationPointer(designRoot, domain.loopId, visited);
  return parent === null ? null : joinContextPointers(parent, items.pointer);
}

function findRepeatByLoopId(value: unknown, loopId: string): Record<string, unknown> | null {
  const node = objectMemberValue(value);
  if (!node) return null;
  if (node.kind === 'repeat' && node.loopId === loopId) return node;
  if (!Array.isArray(node.children)) return null;
  for (const child of node.children) {
    const found = findRepeatByLoopId(child, loopId);
    if (found) return found;
  }
  return null;
}

function joinContextPointers(prefix: string, suffix: string): string {
  if (prefix === '/') return suffix;
  return `${prefix}${suffix}`;
}

function isAbortError(error: unknown): boolean {
  return error instanceof DOMException && error.name === 'AbortError';
}

const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

function immutableDefinitionDomain(
  draft: DefinitionDraft,
  existing?: Readonly<Record<string, unknown>>,
): unknown {
  return existing?.kind === draft.kind
    && (draft.kind === 'mapping' || draft.kind === 'expression')
    ? existing.domain
    : authoredDomain(draft.domain);
}

function parseAuthoredLiteral(valueType: BaseValueType, draft: string):
  | { state: 'valid'; value: unknown }
  | { state: 'invalid'; message: string } {
  if (valueType === 'decimal') {
    const token = draft.trim();
    try {
      return { state: 'valid', value: new LosslessNumber(canonicalTemplateDecimal(token)) };
    } catch (error) {
      if (error instanceof TemplateCanonicalDecimalError) {
        return {
          state: 'invalid',
          message: error.code === 'NUMBER_TOKEN_TOO_LARGE'
            ? '数值文本不能超过 256 字节。'
            : error.code === 'CANONICAL_SIZE_EXCEEDED'
              ? '数值展开后超过文档容量限制。'
              : '请输入合法数值。',
        };
      }
      return { state: 'invalid', message: '请输入合法数值。' };
    }
  }
  if (valueType === 'boolean') return { state: 'valid', value: draft === 'true' };
  if (valueType === 'date' && !/^\d{4}-\d{2}-\d{2}$/.test(draft)) {
    return { state: 'invalid', message: '日期格式必须为 YYYY-MM-DD。' };
  }
  if (valueType === 'time' && !/^\d{2}:\d{2}:\d{2}$/.test(draft)) {
    return { state: 'invalid', message: '时间格式必须为 HH:mm:ss。' };
  }
  if (valueType === 'imageRef' || valueType === 'fontRef') {
    const assetId = draft.trim().toLowerCase();
    if (!/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(assetId)) {
      return { state: 'invalid', message: '请输入合法的 Asset UUID。' };
    }
    return { state: 'valid', value: { assetId } };
  }
  if (valueType === 'color' && !/^#[0-9A-F]{8}$/.test(draft)) {
    return { state: 'invalid', message: '颜色必须是大写 #RRGGBBAA。' };
  }
  return { state: 'valid', value: draft };
}

function definitionLiteral(existing: Readonly<Record<string, unknown>> | undefined, kind: DefinitionKind): unknown {
  if (!existing) return '';
  if (kind === 'custom') return existing.defaultValue;
  if (kind !== 'mapping' || !Array.isArray(existing.cases)) return '';
  const absentCase = existing.cases.find((entry) => objectMemberValue(entry)?.operator === 'IS_ABSENT');
  const then = objectMember(absentCase, 'then');
  return then?.kind === 'literal' ? then.value : '';
}

function authoredLiteralDraft(value: unknown): string {
  if (typeof value === 'string' || typeof value === 'boolean' || typeof value === 'number') return String(value);
  if (value instanceof LosslessNumber) return value.toString();
  const record = objectMemberValue(value);
  return typeof record?.assetId === 'string' ? record.assetId : '';
}

function objectMember(value: unknown, key: string): Record<string, unknown> | null {
  const record = objectMemberValue(value);
  return record && objectMemberValue(record[key]);
}

function objectMemberValue(value: unknown, key?: string): Record<string, unknown> | null {
  const candidate = key === undefined ? value : objectMemberValue(value)?.[key];
  return typeof candidate === 'object' && candidate !== null && !Array.isArray(candidate)
    ? candidate as Record<string, unknown> : null;
}

function definitionDomainDraft(value: unknown): string {
  if (value === 'invocation') return 'invocation';
  const domain = objectMemberValue(value);
  return domain?.kind === 'loop' && typeof domain.loopId === 'string'
    ? `loop:${domain.loopId}`
    : 'invocation';
}

function authoredDomain(value: string): 'invocation' | { kind: 'loop'; loopId: string } {
  return value.startsWith('loop:')
    ? { kind: 'loop', loopId: value.slice('loop:'.length) }
    : 'invocation';
}

function repeatLoopIds(value: unknown): readonly string[] {
  const ids: string[] = [];
  const visit = (candidate: unknown) => {
    const node = objectMemberValue(candidate);
    if (!node) return;
    if (node.kind === 'repeat' && typeof node.loopId === 'string') ids.push(node.loopId);
    if (Array.isArray(node.children)) node.children.forEach(visit);
  };
  visit(value);
  return ids;
}

function shortIdentity(value: string): string {
  return value.length <= 12 ? value : `${value.slice(0, 8)}…${value.slice(-4)}`;
}

function SystemSchemaCard({
  view,
  referenceTransport,
}: {
  view: TemplateStaticSchemaView;
  referenceTransport: TemplateStaticSchemaTransport;
}) {
  if (view.state === 'loading') {
    return <div className="te-data-source-state" role="status">正在读取永久 StaticSchema…</div>;
  }
  if (view.state === 'error') {
    return <div className="te-data-source-state is-error" role="alert">{view.message}</div>;
  }
  return <ReadySystemSchemaCard snapshot={view.snapshot} referenceTransport={referenceTransport} />;
}

function ReadySystemSchemaCard({
  snapshot,
  referenceTransport,
}: {
  snapshot: StaticSnapshot;
  referenceTransport: TemplateStaticSchemaTransport;
}) {
  const projection = useMemo(() => projectTemplateStaticSchema(snapshot), [snapshot]);
  return (
    <div className="te-system-source-catalog">
      <div className="te-system-source-identity">
        <span className="te-system-source-icon"><Database aria-hidden="true" size={17} /></span>
        <div>
          <strong>{projection.displayName}</strong>
          <span><code>{projection.identity.schemaKey}</code><small>{projection.identity.versionTag}</small></span>
        </div>
      </div>
      <ul className="te-data-source-cards" aria-label="系统字段">
        {projection.fields.map((field) => (
          <li key={field.fieldKey}>
            <SystemFieldCard
              field={field}
              schemaIdentity={projection.identity}
              referenceTransport={referenceTransport}
            />
          </li>
        ))}
      </ul>
    </div>
  );
}

type SystemFieldProjection = ReturnType<typeof projectTemplateStaticSchema>['fields'][number];

function SystemFieldCard({
  field,
  schemaIdentity,
  referenceTransport,
}: {
  field: SystemFieldProjection;
  schemaIdentity: { readonly schemaKey: string; readonly versionTag: string };
  referenceTransport: TemplateStaticSchemaTransport;
}) {
  return (
    <Dialog.Root>
      <article className="te-system-source-card">
        <div>
          <strong>{field.displayName}</strong>
          <code>{field.fieldKey}</code>
        </div>
        <span>{field.typeLabel}</span>
        {field.reference ? (
          <p><code>{field.reference.schemaKey}</code> · {field.reference.versionTag}</p>
        ) : null}
        <small>{field.constraintLabels.join(' · ')}</small>
        <Dialog.Trigger asChild>
          <button type="button" aria-label={`查看${field.displayName}`}>
            <Eye aria-hidden="true" size={14} />查看
          </button>
        </Dialog.Trigger>
      </article>
      <Dialog.Portal>
        <Dialog.Overlay className="te-dialog-overlay" />
        <Dialog.Content className="te-dialog-content te-data-source-dialog">
          <header>
            <div>
              <Dialog.Title>{field.displayName}详情</Dialog.Title>
              <Dialog.Description>
                {schemaIdentity.schemaKey} · {schemaIdentity.versionTag} · 只读
              </Dialog.Description>
            </div>
            <Dialog.Close asChild>
              <button type="button" aria-label="关闭系统数据源详情"><X aria-hidden="true" size={17} /></button>
            </Dialog.Close>
          </header>
          <dl className="te-node-inspector-facts">
            <div><dt>名称</dt><dd>{field.displayName}</dd></div>
            <div><dt>Key</dt><dd><code>{field.fieldKey}</code></dd></div>
            <div><dt>路径</dt><dd><code>/{escapeJsonPointerSegment(field.fieldKey)}</code></dd></div>
            <div><dt>类型</dt><dd>{field.typeLabel}</dd></div>
            <div><dt>约束</dt><dd>{field.constraintLabels.join(' · ')}</dd></div>
          </dl>
          {field.reference ? (
            <ReferenceSchemaDetail identity={field.reference} transport={referenceTransport} />
          ) : null}
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}

function escapeJsonPointerSegment(value: string): string {
  return value.replaceAll('~', '~0').replaceAll('/', '~1');
}

type ReferenceDetailState =
  | { readonly state: 'loading' }
  | { readonly state: 'ready'; readonly snapshot: StaticSnapshot }
  | { readonly state: 'error'; readonly message: string };

function ReferenceSchemaDetail({
  identity,
  transport,
}: {
  identity: { readonly schemaKey: string; readonly versionTag: string };
  transport: TemplateStaticSchemaTransport;
}) {
  const [expanded, setExpanded] = useState(false);
  const [detail, setDetail] = useState<ReferenceDetailState>({ state: 'loading' });
  const request = useRef<AbortController | null>(null);
  useEffect(() => () => request.current?.abort(), []);
  const toggle = () => {
    if (expanded) {
      request.current?.abort();
      request.current = null;
      setExpanded(false);
      return;
    }
    request.current = new AbortController();
    const controller = request.current;
    setExpanded(true);
    setDetail({ state: 'loading' });
    void loadExactTemplateStaticSchema(identity, transport, controller.signal)
      .then((snapshot) => setDetail({ state: 'ready', snapshot }))
      .catch((error: unknown) => {
        if (controller.signal.aborted) return;
        setDetail({
          state: 'error',
          message: error instanceof Error ? error.message : '引用 StaticSchema 读取失败。',
        });
      });
  };
  return (
    <div className="te-reference-schema-detail">
      <div>
        <p><span>引用</span>{identity.schemaKey} · {identity.versionTag}</p>
        <button
          type="button"
          aria-expanded={expanded}
          aria-label={`${expanded ? '收起' : '查看'}${identity.schemaKey}引用结构`}
          onClick={toggle}
        >
          {expanded ? '收起结构' : '查看结构'}
        </button>
      </div>
      {expanded ? <ReferenceSchemaBody detail={detail} /> : null}
    </div>
  );
}

function ReferenceSchemaBody({ detail }: { detail: ReferenceDetailState }) {
  if (detail.state === 'loading') {
    return <p className="te-reference-schema-state" role="status">正在读取引用结构…</p>;
  }
  if (detail.state === 'error') {
    return <p className="te-reference-schema-state is-error" role="alert">{detail.message}</p>;
  }
  const projection = projectTemplateStaticSchema(detail.snapshot);
  return (
    <section className="te-reference-schema-body" aria-label={`${projection.displayName}引用结构`}>
      <header>
        <strong>{projection.displayName}</strong>
        <code>{projection.identity.schemaKey} · {projection.identity.versionTag}</code>
      </header>
      <ul>
        {projection.fields.map((field) => (
          <li key={field.fieldKey}>
            <div><strong>{field.displayName}</strong><code>{field.fieldKey}</code></div>
            <span>{field.typeLabel}</span>
            <small>{field.constraintLabels.join(' · ')}</small>
            {field.reference ? (
              <p><span>引用</span>{field.reference.schemaKey} · {field.reference.versionTag}</p>
            ) : null}
          </li>
        ))}
      </ul>
    </section>
  );
}

function definitionRecords(value: unknown): Record<string, unknown>[] {
  return Array.isArray(value)
    ? value.filter((entry): entry is Record<string, unknown> => (
      typeof entry === 'object' && entry !== null && !Array.isArray(entry)
    ))
    : [];
}

function stringMember(value: Readonly<Record<string, unknown>>, key: string): string | null {
  return typeof value[key] === 'string' ? value[key] : null;
}

function definitionKindLabel(kind: unknown): string {
  switch (kind) {
    case 'custom': return '定义数据';
    case 'mapping': return '映射数据';
    case 'expression': return '表达式数据';
    default: return '未知定义';
  }
}
