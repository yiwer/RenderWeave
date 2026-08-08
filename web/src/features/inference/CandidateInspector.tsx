import {
  AlertTriangle,
  Bot,
  Check,
  FileJson2,
  Image as ImageIcon,
  Info,
  ShieldAlert,
  Trash2,
  UserRound,
} from 'lucide-react';
import type { Dispatch } from 'react';

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

export function CandidateInspector({
  state,
  schema,
  field,
  dispatch,
}: {
  state: CandidateReviewState;
  schema: CandidateSchema;
  field: CandidateField | null;
  dispatch: Dispatch<CandidateReviewAction>;
}) {
  const item = field ?? schema;
  const itemId = field?.candidateFieldId ?? schema.candidateSchemaId;
  const itemProblems = state.snapshot.problems.filter((problem) => problem.itemId === itemId);
  const removed = item.assessment.resolution === 'REMOVED';
  return (
    <aside className="candidate-inspector" aria-label="Candidate 属性与证据">
      <header>
        <div>
          <span>{field ? 'FIELD REVIEW' : 'SCHEMA REVIEW'}</span>
          <h2>{field?.displayName || field?.proposedFieldKey || schema.displayName || schema.proposedSchemaKey || '未命名项'}</h2>
        </div>
        <span className={`candidate-source source-${item.source.toLocaleLowerCase()}`}>
          {item.source === 'AI' ? <Bot aria-hidden="true" size={13} /> : <UserRound aria-hidden="true" size={13} />}{item.source}
        </span>
      </header>

      <section className="candidate-edit-section">
        <span className="inspector-kicker">最终定义</span>
        {removed && <p className="removed-note"><Trash2 aria-hidden="true" size={14} />此项已标记移除，仍保留在 Candidate 审计记录中。</p>}
        {field ? (
          <FieldEditor state={state} schema={schema} field={field} disabled={removed} dispatch={dispatch} />
        ) : (
          <SchemaEditor schema={schema} disabled={removed} dispatch={dispatch} />
        )}
      </section>

      <AssessmentPanel assessment={item.assessment} source={item.source} />
      {item.source === 'AI' && !removed && (
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
          <button
            type="button"
            className="remove-candidate"
            onClick={() => field
              ? dispatch({ type: 'resolve-field', schemaId: schema.candidateSchemaId, fieldId: field.candidateFieldId, resolution: 'REMOVED' })
              : dispatch({ type: 'resolve-schema', schemaId: schema.candidateSchemaId, resolution: 'REMOVED' })}
          >
            <Trash2 aria-hidden="true" size={14} />移除当前项
          </button>
          <small>没有“全部确认”；每次保存最多改变一个既有项的 resolution。</small>
        </section>
      )}
      {item.source === 'USER' && !removed && (
        <section className="candidate-resolution-actions">
          <button
            type="button"
            className="remove-candidate"
            onClick={() => field
              ? dispatch({ type: 'resolve-field', schemaId: schema.candidateSchemaId, fieldId: field.candidateFieldId, resolution: 'REMOVED' })
              : dispatch({ type: 'resolve-schema', schemaId: schema.candidateSchemaId, resolution: 'REMOVED' })}
          ><Trash2 aria-hidden="true" size={14} />移除人工项</button>
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
  return (
    <div className="candidate-editor-fields">
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
      {field.value.kind === 'ARRAY' && field.value.items && (
        <ArrayItemEditor state={state} schema={schema} field={field} disabled={disabled} change={change} />
      )}
      {field.value.kind === 'REFERENCE' && field.value.reference && (
        <ReferenceEditor state={state} schema={schema} value={field.value} disabled={disabled} onChange={(value) => change({ value })} />
      )}
      {Object.keys(field.value.constraints).length > 0 && (
        <div className="candidate-constraints"><span>推断约束</span>{Object.entries(field.value.constraints).map(([key, value]) => <code key={key}>{key}: {value}</code>)}</div>
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
  const setItems = (nextItems: CandidateValue) => change({ value: { ...field.value, items: nextItems } });
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
  const selectedImage = state.snapshot.images.find((image) => image.artifactId === imageEvidence[0]?.artifactId);
  return (
    <section className="candidate-evidence">
      <header><span>证据</span><strong>{assessment.evidence.length}</strong></header>
      {selectedImage && (
        <figure>
          <div className="evidence-image-stage">
            <img src={selectedImage.contentUrl} alt={`证据图片 ${selectedImage.ordinal + 1}`} />
            {imageEvidence.filter((entry) => entry.artifactId === selectedImage.artifactId && entry.boundingBox).map((entry, index) => {
              const box = entry.boundingBox!;
              return <i key={`${entry.artifactId}:${index}`} aria-hidden="true" data-evidence-box style={{ left: `${box.left / 100}%`, top: `${box.top / 100}%`, width: `${(box.right - box.left) / 100}%`, height: `${(box.bottom - box.top) / 100}%` }} />;
            })}
          </div>
          <figcaption><ImageIcon aria-hidden="true" size={13} />image {selectedImage.ordinal + 1} · {selectedImage.width}×{selectedImage.height}</figcaption>
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

function referenceForKind(
  kind: NonNullable<CandidateReference['kind']>,
  state: CandidateReviewState,
  currentSchemaId: string,
): CandidateReference {
  if (kind === 'CANDIDATE_SCHEMA') return { kind, candidateSchemaId: defaultCandidateTarget(state, currentSchemaId) ?? null, schemaKey: null, versionTag: null };
  if (kind === 'DRAFT') return { kind, candidateSchemaId: null, schemaKey: null, versionTag: null };
  return { kind, candidateSchemaId: null, schemaKey: null, versionTag: null };
}
