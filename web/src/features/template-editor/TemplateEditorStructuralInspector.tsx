import { useEffect, useId, useState } from 'react';

import { SelectField, type SelectFieldOption } from '../../components/SelectField';
import type { TemplateCatalogEntry } from '../../api/generated';
import type { StaticSnapshot } from '../schema-studio/lossless-api';
import type { EditorNodeProjection } from './template-editor-model';
import { objectOrNull } from './template-editor-model';
import type { TemplateStructuralConfiguration } from './template-editor-commands';
import type {
  TemplateRepeatSourceOption,
  TemplateBooleanSourceOption,
  TemplateStructuralNodeState,
  TemplateStructuralAuthoringProjection,
  TemplateStructuralSample,
  TemplateUseContextOption,
  TemplateUseFillSourceOption,
} from './template-editor-structural-authoring';

export interface TemplateEditorStructuralInspectorProps {
  readonly node: EditorNodeProjection;
  readonly projection: TemplateStructuralAuthoringProjection;
  readonly templateCatalog: readonly TemplateCatalogEntry[];
  readonly designDsl: Readonly<Record<string, unknown>>;
  readonly staticSchema: StaticSnapshot;
  readonly staticSchemas: readonly StaticSnapshot[];
  readonly disabled: boolean;
  readonly onConfigure: (configuration: TemplateStructuralConfiguration) => void;
  readonly onPreviewSample: (sample: TemplateStructuralSample) => void;
  readonly onCreateLoopTemplate: (templateId: string, existingNodeId?: string) => void;
  readonly onSelectTemplateTarget: (templateId: string) => void;
  readonly onEnsureTemplateCurrent?: (templateId: string) => void;
}

export function TemplateEditorStructuralInspector(
  props: TemplateEditorStructuralInspectorProps,
) {
  if (props.node.kind === 'repeat') return <RepeatInspector {...props} />;
  if (props.node.kind === 'conditional') return <ConditionalInspector {...props} />;
  if (props.node.kind === 'templateUse') return <TemplateUseInspector {...props} />;
  return null;
}

function RepeatInspector({
  node,
  projection,
  templateCatalog,
  disabled,
  onConfigure,
  onPreviewSample,
  onCreateLoopTemplate,
}: TemplateEditorStructuralInspectorProps) {
  const value = node.value;
  const sources = projection.repeatSources[node.nodeId] ?? [];
  const selected = sources.find((option) => sameWire(option.source, value.items));
  const itemLayout = repeatPacking(objectOrNull(value.itemLayout));
  const instanceLayout = repeatPacking(objectOrNull(value.instanceLayout));
  const templateUse = childNodes(value).find((child) => child.kind === 'templateUse');
  const currentTemplateId = stringMember(objectOrNull(templateUse?.templateRef), 'templateId') ?? '';
  const referenceTargets = selected?.itemKind === 'reference'
    ? templateCatalog.map((entry): SelectFieldOption => {
      const compatible = entry.readiness === 'READY'
        && entry.staticSchema.schemaKey === selected.itemContext.schemaKey
        && entry.staticSchema.versionTag === selected.itemContext.versionTag;
      return {
        value: entry.templateId,
        label: `${entry.displayName} · ${entry.staticSchema.schemaKey}@${entry.staticSchema.versionTag}${entry.readiness === 'READY' ? '' : ` · ${entry.readiness}`}`,
        disabled: !compatible,
      };
    })
    : [];
  const configure = (patch: Partial<Extract<TemplateStructuralConfiguration, { kind: 'repeat' }>>) => {
    const items = patch.items ?? value.items;
    if (!items) return;
    onConfigure({
      kind: 'repeat',
      items: items as Extract<TemplateStructuralConfiguration, { kind: 'repeat' }>['items'],
      absentPolicy: patch.absentPolicy ?? repeatAbsentPolicy(value.absentPolicy),
      itemLayout: patch.itemLayout ?? itemLayout,
      instanceLayout: patch.instanceLayout ?? instanceLayout,
    });
  };

  return (
    <section className="te-structural-inspector" aria-label="Repeat 结构配置">
      <h3>循环工作流</h3>
      {childNodes(value).length === 0 ? (
        <p className="te-field-help">当前结构暂时为空；添加循环内容后才能通过保存校验。</p>
      ) : null}
      <Field label="循环列表属性">
        <SelectField
          ariaLabel="循环列表属性"
          value={selected?.id ?? ''}
          options={sources.map(sourceOption)}
          disabled={disabled || sources.length === 0}
          placeholder="请先加载可用列表字段"
          onChange={(id) => {
            const source = sources.find((option) => option.id === id);
            if (source) configure({ items: source.source as never });
          }}
        />
      </Field>
      <Field label="循环缺失策略">
        <SelectField
          ariaLabel="循环缺失策略"
          value={repeatAbsentPolicy(value.absentPolicy)}
          options={[
            { value: 'EMPTY', label: '按空列表处理' },
            { value: 'ERROR', label: '报告错误' },
          ]}
          disabled={disabled}
          onChange={(absentPolicy) => configure({ absentPolicy: absentPolicy as 'EMPTY' | 'ERROR' })}
        />
      </Field>
      <div className="te-structural-preview" role="group" aria-label="循环预览输入">
        {(['VALUES', 'EMPTY', 'ABSENT', 'ERROR'] as const).map((state) => (
          <button
            key={state}
            type="button"
            disabled={disabled || (state === 'VALUES' && !selected)}
            onClick={() => onPreviewSample(state === 'VALUES'
              ? { state: 'value', value: repeatSampleValues(selected) }
              : state === 'EMPTY'
                ? { state: 'value', value: [] }
                : state === 'ABSENT'
                  ? { state: 'absent' }
                  : { state: 'error', code: 'EDITOR_SAMPLE_ERROR' })}
          >
            {state}
          </button>
        ))}
      </div>
      <RepeatPackingEditor
        scope="单项"
        value={itemLayout}
        disabled={disabled}
        onCommit={(next) => configure({ itemLayout: next })}
      />
      <RepeatPackingEditor
        scope="循环"
        value={instanceLayout}
        disabled={disabled}
        onCommit={(next) => configure({ instanceLayout: next })}
      />
      {selected?.itemKind === 'reference' ? (
        <Field label="循环单项模板">
          <SelectField
            ariaLabel="循环单项模板"
            value={currentTemplateId}
            options={referenceTargets}
            disabled={disabled || referenceTargets.every((option) => option.disabled)}
            placeholder="选择 exact-schema READY 模板"
            onChange={(templateId) => onCreateLoopTemplate(
              templateId,
              typeof templateUse?.nodeId === 'string' ? templateUse.nodeId : undefined,
            )}
          />
        </Field>
      ) : null}
      <StructuralState state={projection.nodeStates[node.nodeId]} />
    </section>
  );
}

type RepeatConfiguration = Extract<TemplateStructuralConfiguration, { kind: 'repeat' }>;
type RepeatPacking = NonNullable<RepeatConfiguration['itemLayout']>;

function RepeatPackingEditor({
  scope,
  value,
  disabled,
  onCommit,
}: {
  scope: '单项' | '循环';
  value: RepeatPacking;
  disabled: boolean;
  onCommit: (value: RepeatPacking) => void;
}) {
  return (
    <>
      <Field label={`${scope}布局方式`}>
        <SelectField
          ariaLabel={`${scope}布局方式`}
          value={value.kind}
          options={[{ value: 'STACK', label: '堆叠' }, { value: 'GRID', label: '网格' }]}
          disabled={disabled}
          onChange={(kind) => onCommit(kind === 'GRID'
            ? { kind: 'GRID', columns: 2, columnGapMm: 0, rowGapMm: 0 }
            : { kind: 'STACK', direction: 'COLUMN', gapMm: 0 })}
        />
      </Field>
      {value.kind === 'STACK' ? (
        <>
          <Field label={`${scope}排列方向`}>
            <SelectField
              ariaLabel={`${scope}排列方向`}
              value={value.direction}
              options={[{ value: 'COLUMN', label: '纵向' }, { value: 'ROW', label: '横向' }]}
              disabled={disabled}
              onChange={(direction) => onCommit({
                kind: 'STACK',
                direction: direction as 'ROW' | 'COLUMN',
                gapMm: nonnegativeNumber(value.gapMm, 0),
              })}
            />
          </Field>
          <StructuralNumberInput
            label={`${scope}间距`}
            value={nonnegativeNumber(value.gapMm, 0)}
            minimum={0}
            disabled={disabled}
            onCommit={(gapMm) => onCommit({ kind: 'STACK', direction: value.direction, gapMm })}
          />
        </>
      ) : (
        <>
          <StructuralNumberInput
            label={`${scope}网格列数`}
            value={positiveInteger(value.columns, 2)}
            minimum={1}
            integer
            disabled={disabled}
            onCommit={(columns) => onCommit({
              kind: 'GRID', columns,
              columnGapMm: nonnegativeNumber(value.columnGapMm, 0),
              rowGapMm: nonnegativeNumber(value.rowGapMm, 0),
            })}
          />
          <StructuralNumberInput
            label={`${scope}列间距`}
            value={nonnegativeNumber(value.columnGapMm, 0)}
            minimum={0}
            disabled={disabled}
            onCommit={(columnGapMm) => onCommit({
              kind: 'GRID', columns: positiveInteger(value.columns, 2),
              columnGapMm, rowGapMm: nonnegativeNumber(value.rowGapMm, 0),
            })}
          />
          <StructuralNumberInput
            label={`${scope}行间距`}
            value={nonnegativeNumber(value.rowGapMm, 0)}
            minimum={0}
            disabled={disabled}
            onCommit={(rowGapMm) => onCommit({
              kind: 'GRID', columns: positiveInteger(value.columns, 2),
              columnGapMm: nonnegativeNumber(value.columnGapMm, 0), rowGapMm,
            })}
          />
        </>
      )}
    </>
  );
}

function ConditionalInspector({
  node,
  projection,
  disabled,
  onConfigure,
  onPreviewSample,
}: TemplateEditorStructuralInspectorProps) {
  const sources = projection.booleanSources[node.nodeId] ?? [];
  const selected = sources.find((option) => sameWire(option.source, node.value.condition));
  const configure = (
    condition: unknown = node.value.condition,
    absentPolicy: 'FALSE' | 'ERROR' = conditionalAbsentPolicy(node.value.absentPolicy),
  ) => {
    if (!condition) return;
    onConfigure({ kind: 'conditional', condition: condition as never, absentPolicy });
  };
  return (
    <section className="te-structural-inspector" aria-label="Conditional 结构配置">
      <h3>条件工作流</h3>
      {childNodes(node.value).length === 0 ? (
        <p className="te-field-help">当前结构暂时为空；添加 TRUE 分支内容后才能通过保存校验。</p>
      ) : null}
      <Field label="条件数据源">
        <SelectField
          ariaLabel="条件数据源"
          value={selected?.id ?? ''}
          options={sources.map(sourceOption)}
          disabled={disabled || sources.length === 0}
          placeholder="请先加载布尔字段"
          onChange={(id) => {
            const source = sources.find((option) => option.id === id);
            if (source) configure(source.source);
          }}
        />
      </Field>
      <Field label="条件缺失策略">
        <SelectField
          ariaLabel="条件缺失策略"
          value={conditionalAbsentPolicy(node.value.absentPolicy)}
          options={[
            { value: 'FALSE', label: '按 FALSE 剪枝' },
            { value: 'ERROR', label: '报告错误' },
          ]}
          disabled={disabled}
          onChange={(policy) => configure(node.value.condition, policy as 'FALSE' | 'ERROR')}
        />
      </Field>
      <div className="te-structural-preview" role="group" aria-label="条件预览输入">
        {(['TRUE', 'FALSE', 'ABSENT', 'ERROR'] as const).map((state) => (
          <button
            key={state}
            type="button"
            disabled={disabled}
            onClick={() => onPreviewSample(state === 'TRUE'
              ? { state: 'value', value: true }
              : state === 'FALSE'
                ? { state: 'value', value: false }
                : state === 'ABSENT'
                  ? { state: 'absent' }
                  : { state: 'error', code: 'EDITOR_SAMPLE_ERROR' })}
          >
            {state}
          </button>
        ))}
      </div>
      <StructuralState state={projection.nodeStates[node.nodeId]} />
    </section>
  );
}

function TemplateUseInspector({
  node,
  projection,
  templateCatalog,
  disabled,
  onConfigure,
  onSelectTemplateTarget,
  onEnsureTemplateCurrent,
}: TemplateEditorStructuralInspectorProps) {
  const templateRef = objectOrNull(node.value.templateRef);
  const templateId = stringMember(templateRef, 'templateId') ?? '';
  const state = projection.nodeStates[node.nodeId];
  const targets = projection.templateTargets[node.nodeId] ?? [];
  const currentTarget = templateCatalog.find((target) => target.templateId === templateId);
  useEffect(() => {
    if (templateId) onEnsureTemplateCurrent?.(templateId);
  }, [onEnsureTemplateCurrent, templateId]);
  const fills = Array.isArray(node.value.fills)
    ? node.value.fills.map(objectOrNull).filter(notNull)
    : [];
  const contextSelector = objectOrNull(node.value.contextSelector);
  const fillTargets = state?.kind === 'templateUse' ? state.fillTargets : [];
  const contextOptions = state?.kind === 'templateUse' ? state.contextOptions : [];
  const selectedContext = contextOptions.find((option) => (
    sameTemplateContextSelector(option.selector, contextSelector)
  ));
  const contextValue = selectedContext?.id ?? (contextSelector ? '__unavailable__' : '');
  const contextPolicy = contextSelector?.contextAbsentPolicy === 'SKIP' ? 'SKIP' : 'ERROR';
  return (
    <section className="te-structural-inspector" aria-label="TemplateUse 结构配置">
      <h3>嵌套模板工作流</h3>
      {currentTarget ? <p className="te-structural-current">{currentTarget.displayName}</p> : null}
      <Field label="子模板上下文">
        <SelectField
          ariaLabel="子模板上下文"
          value={contextValue}
          options={[
            ...(contextSelector && !selectedContext ? [{
              value: '__unavailable__', label: '当前上下文不可用', disabled: true,
            }] : []),
            ...contextOptions.map(templateContextOption),
          ]}
          disabled={disabled}
          placeholder="选择 whole context 或 reference 路径"
          onChange={(id) => {
            const option = contextOptions.find((candidate) => candidate.id === id);
            if (!option) return;
            const next = objectOrNull(option.selector);
            const nextSelector = next?.kind === 'context'
              ? { ...option.selector, contextAbsentPolicy: contextPolicy }
              : option.selector;
            onConfigure({
              kind: 'templateUse', templateId,
              contextSelector: nextSelector as never,
              fills: fills as never,
            });
          }}
        />
      </Field>
      {contextSelector?.kind === 'context' ? (
        <Field label="上下文缺失策略">
          <SelectField
            ariaLabel="上下文缺失策略"
            value={contextPolicy}
            options={[
              { value: 'ERROR', label: 'ERROR · 终止本次求值' },
              { value: 'SKIP', label: 'SKIP · 移除本次嵌套出现' },
            ]}
            disabled={disabled}
            onChange={(policy) => {
              if (policy !== 'ERROR' && policy !== 'SKIP') return;
              onConfigure({
                kind: 'templateUse', templateId,
                contextSelector: {
                  ...contextSelector,
                  contextAbsentPolicy: policy,
                } as never,
                fills: fills as never,
              });
            }}
          />
        </Field>
      ) : null}
      <Field label="嵌套模板选择">
        <SelectField
          ariaLabel="嵌套模板选择"
          value={templateId}
          options={targets.map((target) => ({
            value: target.templateId,
            label: `${target.displayName} · ${target.staticSchema.schemaKey}@${target.staticSchema.versionTag}${target.state === 'eligible' ? '' : ` · ${target.readiness}`}`,
            disabled: target.state !== 'eligible',
          }))}
          disabled={disabled}
          onChange={onSelectTemplateTarget}
        />
      </Field>
      {fillTargets.map((target) => {
        const sources = target.sources;
        const existing = fills.find((fill) => fill.targetDefinitionId === target.definitionId);
        const selectedSource = sources.find((source) => sameWire(source.source, existing?.source));
        const sourceValue = selectedSource?.id ?? (existing ? '__unavailable__' : '');
        return (
          <Field key={target.definitionId} label={`${target.displayName} 来源`}>
            <SelectField
              ariaLabel={`${target.displayName} 来源`}
              value={sourceValue}
              options={[
                { value: '', label: '使用子模板默认值' },
                ...(existing && !selectedSource ? [{
                  value: '__unavailable__', label: '当前来源不可用', disabled: true,
                }] : []),
                ...sources.map(templateFillSourceOption),
              ]}
              disabled={disabled}
              onChange={(id) => {
                if (!contextSelector) return;
                if (id === '') {
                  onConfigure({
                    kind: 'templateUse', templateId,
                    contextSelector: contextSelector as never,
                    fills: fills.filter((fill) => (
                      fill.targetDefinitionId !== target.definitionId
                    )) as never,
                  });
                  return;
                }
                const source = sources.find((candidate) => candidate.id === id);
                if (!source) return;
                const nextFills = [
                  ...fills.filter((fill) => fill.targetDefinitionId !== target.definitionId),
                  { targetDefinitionId: target.definitionId, source: source.source },
                ];
                onConfigure({
                  kind: 'templateUse', templateId,
                  contextSelector: contextSelector as never,
                  fills: nextFills as never,
                });
              }}
            />
          </Field>
        );
      })}
      {fillTargets.length === 0 ? (
        <p className="te-field-help">选择后只读取该 Template current，以发现 PUBLIC fills。</p>
      ) : null}
      <StructuralState state={state} />
    </section>
  );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return <div className="te-structural-field"><span>{label}</span>{children}</div>;
}

function StructuralNumberInput({
  label,
  value,
  minimum,
  integer = false,
  disabled,
  onCommit,
}: {
  label: string;
  value: number;
  minimum: number;
  integer?: boolean;
  disabled: boolean;
  onCommit: (value: number) => void;
}) {
  const id = useId();
  const problemId = `${id}-problem`;
  const [field, setField] = useState<{
    readonly sourceValue: number;
    readonly draft: string;
    readonly problem: string | null;
  }>({ sourceValue: value, draft: String(value), problem: null });
  if (field.sourceValue !== value) {
    setField({ sourceValue: value, draft: String(value), problem: null });
  }
  const { draft, problem } = field;
  const commit = () => {
    const parsed = draft.trim() === '' ? Number.NaN : Number(draft);
    if (!Number.isFinite(parsed) || parsed < minimum || (integer && !Number.isSafeInteger(parsed))) {
      setField((current) => ({
        ...current,
        problem: integer
          ? `请输入不小于 ${minimum} 的整数。`
          : `请输入不小于 ${minimum} 的数字。`,
      }));
      return;
    }
    if (problem) setField((current) => ({ ...current, problem: null }));
    if (parsed !== value) onCommit(parsed);
  };
  return (
    <label className="te-structural-field" htmlFor={id}>
      <span>{label}</span>
      <input
        id={id}
        aria-label={label}
        type="number"
        min={minimum}
        step={integer ? 1 : 'any'}
        value={draft}
        disabled={disabled}
        aria-invalid={problem !== null}
        aria-describedby={problem ? problemId : undefined}
        onChange={(event) => {
          setField({ sourceValue: value, draft: event.currentTarget.value, problem: null });
        }}
        onBlur={commit}
        onKeyDown={(event) => {
          if (event.key === 'Enter') {
            event.preventDefault();
            commit();
          }
        }}
      />
      {problem ? (
        <small id={problemId} className="te-node-inspector-problem" role="alert">{problem}</small>
      ) : null}
    </label>
  );
}

function StructuralState({ state }: { state: TemplateStructuralNodeState | undefined }) {
  if (!state || state.authoringState === 'READY') return null;
  return (
    <div className="te-node-inspector-warning" role="status">
      <strong>{state.authoringState === 'NEEDS_REPAIR' ? '需要修复' : '配置不可用'}</strong>
      <span>{state.problems.join(' · ')}</span>
    </div>
  );
}

function sourceOption(
  source: TemplateRepeatSourceOption | TemplateBooleanSourceOption,
): SelectFieldOption {
  const pointer = objectOrNull(source.source)?.pointer;
  return {
    value: source.id,
    label: `${source.label}${typeof pointer === 'string' ? ` · ${pointer}` : ''}`,
  };
}

function templateContextOption(option: TemplateUseContextOption): SelectFieldOption {
  const selector = objectOrNull(option.selector);
  const pointer = stringMember(selector, 'pointer');
  return {
    value: option.id,
    label: `${option.label}${pointer ? ` · ${pointer}` : ''} · ${option.schema.schemaKey}@${option.schema.versionTag}${option.presence === 'MAY_BE_ABSENT' ? ' · 可能缺失' : ''}`,
  };
}

function templateFillSourceOption(source: TemplateUseFillSourceOption): SelectFieldOption {
  const pointer = objectOrNull(source.source)?.pointer;
  return {
    value: source.id,
    label: `${source.label}${typeof pointer === 'string' ? ` · ${pointer}` : ''}${source.presence === 'MAY_BE_ABSENT' ? ' · 可能缺失，缺失时使用子模板默认值' : ''}`,
  };
}

function repeatPacking(value: Record<string, unknown> | null) {
  return value?.kind === 'GRID'
    ? {
      kind: 'GRID' as const,
      columns: positiveInteger(value.columns, 2),
      columnGapMm: nonnegativeNumber(value.columnGapMm, 0),
      rowGapMm: nonnegativeNumber(value.rowGapMm, 0),
    }
    : {
      kind: 'STACK' as const,
      direction: value?.direction === 'ROW' ? 'ROW' as const : 'COLUMN' as const,
      gapMm: nonnegativeNumber(value?.gapMm, 0),
    };
}

function repeatSampleValues(source: TemplateRepeatSourceOption | undefined): readonly unknown[] {
  if (!source) return [];
  if (source.itemKind === 'reference') return [{}, {}, {}];
  switch (source.itemContext.schemaKey) {
    case 'system-basic-decimal':
      return [1, 2, 3];
    case 'system-basic-boolean':
      return [true, false, true];
    case 'system-basic-date':
      return ['2026-09-01', '2026-09-02', '2026-09-03'];
    case 'system-basic-time':
      return ['09:00:00', '12:00:00', '17:00:00'];
    default:
      return ['A', 'B', 'C'];
  }
}

function repeatAbsentPolicy(value: unknown): 'EMPTY' | 'ERROR' {
  return value === 'ERROR' ? 'ERROR' : 'EMPTY';
}

function conditionalAbsentPolicy(value: unknown): 'FALSE' | 'ERROR' {
  return value === 'ERROR' ? 'ERROR' : 'FALSE';
}

function childNodes(value: Readonly<Record<string, unknown>>): Record<string, unknown>[] {
  return Array.isArray(value.children) ? value.children.map(objectOrNull).filter(notNull) : [];
}

function notNull<T>(value: T | null): value is T {
  return value !== null;
}

function stringMember(value: Record<string, unknown> | null, member: string): string | null {
  return value && typeof value[member] === 'string' ? value[member] : null;
}

function nonnegativeNumber(value: unknown, fallback: number): number {
  return typeof value === 'number' && Number.isFinite(value) && value >= 0 ? value : fallback;
}

function positiveInteger(value: unknown, fallback: number): number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value > 0 ? value : fallback;
}

function sameWire(left: unknown, right: unknown): boolean {
  const leftSource = objectOrNull(left);
  const rightSource = objectOrNull(right);
  if (!leftSource || !rightSource || leftSource.kind !== rightSource.kind) return false;
  if (leftSource.kind === 'definition') {
    return leftSource.definitionId === rightSource.definitionId;
  }
  if (leftSource.kind === 'loopIndex') return leftSource.loopId === rightSource.loopId;
  if (leftSource.kind !== 'context') return false;
  return leftSource.pointer === rightSource.pointer
    && sameSourceDomain(leftSource.domain, rightSource.domain);
}

function sameTemplateContextSelector(
  left: unknown,
  right: Readonly<Record<string, unknown>> | null,
): boolean {
  const leftSelector = objectOrNull(left);
  if (!leftSelector || !right || leftSelector.kind !== right.kind) return false;
  if (leftSelector.kind === 'empty') return true;
  if (leftSelector.kind !== 'context' || right.kind !== 'context') return false;
  return leftSelector.pointer === right.pointer
    && sameTemplateSelectorDomain(leftSelector.domain, right.domain);
}

function sameTemplateSelectorDomain(left: unknown, right: unknown): boolean {
  const leftDomain = objectOrNull(left);
  const rightDomain = objectOrNull(right);
  if (!leftDomain || !rightDomain || leftDomain.kind !== rightDomain.kind) return false;
  return leftDomain.kind === 'invocation' || leftDomain.loopId === rightDomain.loopId;
}

function sameSourceDomain(left: unknown, right: unknown): boolean {
  if (left === 'invocation' || right === 'invocation') return left === right;
  const leftDomain = objectOrNull(left);
  const rightDomain = objectOrNull(right);
  return leftDomain?.kind === 'loop' && rightDomain?.kind === 'loop'
    && leftDomain.loopId === rightDomain.loopId;
}
