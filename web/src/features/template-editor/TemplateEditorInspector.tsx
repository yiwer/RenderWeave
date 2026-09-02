import {
  useId,
  useRef,
  useState,
  type ChangeEvent,
  type KeyboardEvent,
  type ReactNode,
} from 'react';

import type { TemplateEditorCommandIntent } from './template-editor-commands';
import { normalizeTemplateEditorDisplayName } from './template-editor-display-name';
import {
  sameTemplateNumber,
  templateNumberDraft,
} from './template-editor-numbers';
import {
  objectOrNull,
  type EditorNodeProjection,
} from './template-editor-model';
import { isCoreTemplateAuthoringKind } from './template-editor-node-contract';

type InspectorTab = 'properties' | 'bindings';
type DraftParse<T> = { ok: true; value: T } | { ok: false; problem: string };

const COLOR = /^#[0-9A-Fa-f]{8}$/;

const KIND_LABELS: Readonly<Record<string, string>> = {
  canvas: '画布',
  frame: '框架',
  stack: '堆叠',
  rect: '矩形',
};

export interface TemplateEditorInspectorProps {
  readonly node?: EditorNodeProjection;
  readonly disabled: boolean;
  readonly onCommand: (intent: TemplateEditorCommandIntent) => void;
}

/**
 * A command-only inspector. Its local drafts never mutate the projected wire;
 * an authored change can only leave this component as a TemplateEditorCommandIntent.
 */
export function TemplateEditorInspector({
  node,
  disabled,
  onCommand,
}: TemplateEditorInspectorProps) {
  const [tab, setTab] = useState<InspectorTab>('properties');
  const tabsId = useId();

  if (!node) {
    return (
      <section className="te-node-inspector is-empty" aria-label="节点检视器">
        <div className="te-node-inspector-empty">
          <strong>未选择元素</strong>
          <span>从画布或结构中选择一个节点以查看它的已编写属性。</span>
        </div>
      </section>
    );
  }

  const bindings = bindingRecords(node.value.bindings);
  const propertiesTabId = `${tabsId}-properties-tab`;
  const bindingsTabId = `${tabsId}-bindings-tab`;
  const propertiesPanelId = `${tabsId}-properties-panel`;
  const bindingsPanelId = `${tabsId}-bindings-panel`;

  function tabKeyDown(event: KeyboardEvent<HTMLButtonElement>, current: InspectorTab) {
    let next: InspectorTab | null = null;
    if (event.key === 'ArrowRight' || event.key === 'ArrowLeft') {
      next = current === 'properties' ? 'bindings' : 'properties';
    } else if (event.key === 'Home') {
      next = 'properties';
    } else if (event.key === 'End') {
      next = 'bindings';
    }
    if (!next) return;
    event.preventDefault();
    setTab(next);
    document.getElementById(next === 'properties' ? propertiesTabId : bindingsTabId)?.focus();
  }

  return (
    <section
      className="te-node-inspector"
      aria-label={`${node.displayName} 属性检视器`}
      data-node-kind={node.kind}
    >
      <header className="te-node-inspector-heading">
        <span>{KIND_LABELS[node.kind] ?? node.kind}</span>
        <h2 title={node.displayName}>{node.displayName}</h2>
      </header>

      <div className="te-node-inspector-tabs" role="tablist" aria-label="检视器页签">
        <button
          type="button"
          id={propertiesTabId}
          role="tab"
          aria-selected={tab === 'properties'}
          aria-controls={propertiesPanelId}
          tabIndex={tab === 'properties' ? 0 : -1}
          onClick={() => setTab('properties')}
          onKeyDown={(event) => tabKeyDown(event, 'properties')}
        >
          属性
        </button>
        <button
          type="button"
          id={bindingsTabId}
          role="tab"
          aria-selected={tab === 'bindings'}
          aria-controls={bindingsPanelId}
          tabIndex={tab === 'bindings' ? 0 : -1}
          onClick={() => setTab('bindings')}
          onKeyDown={(event) => tabKeyDown(event, 'bindings')}
        >
          绑定 <span aria-label={`${bindings.length} 个绑定`}>{bindings.length}</span>
        </button>
      </div>

      {tab === 'properties' ? (
        <div
          key={propertyRevisionKey(node)}
          id={propertiesPanelId}
          className="te-node-inspector-panel"
          role="tabpanel"
          aria-labelledby={propertiesTabId}
        >
          <PropertiesPanel node={node} disabled={disabled} onCommand={onCommand} />
        </div>
      ) : (
        <div
          id={bindingsPanelId}
          className="te-node-inspector-panel"
          role="tabpanel"
          aria-labelledby={bindingsTabId}
        >
          <BindingsPanel bindings={bindings} />
        </div>
      )}
    </section>
  );
}

function PropertiesPanel({
  node,
  disabled,
  onCommand,
}: Required<Pick<TemplateEditorInspectorProps, 'node' | 'disabled' | 'onCommand'>>) {
  const value = node.value;
  const placement = objectOrNull(value.placement);
  const fill = objectOrNull(value.fill);
  const isCore = isCoreTemplateAuthoringKind(node.kind);
  const isCanvas = node.kind === 'canvas';
  const isFrame = node.kind === 'frame';
  const isStack = node.kind === 'stack';
  const isRect = node.kind === 'rect';
  const hasAbsoluteGeometry = placement?.type === 'ABSOLUTE' && !isCanvas;
  const hasLayoutProperties = isFrame || isStack;
  const hasAppearance = isCanvas || isFrame || isStack || isRect;

  return (
    <div className="te-node-inspector-groups">
      {isCore && !isCanvas ? (
        <InspectorGroup group="content" title="内容">
          <CommitInput
            label="名称"
            initialValue={stringValue(value.displayName, node.displayName)}
            disabled={disabled}
            parse={parseDisplayName}
            isUnchanged={(next) => next === value.displayName}
            onCommit={(displayName) => onCommand({
              operation: 'rename', nodeId: node.nodeId, displayName,
            })}
          />
        </InspectorGroup>
      ) : null}

      {hasLayoutProperties ? (
        <InspectorGroup group="layout-constraints" title="布局 / 约束">
          {isStack ? (
            <label className="te-node-inspector-field">
              <span>排列方向</span>
              <select
                value={value.direction === 'ROW' ? 'ROW' : 'COLUMN'}
                disabled={disabled}
                onChange={(event) => onCommand({
                  operation: 'set-property',
                  nodeId: node.nodeId,
                  property: 'direction',
                  value: event.currentTarget.value,
                })}
              >
                <option value="ROW">横向</option>
                <option value="COLUMN">纵向</option>
              </select>
            </label>
          ) : null}
          {isStack ? (
            <CommitInput
              label="间距"
              suffix="mm"
              inputMode="decimal"
              initialValue={templateNumberDraft(value.gapMm)}
              disabled={disabled}
              parse={(draft) => parseNumber(draft, false, '间距必须是非负有限数值。')}
              isUnchanged={(next) => sameTemplateNumber(value.gapMm, next)}
              onCommit={(next) => onCommand({
                operation: 'set-property', nodeId: node.nodeId, property: 'gapMm', value: next,
              })}
            />
          ) : null}
          <label className="te-node-inspector-check">
            <span>
              <strong>裁剪溢出内容</strong>
              <small>子元素不会绘制到容器边界之外</small>
            </span>
            <input
              type="checkbox"
              checked={value.clipContent === true}
              disabled={disabled}
              onChange={(event) => onCommand({
                operation: 'set-property',
                nodeId: node.nodeId,
                property: 'clipContent',
                value: event.currentTarget.checked,
              })}
            />
          </label>
        </InspectorGroup>
      ) : null}

      {isCanvas ? (
        <InspectorGroup group="position-size" title="位置 / 尺寸">
          <div className="te-node-inspector-field-grid">
            <CommitInput
              label="画布宽度"
              suffix="mm"
              inputMode="decimal"
              initialValue={templateNumberDraft(value.widthMm)}
              disabled={disabled}
              parse={(draft) => parseNumber(draft, true, '画布宽度必须大于 0。')}
              isUnchanged={(next) => sameTemplateNumber(value.widthMm, next)}
              onCommit={(next) => onCommand({
                operation: 'set-property', nodeId: node.nodeId,
                property: 'canvasWidthMm', value: next,
              })}
            />
            <CommitInput
              label="画布高度"
              suffix="mm"
              inputMode="decimal"
              initialValue={templateNumberDraft(value.heightMm)}
              disabled={disabled}
              parse={(draft) => parseNumber(draft, true, '画布高度必须大于 0。')}
              isUnchanged={(next) => sameTemplateNumber(value.heightMm, next)}
              onCommit={(next) => onCommand({
                operation: 'set-property', nodeId: node.nodeId,
                property: 'canvasHeightMm', value: next,
              })}
            />
          </div>
        </InspectorGroup>
      ) : hasAbsoluteGeometry && placement ? (
        <InspectorGroup group="position-size" title="位置 / 尺寸">
          <GeometryFields
            nodeId={node.nodeId}
            placement={placement}
            disabled={disabled}
            onCommand={onCommand}
          />
        </InspectorGroup>
      ) : null}

      {hasAppearance ? (
        <InspectorGroup group="appearance" title="外观">
          <CommitInput
            label={isCanvas ? '背景颜色' : '填充颜色'}
            initialValue={isCanvas
              ? stringValue(value.backgroundColor)
              : stringValue(fill?.color)}
            placeholder="#RRGGBBAA"
            disabled={disabled}
            parse={parseColor}
            isUnchanged={(next) => next === (isCanvas ? value.backgroundColor : fill?.color)}
            onCommit={(next) => onCommand({
              operation: 'set-property',
              nodeId: node.nodeId,
              property: isCanvas ? 'backgroundColor' : 'fillColor',
              value: next,
            })}
          />
        </InspectorGroup>
      ) : null}

      <InspectorGroup group="advanced" title="高级" defaultOpen={false}>
        <dl className="te-node-inspector-facts">
          <div><dt>节点类型</dt><dd>{KIND_LABELS[node.kind] ?? node.kind}</dd></div>
          <div><dt>nodeId</dt><dd><code title={node.nodeId}>{node.nodeId}</code></dd></div>
          <div><dt>子元素</dt><dd>{node.childCount}</dd></div>
          <div><dt>已有绑定</dt><dd>{bindingRecords(value.bindings).length}</dd></div>
          {placement && typeof placement.type === 'string' ? (
            <div><dt>Placement</dt><dd>{placement.type}</dd></div>
          ) : null}
        </dl>
      </InspectorGroup>
    </div>
  );
}

function InspectorGroup({
  group,
  title,
  defaultOpen = true,
  children,
}: {
  group: string;
  title: string;
  defaultOpen?: boolean;
  children: ReactNode;
}) {
  return (
    <details className="te-node-inspector-group" data-inspector-group={group} open={defaultOpen}>
      <summary>{title}</summary>
      <div className="te-node-inspector-group-content">{children}</div>
    </details>
  );
}

function CommitInput<T>({
  label,
  initialValue,
  disabled,
  parse,
  isUnchanged,
  onCommit,
  inputMode,
  suffix,
  placeholder,
}: {
  label: string;
  initialValue: string;
  disabled: boolean;
  parse: (draft: string) => DraftParse<T>;
  isUnchanged: (value: T) => boolean;
  onCommit: (value: T) => void;
  inputMode?: 'text' | 'decimal';
  suffix?: string;
  placeholder?: string;
}) {
  const inputId = useId();
  const problemId = `${inputId}-problem`;
  const [draft, setDraft] = useState(initialValue);
  const [problem, setProblem] = useState<string | null>(null);
  const submittedDraft = useRef<string | null>(null);

  function commit() {
    if (disabled) return;
    const parsed = parse(draft);
    if (!parsed.ok) {
      setProblem(parsed.problem);
      return;
    }
    setProblem(null);
    if (submittedDraft.current === draft) return;
    submittedDraft.current = draft;
    if (!isUnchanged(parsed.value)) onCommit(parsed.value);
  }

  function change(event: ChangeEvent<HTMLInputElement>) {
    setDraft(event.currentTarget.value);
    setProblem(null);
    submittedDraft.current = null;
  }

  function keyDown(event: KeyboardEvent<HTMLInputElement>) {
    if (event.key === 'Enter') {
      event.preventDefault();
      commit();
    } else if (event.key === 'Escape') {
      setDraft(initialValue);
      setProblem(null);
      submittedDraft.current = null;
    }
  }

  return (
    <label className="te-node-inspector-field" htmlFor={inputId}>
      <span>{label}</span>
      <span className="te-node-inspector-input">
        <input
          id={inputId}
          type="text"
          inputMode={inputMode}
          value={draft}
          placeholder={placeholder}
          disabled={disabled}
          aria-label={label}
          aria-invalid={problem !== null}
          aria-describedby={problem ? problemId : undefined}
          onChange={change}
          onBlur={commit}
          onKeyDown={keyDown}
        />
        {suffix ? <span aria-hidden="true">{suffix}</span> : null}
      </span>
      {problem ? <small id={problemId} className="te-node-inspector-problem" role="alert">{problem}</small> : null}
    </label>
  );
}

type GeometryKey = 'xMm' | 'yMm' | 'widthMm' | 'heightMm';
type GeometryDraft = Record<GeometryKey, string>;

function GeometryFields({
  nodeId,
  placement,
  disabled,
  onCommand,
}: {
  nodeId: string;
  placement: Record<string, unknown>;
  disabled: boolean;
  onCommand: (intent: TemplateEditorCommandIntent) => void;
}) {
  const fieldId = useId();
  const fixedWidth = placement.widthMode === 'FIXED';
  const fixedHeight = placement.heightMode === 'FIXED';
  const [draft, setDraft] = useState<GeometryDraft>(() => ({
    xMm: templateNumberDraft(placement.xMm),
    yMm: templateNumberDraft(placement.yMm),
    widthMm: templateNumberDraft(placement.widthMm),
    heightMm: templateNumberDraft(placement.heightMm),
  }));
  const [problems, setProblems] = useState<Partial<Record<GeometryKey, string>>>({});
  const submittedDraft = useRef<string | null>(null);

  function commit() {
    if (disabled) return;
    const parsed = parseGeometry(draft, fixedWidth, fixedHeight);
    setProblems(parsed.problems);
    if (!parsed.geometry) return;
    const signature = JSON.stringify(draft);
    if (submittedDraft.current === signature) return;
    submittedDraft.current = signature;
    if (sameGeometry(parsed.geometry, placement)) return;
    onCommand({ operation: 'set-geometry', nodeId, geometry: parsed.geometry });
  }

  function change(key: GeometryKey, event: ChangeEvent<HTMLInputElement>) {
    const nextValue = event.currentTarget.value;
    setDraft((current) => ({ ...current, [key]: nextValue }));
    setProblems((current) => ({ ...current, [key]: undefined }));
    submittedDraft.current = null;
  }

  function keyDown(event: KeyboardEvent<HTMLInputElement>) {
    if (event.key === 'Enter') {
      event.preventDefault();
      commit();
    }
  }

  const fields: Array<{ key: GeometryKey; label: string }> = [
    { key: 'xMm', label: 'X 坐标' },
    { key: 'yMm', label: 'Y 坐标' },
    ...(fixedWidth ? [{ key: 'widthMm' as const, label: '宽度' }] : []),
    ...(fixedHeight ? [{ key: 'heightMm' as const, label: '高度' }] : []),
  ];

  return (
    <div className="te-node-inspector-field-grid">
      {fields.map((field) => {
        const inputId = `${fieldId}-${field.key}`;
        const problemId = `${inputId}-problem`;
        const problem = problems[field.key];
        return (
          <label key={field.key} className="te-node-inspector-field" htmlFor={inputId}>
            <span>{field.label}</span>
            <span className="te-node-inspector-input">
              <input
                id={inputId}
                type="text"
                inputMode="decimal"
                value={draft[field.key]}
                disabled={disabled}
                aria-label={field.label}
                aria-invalid={problem !== undefined}
                aria-describedby={problem ? problemId : undefined}
                onChange={(event) => change(field.key, event)}
                onBlur={commit}
                onKeyDown={keyDown}
              />
              <span aria-hidden="true">mm</span>
            </span>
            {problem ? <small id={problemId} className="te-node-inspector-problem" role="alert">{problem}</small> : null}
          </label>
        );
      })}
    </div>
  );
}

function BindingsPanel({ bindings }: { bindings: readonly Record<string, unknown>[] }) {
  if (bindings.length === 0) {
    return (
      <div className="te-node-inspector-empty is-compact">
        <strong>暂无绑定</strong>
        <span>该节点的 DesignDSL 当前没有 authored binding。</span>
      </div>
    );
  }

  return (
    <ol className="te-node-inspector-bindings" aria-label="已有绑定">
      {bindings.map((binding, index) => {
        const bindingId = stringValue(binding.bindingId, `binding-${index + 1}`);
        return (
          <li key={`${bindingId}-${index}`}>
            <div>
              <span>目标属性</span>
              <strong>{targetPropertyLabel(binding.targetPropertyRef)}</strong>
            </div>
            <div>
              <span>数据来源</span>
              <strong>{sourceLabel(binding.source)}</strong>
            </div>
            <code title={bindingId}>{bindingId}</code>
          </li>
        );
      })}
    </ol>
  );
}

function parseGeometry(
  draft: GeometryDraft,
  fixedWidth: boolean,
  fixedHeight: boolean,
): {
  geometry?: Extract<TemplateEditorCommandIntent, { operation: 'set-geometry' }>['geometry'];
  problems: Partial<Record<GeometryKey, string>>;
} {
  const problems: Partial<Record<GeometryKey, string>> = {};
  const xMm = finiteDraft(draft.xMm);
  const yMm = finiteDraft(draft.yMm);
  const widthMm = fixedWidth ? positiveDraft(draft.widthMm) : undefined;
  const heightMm = fixedHeight ? positiveDraft(draft.heightMm) : undefined;
  if (xMm === null) problems.xMm = 'X 必须是有限数值。';
  if (yMm === null) problems.yMm = 'Y 必须是有限数值。';
  if (fixedWidth && widthMm === null) problems.widthMm = '宽度必须大于 0。';
  if (fixedHeight && heightMm === null) problems.heightMm = '高度必须大于 0。';
  if (Object.keys(problems).length > 0 || xMm === null || yMm === null) return { problems };
  return {
    problems,
    geometry: {
      xMm,
      yMm,
      ...(fixedWidth && widthMm !== null ? { widthMm } : {}),
      ...(fixedHeight && heightMm !== null ? { heightMm } : {}),
    },
  };
}

function sameGeometry(
  geometry: Extract<TemplateEditorCommandIntent, { operation: 'set-geometry' }>['geometry'],
  placement: Record<string, unknown>,
): boolean {
  return sameTemplateNumber(placement.xMm, geometry.xMm)
    && sameTemplateNumber(placement.yMm, geometry.yMm)
    && (geometry.widthMm === undefined
      || sameTemplateNumber(placement.widthMm, geometry.widthMm))
    && (geometry.heightMm === undefined
      || sameTemplateNumber(placement.heightMm, geometry.heightMm));
}

function parseDisplayName(draft: string): DraftParse<string> {
  const result = normalizeTemplateEditorDisplayName(draft);
  if (result.state === 'invalid') {
    return { ok: false, problem: '名称必须是 1–128 个有效字符。' };
  }
  return { ok: true, value: result.value };
}

function parseColor(draft: string): DraftParse<string> {
  const value = draft.trim();
  return COLOR.test(value)
    ? { ok: true, value: value.toUpperCase() }
    : { ok: false, problem: '请输入 #RRGGBBAA 格式的颜色。' };
}

function parseNumber(draft: string, positive: boolean, problem: string): DraftParse<number> {
  const value = finiteDraft(draft);
  if (value === null || (positive ? value <= 0 : value < 0)) return { ok: false, problem };
  return { ok: true, value };
}

function finiteDraft(draft: string): number | null {
  if (draft.trim().length === 0) return null;
  const value = Number(draft);
  return Number.isFinite(value) ? value : null;
}

function positiveDraft(draft: string): number | null {
  const value = finiteDraft(draft);
  return value !== null && value > 0 ? value : null;
}

function bindingRecords(value: unknown): readonly Record<string, unknown>[] {
  return Array.isArray(value)
    ? value.map(objectOrNull).filter((entry): entry is Record<string, unknown> => entry !== null)
    : [];
}

function targetPropertyLabel(value: unknown): string {
  const target = objectOrNull(value);
  if (!target) return '未知目标';
  let label = stringValue(target.rootPropertyId, '未知目标');
  if (!Array.isArray(target.selectors)) return label;
  for (const candidate of target.selectors) {
    const selector = objectOrNull(candidate);
    if (selector?.kind === 'member' && typeof selector.name === 'string') {
      label += `.${selector.name}`;
    } else if (selector?.kind === 'index') {
      const index = typeof selector.index === 'string'
        ? selector.index
        : templateNumberDraft(selector.index);
      if (index.length > 0) label += `[${index}]`;
    }
  }
  return label;
}

function sourceLabel(value: unknown): string {
  const source = objectOrNull(value);
  if (!source || typeof source.kind !== 'string') return '未知来源';
  switch (source.kind) {
    case 'literal':
      return `字面量 · ${valueTypeLabel(source.valueType)}`;
    case 'context':
      return `上下文 · ${compactParts(source.domain, source.pointer)}`;
    case 'definition':
      return `定义 · ${stringValue(source.definitionId, '未指定')}`;
    case 'loopIndex':
      return `循环索引 · ${stringValue(source.loopId, '未指定')}`;
    case 'capability':
      return `能力 · ${compactParts(source.capability, source.operation)}`;
    default:
      return source.kind;
  }
}

function valueTypeLabel(value: unknown): string {
  if (typeof value === 'string') return value;
  const type = objectOrNull(value);
  return type && typeof type.type === 'string' ? type.type : '未指定类型';
}

function compactParts(...values: unknown[]): string {
  const parts = values.filter((value): value is string => typeof value === 'string' && value.length > 0);
  return parts.length > 0 ? parts.join(' / ') : '未指定';
}

function propertyRevisionKey(node: EditorNodeProjection): string {
  const placement = objectOrNull(node.value.placement);
  const fill = objectOrNull(node.value.fill);
  return JSON.stringify([
    node.nodeId,
    node.kind,
    node.value.displayName,
    node.value.widthMm,
    node.value.heightMm,
    node.value.backgroundColor,
    node.value.clipContent,
    node.value.direction,
    node.value.gapMm,
    fill?.color,
    placement?.type,
    placement?.xMm,
    placement?.yMm,
    placement?.widthMode,
    placement?.widthMm,
    placement?.heightMode,
    placement?.heightMm,
    node.childCount,
    bindingRecords(node.value.bindings).length,
  ]);
}

function stringValue(value: unknown, fallback = ''): string {
  return typeof value === 'string' ? value : fallback;
}
