import {
  AlertTriangle,
  ArrowDown,
  ArrowUp,
  Bot,
  Check,
  FileJson2,
  Image as ImageIcon,
  Info,
  ShieldAlert,
  Trash2,
  UserRound,
  X,
} from 'lucide-react';
import { useId, useState, type Dispatch } from 'react';

import type {
  CandidateAssessment,
  CandidateField,
  CandidateProblem,
  CandidateReference,
  CandidateSchema,
  CandidateValue,
} from '../../api/generated';
import {
  candidateValue,
  type ArrayItemCandidateKind,
  type CandidateReviewAction,
  type CandidateReviewState,
  type FinalCandidateKind,
} from './candidate-session';
import { candidateTypeLabels, problemLabel, resolutionLabels } from './candidate-format';

const finalKinds: FinalCandidateKind[] = ['TEXT', 'DECIMAL', 'DATE', 'TIME', 'BOOLEAN', 'REFERENCE', 'ARRAY'];
const arrayItemKinds: ArrayItemCandidateKind[] = ['TEXT', 'DECIMAL', 'DATE', 'TIME', 'BOOLEAN', 'REFERENCE'];

interface ConstraintDefinition {
  key: string;
  label: string;
  help: string;
  placeholder?: string;
  inputMode?: 'text' | 'numeric' | 'decimal';
  control?: 'input' | 'textarea' | 'boolean';
}

const orderedRangeConstraints: ConstraintDefinition[] = [
  { key: 'min', label: '最小值', help: '包含边界。', placeholder: '2026-01-01' },
  { key: 'exclusiveMin', label: '大于', help: '不包含边界。', placeholder: '2026-01-01' },
  { key: 'max', label: '最大值', help: '包含边界。', placeholder: '2026-12-31' },
  { key: 'exclusiveMax', label: '小于', help: '不包含边界。', placeholder: '2026-12-31' },
  { key: 'enum', label: '允许值', help: '使用 JSON 数组，例如 ["A","B"]。', control: 'textarea' },
  { key: 'const', label: '固定值', help: '只允许一个确定值。' },
];

export function CandidateInspector({
  state,
  schema,
  field,
  dispatch,
  readOnly = false,
  onClose,
}: {
  state: CandidateReviewState;
  schema: CandidateSchema;
  field: CandidateField | null;
  dispatch: Dispatch<CandidateReviewAction>;
  readOnly?: boolean;
  onClose?: () => void;
}) {
  const item = field ?? schema;
  const itemId = field?.candidateFieldId ?? schema.candidateSchemaId;
  const itemProblems = state.snapshot.problems.filter((problem) => problem.itemId === itemId);
  const removed = item.assessment.resolution === 'REMOVED';
  const rootSchema = field === null && schema.candidateSchemaId === state.draft.rootCandidateSchemaId;
  return (
    <aside className="candidate-inspector" aria-label="Candidate 属性与证据">
      <header>
        <div>
          <span>{field ? '字段审核' : '数据结构审核'}</span>
          <h2>{field?.displayName || field?.proposedFieldKey || schema.displayName || schema.proposedSchemaKey || '未命名项'}</h2>
        </div>
        <div className="candidate-inspector-heading-actions">
          <span className={`candidate-source source-${item.source.toLocaleLowerCase()}`}>
            {item.source === 'AI' ? <Bot aria-hidden="true" size={13} /> : <UserRound aria-hidden="true" size={13} />}{item.source}
          </span>
          {onClose && <button type="button" className="icon-button candidate-inspector-close" onClick={onClose} aria-label="关闭属性与证据"><X aria-hidden="true" size={17} /></button>}
        </div>
      </header>

      <section className="candidate-edit-section">
        <span className="inspector-kicker">最终定义</span>
        {removed && <p className="removed-note"><Trash2 aria-hidden="true" size={14} />此项已标记移除，仍保留在 Candidate 审计记录中。</p>}
        {field ? (
          <FieldEditor state={state} schema={schema} field={field} disabled={removed || readOnly} dispatch={dispatch} />
        ) : (
          <SchemaEditor schema={schema} disabled={removed || readOnly} dispatch={dispatch} />
        )}
      </section>

      <AssessmentPanel assessment={item.assessment} source={item.source} />
      {!readOnly && item.source === 'AI' && !removed && (
        <section className="candidate-resolution-actions" aria-label="逐项审核操作">
          <span>只处理当前项</span>
          <button
            type="button"
            className="confirm-candidate"
            disabled={field ? field.value.kind === 'UNRESOLVED' || field.value.kind === 'CONFLICT' : false}
            onClick={() => field
              ? dispatch({ type: 'resolve-field', schemaId: schema.candidateSchemaId, fieldId: field.candidateFieldId, resolution: 'CONFIRMED' })
              : dispatch({ type: 'resolve-schema', schemaId: schema.candidateSchemaId, resolution: 'CONFIRMED' })}
          >
            <Check aria-hidden="true" size={14} />确认当前项
          </button>
          {!rootSchema && (
            <button
              type="button"
              className="remove-candidate"
              onClick={() => field
                ? dispatch({ type: 'resolve-field', schemaId: schema.candidateSchemaId, fieldId: field.candidateFieldId, resolution: 'REMOVED' })
                : dispatch({ type: 'resolve-schema', schemaId: schema.candidateSchemaId, resolution: 'REMOVED' })}
            >
              <Trash2 aria-hidden="true" size={14} />移除当前项
            </button>
          )}
          <small>{rootSchema ? '根数据结构不可移除；可继续修改名称与 schemaKey。' : '没有“全部确认”；每次保存最多改变一个既有项的 resolution。'}</small>
        </section>
      )}
      {!readOnly && item.source === 'USER' && !removed && (
        <section className="candidate-resolution-actions">
          {rootSchema ? <small>根数据结构不可移除；可继续修改名称与 schemaKey。</small> : (
            <button
              type="button"
              className="remove-candidate"
              onClick={() => field
                ? dispatch({ type: 'resolve-field', schemaId: schema.candidateSchemaId, fieldId: field.candidateFieldId, resolution: 'REMOVED' })
                : dispatch({ type: 'resolve-schema', schemaId: schema.candidateSchemaId, resolution: 'REMOVED' })}
            ><Trash2 aria-hidden="true" size={14} />移除人工项</button>
          )}
        </section>
      )}
      {!readOnly && removed && (
        <section className="candidate-resolution-actions single-action" aria-label="恢复已移除项">
          <button
            type="button"
            className="confirm-candidate"
            onClick={() => {
              const resolution = originalResolution(state, itemId, item.source);
              if (field) dispatch({ type: 'resolve-field', schemaId: schema.candidateSchemaId, fieldId: field.candidateFieldId, resolution });
              else dispatch({ type: 'resolve-schema', schemaId: schema.candidateSchemaId, resolution });
            }}
          >恢复此项</button>
        </section>
      )}
      <EvidencePanel state={state} assessment={item.assessment} />
      <ProblemPanel problems={itemProblems} />
    </aside>
  );
}

function SchemaEditor({
  schema,
  disabled,
  dispatch,
}: {
  schema: CandidateSchema;
  disabled: boolean;
  dispatch: Dispatch<CandidateReviewAction>;
}) {
  return (
    <div className="candidate-editor-fields">
      <label>显示名称<input disabled={disabled} value={schema.displayName ?? ''} onChange={(event) => dispatch({ type: 'edit-schema', schemaId: schema.candidateSchemaId, patch: { displayName: event.target.value || null } })} /></label>
      <label>schemaKey<input disabled={disabled} value={schema.proposedSchemaKey ?? ''} spellCheck={false} onChange={(event) => dispatch({ type: 'edit-schema', schemaId: schema.candidateSchemaId, patch: { proposedSchemaKey: event.target.value || null } })} /></label>
    </div>
  );
}

function FieldEditor({
  state,
  schema,
  field,
  disabled,
  dispatch,
}: {
  state: CandidateReviewState;
  schema: CandidateSchema;
  field: CandidateField;
  disabled: boolean;
  dispatch: Dispatch<CandidateReviewAction>;
}) {
  const change = (patch: Partial<Pick<CandidateField, 'proposedFieldKey' | 'displayName' | 'required' | 'value'>>) =>
    dispatch({ type: 'edit-field', schemaId: schema.candidateSchemaId, fieldId: field.candidateFieldId, patch });
  const selectedKind = finalKinds.includes(field.value.kind as FinalCandidateKind) ? field.value.kind : '';
  const fieldIndex = schema.fields.findIndex((item) => item.candidateFieldId === field.candidateFieldId);
  return (
    <div className="candidate-editor-fields">
      <div className="candidate-field-order" role="group" aria-label={`${field.displayName || field.proposedFieldKey || '当前字段'} 排序`}>
        <span>字段顺序 <strong>{fieldIndex + 1} / {schema.fields.length}</strong></span>
        <button type="button" aria-label="上移当前字段" disabled={disabled || fieldIndex <= 0} onClick={() => dispatch({ type: 'move-field', schemaId: schema.candidateSchemaId, fieldId: field.candidateFieldId, direction: -1 })}><ArrowUp aria-hidden="true" size={14} />上移</button>
        <button type="button" aria-label="下移当前字段" disabled={disabled || fieldIndex < 0 || fieldIndex >= schema.fields.length - 1} onClick={() => dispatch({ type: 'move-field', schemaId: schema.candidateSchemaId, fieldId: field.candidateFieldId, direction: 1 })}><ArrowDown aria-hidden="true" size={14} />下移</button>
      </div>
      <label>显示名称<input disabled={disabled} value={field.displayName ?? ''} onChange={(event) => change({ displayName: event.target.value || null })} /></label>
      <label>fieldKey<input disabled={disabled} value={field.proposedFieldKey ?? ''} spellCheck={false} onChange={(event) => change({ proposedFieldKey: event.target.value || null })} /></label>
      <label>字段类型
        <select
          aria-label="Candidate 字段类型"
          disabled={disabled}
          value={selectedKind}
          onChange={(event) => change({ value: candidateValue(event.target.value as FinalCandidateKind, defaultCandidateTarget(state, schema.candidateSchemaId)) })}
        >
          {!selectedKind && <option value="" disabled>{candidateTypeLabels[field.value.kind]} · 请选择最终类型</option>}
          {finalKinds.map((kind) => <option key={kind} value={kind}>{candidateTypeLabels[kind]}</option>)}
        </select>
      </label>
      <label className="candidate-checkbox"><input disabled={disabled} type="checkbox" checked={field.required} onChange={(event) => change({ required: event.target.checked })} /><span>RootDocument 中必填</span></label>
      <CandidateConstraintEditor value={field.value} disabled={disabled} onChange={(value) => change({ value })} />
      {field.value.kind === 'ARRAY' && field.value.items && (
        <ArrayItemEditor state={state} schema={schema} field={field} disabled={disabled} change={change} />
      )}
      {field.value.kind === 'REFERENCE' && field.value.reference && (
        <ReferenceEditor state={state} schema={schema} value={field.value} disabled={disabled} onChange={(value) => change({ value })} />
      )}
    </div>
  );
}

function ArrayItemEditor({
  state,
  schema,
  field,
  disabled,
  change,
}: {
  state: CandidateReviewState;
  schema: CandidateSchema;
  field: CandidateField;
  disabled: boolean;
  change: (patch: Partial<Pick<CandidateField, 'value'>>) => void;
}) {
  const items = field.value.items!;
  const selectedKind = arrayItemKinds.includes(items.kind as ArrayItemCandidateKind) ? items.kind : '';
  const setItems = (nextItems: CandidateValue) => {
    const constraints = { ...field.value.constraints };
    if (nextItems.kind === 'REFERENCE') delete constraints.uniqueItems;
    change({ value: { ...field.value, items: nextItems, constraints } });
  };
  return (
    <div className="array-item-editor">
      <span>数组元素（禁止 Array&lt;Array&lt;T&gt;&gt;）</span>
      <label>元素类型
        <select
          aria-label="Candidate 数组元素类型"
          disabled={disabled}
          value={selectedKind}
          onChange={(event) => setItems(candidateValue(event.target.value as ArrayItemCandidateKind, defaultCandidateTarget(state, schema.candidateSchemaId)))}
        >
          {!selectedKind && <option value="" disabled>{candidateTypeLabels[items.kind]} · 请选择</option>}
          {arrayItemKinds.map((kind) => <option key={kind} value={kind}>{candidateTypeLabels[kind]}</option>)}
        </select>
      </label>
      {items.kind === 'REFERENCE' && items.reference && (
        <ReferenceEditor state={state} schema={schema} value={items} disabled={disabled} onChange={setItems} />
      )}
      <CandidateConstraintEditor value={items} disabled={disabled} compact onChange={setItems} />
    </div>
  );
}

function ReferenceEditor({
  state,
  schema,
  value,
  disabled,
  onChange,
}: {
  state: CandidateReviewState;
  schema: CandidateSchema;
  value: CandidateValue;
  disabled: boolean;
  onChange: (value: CandidateValue) => void;
}) {
  const reference = value.reference!;
  const setReference = (next: CandidateReference) => onChange({ ...value, reference: next });
  const referenceKind = reference.kind ?? 'CANDIDATE_SCHEMA';
  return (
    <div className="candidate-reference-editor">
      <label>引用范围
        <select
          disabled={disabled}
          value={referenceKind}
          onChange={(event) => setReference(referenceForKind(event.target.value as NonNullable<CandidateReference['kind']>, state, schema.candidateSchemaId))}
        >
          <option value="CANDIDATE_SCHEMA">本次 Candidate 包</option>
          <option value="DRAFT">现有 Draft</option>
          <option value="STATIC">StaticSchema</option>
        </select>
      </label>
      {referenceKind === 'CANDIDATE_SCHEMA' && (
        <label>目标 Schema
          <select disabled={disabled} value={reference.candidateSchemaId ?? ''} onChange={(event) => setReference({ kind: 'CANDIDATE_SCHEMA', candidateSchemaId: event.target.value || null, schemaKey: null, versionTag: null })}>
            <option value="">请选择</option>
            {state.draft.schemas.filter((item) => item.assessment.resolution !== 'REMOVED').map((item) => <option key={item.candidateSchemaId} value={item.candidateSchemaId}>{item.displayName || item.proposedSchemaKey || item.candidateSchemaId}{item.candidateSchemaId === schema.candidateSchemaId ? '（当前）' : ''}</option>)}
          </select>
        </label>
      )}
      {(referenceKind === 'DRAFT' || referenceKind === 'STATIC') && (
        <label>schemaKey<input disabled={disabled} value={reference.schemaKey ?? ''} spellCheck={false} onChange={(event) => setReference({ ...reference, kind: referenceKind, candidateSchemaId: null, schemaKey: event.target.value || null, versionTag: referenceKind === 'STATIC' ? reference.versionTag : null })} /></label>
      )}
      {referenceKind === 'STATIC' && (
        <label>versionTag<input disabled={disabled} value={reference.versionTag ?? ''} spellCheck={false} onChange={(event) => setReference({ ...reference, kind: 'STATIC', candidateSchemaId: null, versionTag: event.target.value || null })} /></label>
      )}
    </div>
  );
}

function CandidateConstraintEditor({
  value,
  disabled,
  compact = false,
  onChange,
}: {
  value: CandidateValue;
  disabled: boolean;
  compact?: boolean;
  onChange: (value: CandidateValue) => void;
}) {
  const idPrefix = useId();
  const definitions = constraintsFor(value);
  const known = new Set(definitions.map((definition) => definition.key));
  const unknown = Object.entries(value.constraints).filter(([key]) => !known.has(key));
  if (definitions.length === 0 && unknown.length === 0) return null;

  const setConstraint = (key: string, raw: string | null) => {
    const constraints = { ...value.constraints };
    if (raw === null) delete constraints[key];
    else constraints[key] = raw;
    onChange({ ...value, constraints });
  };

  return (
    <section className={`candidate-constraint-editor ${compact ? 'compact' : ''}`} aria-label={`${candidateTypeLabels[value.kind]}约束`}>
      <header>
        <div><strong>{compact ? '元素约束' : '字段约束'}</strong><span>仅启用需要固化到 Draft 的规则</span></div>
        <em>{Object.keys(value.constraints).length} 项</em>
      </header>
      <div className="candidate-constraint-grid">
        {definitions.map((definition) => {
          const enabled = Object.hasOwn(value.constraints, definition.key);
          const inputId = `${idPrefix}-${definition.key}`;
          return (
            <div className={`candidate-constraint-control ${enabled ? 'enabled' : ''}`} key={definition.key}>
              <label className="candidate-constraint-toggle">
                <input
                  type="checkbox"
                  aria-label={`启用${definition.label}`}
                  checked={enabled}
                  disabled={disabled}
                  onChange={(event) => setConstraint(
                    definition.key,
                    event.target.checked ? defaultConstraintLiteral(value.kind, definition.key) : null,
                  )}
                />
                <span><strong>{definition.label}</strong><small>{definition.help}</small></span>
              </label>
              {enabled && definition.control === 'textarea' && (
                <textarea
                  id={inputId}
                  aria-label={definition.label}
                  disabled={disabled}
                  rows={2}
                  spellCheck={false}
                  value={value.constraints[definition.key] ?? ''}
                  placeholder={definition.placeholder}
                  onChange={(event) => setConstraint(definition.key, event.target.value)}
                />
              )}
              {enabled && definition.control === 'boolean' && (
                <select
                  id={inputId}
                  aria-label={definition.label}
                  disabled={disabled}
                  value={value.constraints[definition.key] ?? 'true'}
                  onChange={(event) => setConstraint(definition.key, event.target.value)}
                ><option value="true">true</option><option value="false">false</option></select>
              )}
              {enabled && (!definition.control || definition.control === 'input') && (
                <input
                  id={inputId}
                  aria-label={definition.label}
                  disabled={disabled}
                  inputMode={definition.inputMode}
                  spellCheck={false}
                  value={value.constraints[definition.key] ?? ''}
                  placeholder={definition.placeholder}
                  onChange={(event) => setConstraint(definition.key, event.target.value)}
                />
              )}
            </div>
          );
        })}
      </div>
      {value.kind === 'ARRAY' && value.items?.kind === 'REFERENCE' && (
        <p className="candidate-constraint-note">对象数组不支持 uniqueItems；切换为引用元素时会自动移除该约束。</p>
      )}
      {unknown.length > 0 && (
        <div className="candidate-unknown-constraints" role="group" aria-label="未识别约束">
          <span>模型返回的未识别约束</span>
          {unknown.map(([key, raw]) => (
            <div key={key}><code>{key}</code><input aria-label={`约束 ${key}`} disabled={disabled} value={raw} onChange={(event) => setConstraint(key, event.target.value)} /><button type="button" disabled={disabled} aria-label={`移除约束 ${key}`} onClick={() => setConstraint(key, null)}><Trash2 aria-hidden="true" size={13} /></button></div>
          ))}
        </div>
      )}
    </section>
  );
}

function constraintsFor(value: CandidateValue): ConstraintDefinition[] {
  switch (value.kind) {
    case 'TEXT':
      return [
        { key: 'minLength', label: '最小长度', help: '允许 0–65536。', inputMode: 'numeric' },
        { key: 'maxLength', label: '最大长度', help: '允许 0–65536。', inputMode: 'numeric' },
        { key: 'pattern', label: '正则表达式', help: '使用受支持的安全正则语法。', placeholder: '^[A-Z0-9]+$' },
        { key: 'enum', label: '允许值', help: '使用 JSON 字符串数组。', control: 'textarea', placeholder: '["A","B"]' },
        { key: 'const', label: '固定值', help: '只允许一个确定文本。' },
      ];
    case 'DECIMAL':
      return [
        { key: 'min', label: '最小值', help: '包含边界。', inputMode: 'decimal' },
        { key: 'exclusiveMin', label: '大于', help: '不包含边界。', inputMode: 'decimal' },
        { key: 'max', label: '最大值', help: '包含边界。', inputMode: 'decimal' },
        { key: 'exclusiveMax', label: '小于', help: '不包含边界。', inputMode: 'decimal' },
        { key: 'multipleOf', label: '倍数', help: '必须是大于 0 的精确数值。', inputMode: 'decimal' },
        { key: 'enum', label: '允许值', help: '使用 JSON number 数组。', control: 'textarea', placeholder: '[0,1]' },
        { key: 'const', label: '固定值', help: '只允许一个精确数值。', inputMode: 'decimal' },
      ];
    case 'DATE':
      return orderedRangeConstraints;
    case 'TIME':
      return orderedRangeConstraints.map((definition) => ({
        ...definition,
        placeholder: definition.key === 'enum' ? '["08:30:00","17:30:00"]' : '16:32:00',
      }));
    case 'BOOLEAN':
      return [{ key: 'const', label: '固定值', help: '固定为 true 或 false。', control: 'boolean' }];
    case 'ARRAY':
      return [
        { key: 'minItems', label: '最少元素', help: '允许 0–10000。', inputMode: 'numeric' },
        { key: 'maxItems', label: '最多元素', help: '允许 0–10000。', inputMode: 'numeric' },
        ...(value.items?.kind !== 'REFERENCE' ? [{ key: 'uniqueItems', label: '元素唯一', help: '标量数组可启用。', control: 'boolean' as const }] : []),
      ];
    default:
      return [];
  }
}

function defaultConstraintLiteral(kind: CandidateValue['kind'], key: string): string {
  if (key === 'enum') {
    if (kind === 'DECIMAL') return '[0]';
    if (kind === 'DATE') return '["2026-01-01"]';
    if (kind === 'TIME') return '["00:00:00"]';
    return '["value"]';
  }
  if (key === 'uniqueItems' || (kind === 'BOOLEAN' && key === 'const')) return 'true';
  if (key === 'multipleOf') return '1';
  if (key === 'minLength' || key === 'maxLength' || key === 'minItems' || key === 'maxItems') return '0';
  if (kind === 'DECIMAL') return '0';
  if (kind === 'DATE') return '2026-01-01';
  if (kind === 'TIME') return '00:00:00';
  return key === 'pattern' ? '' : 'value';
}

function AssessmentPanel({ assessment, source }: { assessment: CandidateAssessment; source: 'AI' | 'USER' }) {
  const confidence = assessment.confidenceBps;
  return (
    <section className="assessment-panel">
      <div><span>审核状态</span><strong>{resolutionLabels[assessment.resolution]}</strong></div>
      <div><span>置信度</span><strong>{confidence === null ? '人工新增' : `${(confidence / 100).toFixed(2)}%`}</strong></div>
      <div><span>推断标记</span><strong>{assessment.inferred ? '是' : '否'}</strong></div>
      <p>{source === 'AI' ? <><Info aria-hidden="true" size={13} />置信度与证据只读，编辑不会改写 AI 来源。</> : <><UserRound aria-hidden="true" size={13} />人工项不附带伪造的 AI provenance。</>}</p>
    </section>
  );
}

function EvidencePanel({ state, assessment }: { state: CandidateReviewState; assessment: CandidateAssessment }) {
  const imageEvidence = assessment.evidence.filter((entry) => entry.kind === 'IMAGE' && entry.artifactId);
  const jsonEvidence = assessment.evidence.filter((entry) => entry.kind === 'JSON');
  const linkedImageIds = new Set(imageEvidence.map((entry) => entry.artifactId));
  const linkedImages = state.snapshot.images.filter((image) => linkedImageIds.has(image.artifactId));
  const [selectedImageId, setSelectedImageId] = useState<string | null>(linkedImages[0]?.artifactId ?? null);
  const selectedImage = linkedImages.find((image) => image.artifactId === selectedImageId) ?? linkedImages[0];
  return (
    <section className="candidate-evidence">
      <header><span>证据</span><strong>{assessment.evidence.length}</strong></header>
      {linkedImages.length > 1 && (
        <div className="evidence-image-tabs" role="tablist" aria-label="图片证据">
          {linkedImages.map((image) => (
            <button
              key={image.artifactId}
              type="button"
              role="tab"
              aria-selected={image.artifactId === selectedImage?.artifactId}
              aria-label={`查看证据图片 ${image.ordinal + 1}`}
              onClick={() => setSelectedImageId(image.artifactId)}
            >图片 {image.ordinal + 1}</button>
          ))}
        </div>
      )}
      {selectedImage && (
        <figure>
          <div className="evidence-image-stage">
            <img src={selectedImage.contentUrl} alt={`证据图片 ${selectedImage.ordinal + 1}`} />
            {imageEvidence.filter((entry) => entry.artifactId === selectedImage.artifactId && entry.boundingBox).map((entry, index) => {
              const box = entry.boundingBox!;
              return <i key={`${entry.artifactId}:${index}`} aria-hidden="true" data-evidence-box style={{ left: `${box.left / 100}%`, top: `${box.top / 100}%`, width: `${(box.right - box.left) / 100}%`, height: `${(box.bottom - box.top) / 100}%` }} />;
            })}
          </div>
          <figcaption><ImageIcon aria-hidden="true" size={13} />图片 {selectedImage.ordinal + 1} · {selectedImage.width}×{selectedImage.height}</figcaption>
        </figure>
      )}
      {jsonEvidence.length > 0 && <div className="json-evidence-list">{jsonEvidence.map((entry, index) => <div key={`${entry.sampleIndex}:${entry.jsonPointer}:${index}`}><FileJson2 aria-hidden="true" size={14} /><span>sample #{(entry.sampleIndex ?? 0) + 1}</span><code>{entry.jsonPointer || '/'}</code></div>)}</div>}
      {assessment.evidence.length === 0 && <p className="no-evidence">人工项没有 AI 证据。</p>}
    </section>
  );
}

function ProblemPanel({ problems }: { problems: CandidateProblem[] }) {
  return (
    <section className="candidate-item-problems">
      <header><span>当前项诊断</span><strong>{problems.length}</strong></header>
      {problems.length === 0 ? <p><Check aria-hidden="true" size={14} />当前项没有确定性问题</p> : (
        <ul>{problems.map((problem, index) => <li key={`${problem.code}:${problem.pointer}:${index}`} className={problem.severity.toLocaleLowerCase()}>{problem.severity === 'BLOCKER' ? <ShieldAlert aria-hidden="true" size={14} /> : <AlertTriangle aria-hidden="true" size={14} />}<span><strong>{problemLabel(problem.code)}</strong><code>{problem.code}</code><small>{problem.pointer}</small></span></li>)}</ul>
      )}
    </section>
  );
}

function defaultCandidateTarget(state: CandidateReviewState, currentSchemaId: string) {
  return state.draft.schemas.find((item) => item.candidateSchemaId !== currentSchemaId && item.assessment.resolution !== 'REMOVED')?.candidateSchemaId
    ?? state.draft.schemas.find((item) => item.assessment.resolution !== 'REMOVED')?.candidateSchemaId;
}

function originalResolution(
  state: CandidateReviewState,
  itemId: string,
  source: 'AI' | 'USER',
): import('../../api/generated').CandidateResolution {
  for (const schema of state.snapshot.original.schemas) {
    if (schema.candidateSchemaId === itemId) return schema.assessment.resolution;
    const field = schema.fields.find((candidate) => candidate.candidateFieldId === itemId);
    if (field) return field.assessment.resolution;
  }
  return source === 'USER' ? 'NOT_REQUIRED' : 'UNRESOLVED';
}

function referenceForKind(
  kind: NonNullable<CandidateReference['kind']>,
  state: CandidateReviewState,
  currentSchemaId: string,
): CandidateReference {
  if (kind === 'CANDIDATE_SCHEMA') return { kind, candidateSchemaId: defaultCandidateTarget(state, currentSchemaId) ?? null, schemaKey: null, versionTag: null };
  if (kind === 'DRAFT') return { kind, candidateSchemaId: null, schemaKey: null, versionTag: null };
  return { kind, candidateSchemaId: null, schemaKey: null, versionTag: null };
}
