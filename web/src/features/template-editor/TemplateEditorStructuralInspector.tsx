import { useEffect, useId, useState } from 'react';

import { SelectField, type SelectFieldOption } from '../../components/SelectField';
import type { TemplateCatalogEntry } from '../../api/generated';
import type { StaticSnapshot } from '../schema-studio/lossless-api';
import type { EditorNodeProjection } from './template-editor-model';
import {
  projectBindingSources,
  type TemplateBindingValueType,
} from './template-editor-data-authoring';
import { objectOrNull } from './template-editor-model';
import type { TemplateStructuralConfiguration } from './template-editor-commands';
import type {
  TemplateRepeatSourceOption,
  TemplateBooleanSourceOption,
  TemplateStructuralNodeState,
  TemplateStructuralAuthoringProjection,
  TemplateStructuralSample,
} from './template-editor-structural-authoring';

const BINDING_TYPES = new Set<TemplateBindingValueType>([
  'text', 'decimal', 'boolean', 'date', 'time', 'color', 'imageRef', 'fontRef',
]);

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
  onCreateLoopTemplate,
}: TemplateEditorStructuralInspectorProps) {
  const value = node.value;
  const sources = projection.repeatSources[node.nodeId] ?? [];
  const selected = sources.find((option) => sameWire(option.source, value.items));
  const itemLayout = objectOrNull(value.itemLayout);
  const instanceLayout = objectOrNull(value.instanceLayout);
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
      itemLayout: patch.itemLayout ?? repeatPacking(itemLayout),
      instanceLayout: patch.instanceLayout ?? repeatPacking(instanceLayout),
    });
  };

  return (
    <section className="te-structural-inspector" aria-label="Repeat 结构配置">
      <h3>循环工作流</h3>
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
      <Field label="单项排列方向">
        <SelectField
          ariaLabel="单项排列方向"
          value={itemLayout?.kind === 'STACK' && itemLayout.direction === 'ROW' ? 'ROW' : 'COLUMN'}
          options={[{ value: 'COLUMN', label: '纵向' }, { value: 'ROW', label: '横向' }]}
          disabled={disabled}
          onChange={(direction) => configure({
            itemLayout: {
              kind: 'STACK', direction: direction as 'ROW' | 'COLUMN',
              gapMm: nonnegativeNumber(itemLayout?.gapMm, 0),
            },
          })}
        />
      </Field>
      <StructuralNumberInput
        label="单项间距"
        value={nonnegativeNumber(itemLayout?.gapMm, 0)}
        minimum={0}
        disabled={disabled}
        onCommit={(gapMm) => configure({
          itemLayout: {
            kind: 'STACK',
            direction: itemLayout?.direction === 'ROW' ? 'ROW' : 'COLUMN',
            gapMm,
          },
        })}
      />
      <Field label="循环布局方式">
        <SelectField
          ariaLabel="循环布局方式"
          value={instanceLayout?.kind === 'GRID' ? 'GRID' : 'STACK'}
          options={[{ value: 'STACK', label: '堆叠' }, { value: 'GRID', label: '网格' }]}
          disabled={disabled}
          onChange={(kind) => configure({
            instanceLayout: kind === 'GRID'
              ? { kind: 'GRID', columns: 2, columnGapMm: 0, rowGapMm: 0 }
              : { kind: 'STACK', direction: 'COLUMN', gapMm: 0 },
          })}
        />
      </Field>
      {instanceLayout?.kind === 'GRID' ? (
        <>
          <StructuralNumberInput
            label="循环网格列数"
            value={positiveInteger(instanceLayout.columns, 2)}
            minimum={1}
            integer
            disabled={disabled}
            onCommit={(columns) => configure({ instanceLayout: {
              kind: 'GRID', columns,
              columnGapMm: nonnegativeNumber(instanceLayout.columnGapMm, 0),
              rowGapMm: nonnegativeNumber(instanceLayout.rowGapMm, 0),
            } })}
          />
          <StructuralNumberInput
            label="循环列间距"
            value={nonnegativeNumber(instanceLayout.columnGapMm, 0)}
            minimum={0}
            disabled={disabled}
            onCommit={(columnGapMm) => configure({ instanceLayout: {
              kind: 'GRID', columns: positiveInteger(instanceLayout.columns, 2),
              columnGapMm, rowGapMm: nonnegativeNumber(instanceLayout.rowGapMm, 0),
            } })}
          />
          <StructuralNumberInput
            label="循环行间距"
            value={nonnegativeNumber(instanceLayout.rowGapMm, 0)}
            minimum={0}
            disabled={disabled}
            onCommit={(rowGapMm) => configure({ instanceLayout: {
              kind: 'GRID', columns: positiveInteger(instanceLayout.columns, 2),
              columnGapMm: nonnegativeNumber(instanceLayout.columnGapMm, 0), rowGapMm,
            } })}
          />
        </>
      ) : null}
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
  designDsl,
  staticSchema,
  staticSchemas,
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
  const contextSelector = node.value.contextSelector;
  const fillTargets = state?.kind === 'templateUse' ? state.fillTargets : [];
  return (
    <section className="te-structural-inspector" aria-label="TemplateUse 结构配置">
      <h3>嵌套模板工作流</h3>
      {currentTarget ? <p className="te-structural-current">{currentTarget.displayName}</p> : null}
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
        const targetType = bindingType(target.valueType);
        const sources = targetType
          ? projectBindingSources(designDsl, staticSchema, node.nodeId, targetType, staticSchemas)
          : [];
        const existing = fills.find((fill) => fill.targetDefinitionId === target.definitionId);
        const selectedSource = sources.find((source) => sameWire(source.source, existing?.source));
        return (
          <Field key={target.definitionId} label={`${target.displayName} 来源`}>
            <SelectField
              ariaLabel={`${target.displayName} 来源`}
              value={selectedSource?.id ?? ''}
              options={sources.map((source) => ({
                value: source.id,
                label: `${source.label} · ${source.detail}`,
                disabled: source.state !== 'available',
              }))}
              disabled={disabled || !targetType}
              placeholder="选择同类型公开赋值来源"
              onChange={(id) => {
                const source = sources.find((candidate) => candidate.id === id);
                if (!source || source.state !== 'available' || !contextSelector) return;
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
  const [draft, setDraft] = useState(String(value));
  const commit = () => {
    const parsed = Number(draft);
    if (!Number.isFinite(parsed) || parsed < minimum || (integer && !Number.isSafeInteger(parsed))) {
      setDraft(String(value));
      return;
    }
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
        onChange={(event) => setDraft(event.currentTarget.value)}
        onBlur={commit}
        onKeyDown={(event) => {
          if (event.key === 'Enter') {
            event.preventDefault();
            commit();
          }
        }}
      />
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

function repeatAbsentPolicy(value: unknown): 'EMPTY' | 'ERROR' {
  return value === 'ERROR' ? 'ERROR' : 'EMPTY';
}

function conditionalAbsentPolicy(value: unknown): 'FALSE' | 'ERROR' {
  return value === 'ERROR' ? 'ERROR' : 'FALSE';
}

function bindingType(value: unknown): TemplateBindingValueType | null {
  return typeof value === 'string' && BINDING_TYPES.has(value as TemplateBindingValueType)
    ? value as TemplateBindingValueType
    : null;
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

function sameSourceDomain(left: unknown, right: unknown): boolean {
  if (left === 'invocation' || right === 'invocation') return left === right;
  const leftDomain = objectOrNull(left);
  const rightDomain = objectOrNull(right);
  return leftDomain?.kind === 'loop' && rightDomain?.kind === 'loop'
    && leftDomain.loopId === rightDomain.loopId;
}
