import {
  Background,
  Controls,
  MarkerType,
  Position,
  ReactFlow,
  type Edge,
  type Node,
} from '@xyflow/react';
import {
  AlertCircle,
  ArrowDown,
  ArrowUp,
  Bot,
  CheckCircle2,
  ChevronRight,
  GitBranch,
  ListFilter,
  Plus,
  UserRound,
} from 'lucide-react';
import { useMemo, type Dispatch } from 'react';

import type { CandidateField, CandidateProblem, CandidateSchema } from '../../api/generated';
import {
  newUserField,
  newUserSchema,
  nextCandidateKey,
  type CandidateReviewAction,
  type CandidateReviewState,
} from './candidate-session';
import { candidateTypeLabels, resolutionLabels, summarizeValue } from './candidate-format';

export function CandidateBundleNav({
  state,
  dispatch,
}: {
  state: CandidateReviewState;
  dispatch: Dispatch<CandidateReviewAction>;
}) {
  return (
    <aside className="candidate-bundle-nav" aria-label="Candidate Schema 包">
      <header>
        <span>候选数据结构</span><strong>{state.draft.schemas.length}</strong>
        <button
          type="button"
          className="candidate-add-schema"
          onClick={() => dispatch({
            type: 'add-schema',
            schema: newUserSchema(nextCandidateKey('new-schema', state.draft.schemas.map((item) => item.proposedSchemaKey))),
          })}
        ><Plus aria-hidden="true" size={14} />新增</button>
      </header>
      <div className="bundle-schema-list">
        {state.draft.schemas.map((schema, index) => {
          const problems = problemsForSchema(schema, state.snapshot.problems);
          const isRoot = schema.candidateSchemaId === state.draft.rootCandidateSchemaId;
          const label = schema.displayName || schema.proposedSchemaKey || '未命名 Schema';
          return (
            <div className="bundle-schema-entry" key={schema.candidateSchemaId}>
              <button
                type="button"
                className={`bundle-schema-select ${schema.candidateSchemaId === state.selectedSchemaId ? 'active' : ''} ${schema.assessment.resolution === 'REMOVED' ? 'removed' : ''}`}
                onClick={() => dispatch({ type: 'select-schema', schemaId: schema.candidateSchemaId })}
              >
                <span className="bundle-index">{String(index + 1).padStart(2, '0')}</span>
                <span><strong>{label}</strong><code>{schema.proposedSchemaKey || 'schemaKey 待填写'}</code></span>
                {isRoot && <i>根</i>}
                {problems > 0 ? <b>{problems}</b> : <CheckCircle2 aria-hidden="true" size={14} />}
              </button>
              <div className="bundle-order-actions" aria-label={`${label} 排序`}>
                <button type="button" aria-label={`上移 ${label}`} disabled={index === 0} onClick={() => dispatch({ type: 'move-schema', schemaId: schema.candidateSchemaId, direction: -1 })}><ArrowUp aria-hidden="true" size={13} /></button>
                <button type="button" aria-label={`下移 ${label}`} disabled={index === state.draft.schemas.length - 1} onClick={() => dispatch({ type: 'move-schema', schemaId: schema.candidateSchemaId, direction: 1 })}><ArrowDown aria-hidden="true" size={13} /></button>
              </div>
            </div>
          );
        })}
      </div>
      <div className="bundle-legend">
        <span><Bot aria-hidden="true" size={13} />AI 建议保留原始证据</span>
        <span><UserRound aria-hidden="true" size={13} />人工新增不伪造置信度</span>
      </div>
    </aside>
  );
}

export function CandidateSurface({
  state,
  schema,
  dispatch,
}: {
  state: CandidateReviewState;
  schema: CandidateSchema;
  dispatch: Dispatch<CandidateReviewAction>;
}) {
  if (state.view === 'map') {
    return <CandidateMap state={state} schema={schema} dispatch={dispatch} />;
  }
  const search = state.search.trim().toLocaleLowerCase('zh-CN');
  const fields = schema.fields.filter((field) => matches(field, search));
  return (
    <section className="candidate-form-surface" aria-label={`${schema.displayName ?? 'Schema'} 字段表单`}>
      {fields.map((field, index) => {
        const problemCount = state.snapshot.problems.filter((problem) => problem.itemId === field.candidateFieldId).length;
        const confidence = field.assessment.confidenceBps;
        return (
          <button
            type="button"
            key={field.candidateFieldId}
            className={`candidate-field-row ${field.candidateFieldId === state.selectedFieldId ? 'selected' : ''} ${field.assessment.resolution === 'REMOVED' ? 'removed' : ''}`}
            onClick={() => dispatch({ type: 'select-field', schemaId: schema.candidateSchemaId, fieldId: field.candidateFieldId })}
          >
            <span className={`candidate-type-mark type-${field.value.kind.toLocaleLowerCase()}`}>{candidateTypeLabels[field.value.kind].slice(0, 1)}</span>
            <span className="candidate-field-identity">
              <strong>{field.displayName || field.proposedFieldKey || `未命名字段 ${index + 1}`}</strong>
              <code>{field.proposedFieldKey || 'fieldKey 待填写'}</code>
            </span>
            <span className="candidate-field-shape">
              <b>{candidateTypeLabels[field.value.kind]}</b>
              <small>{summarizeValue(field.value, state.draft.schemas)}</small>
            </span>
            <span className={`candidate-source source-${field.source.toLocaleLowerCase()}`}>
              {field.source === 'AI' ? <Bot aria-hidden="true" size={13} /> : <UserRound aria-hidden="true" size={13} />}
              {field.source}
            </span>
            <span className="candidate-confidence">
              <i><em style={{ width: `${confidence === null ? 0 : confidence / 100}%` }} /></i>
              <small>{confidence === null ? '人工' : `${(confidence / 100).toFixed(0)}%`}</small>
            </span>
            <span className={`candidate-resolution resolution-${field.assessment.resolution.toLocaleLowerCase()}`}>
              {resolutionLabels[field.assessment.resolution]}
            </span>
            <span className={problemCount > 0 ? 'candidate-problem-count' : 'candidate-ok'}>
              {problemCount > 0 ? <><AlertCircle aria-hidden="true" size={13} />{problemCount}</> : <CheckCircle2 aria-hidden="true" size={14} />}
            </span>
            <ChevronRight aria-hidden="true" size={15} />
          </button>
        );
      })}
      {fields.length === 0 && (
        <div className="candidate-empty" role="status">
          <ListFilter aria-hidden="true" size={20} />
          <strong>{schema.fields.length === 0 ? '此 Schema 尚无字段' : '没有匹配字段'}</strong>
          <span>{schema.fields.length === 0 ? '可新增一个人工字段。' : '清除搜索词查看全部字段。'}</span>
        </div>
      )}
      <button
        type="button"
        className="candidate-add-field"
        onClick={() => dispatch({
          type: 'add-field',
          schemaId: schema.candidateSchemaId,
          field: newUserField(nextCandidateKey('new-field', schema.fields.map((item) => item.proposedFieldKey))),
        })}
      >
        <Plus aria-hidden="true" size={15} />新增人工字段
      </button>
    </section>
  );
}

function CandidateMap({
  state,
  schema,
  dispatch,
}: {
  state: CandidateReviewState;
  schema: CandidateSchema;
  dispatch: Dispatch<CandidateReviewAction>;
}) {
  const search = state.search.trim().toLocaleLowerCase('zh-CN');
  const nodes = useMemo<Node[]>(() => {
    const rootY = Math.max(70, schema.fields.length * 44);
    const fieldNodes: Node[] = schema.fields.map((field, index) => ({
      id: field.candidateFieldId,
      position: { x: 290, y: index * 88 + 42 },
      data: { label: <MapNode field={field} index={index} /> },
      className: `candidate-map-node type-border-${field.value.kind.toLocaleLowerCase()} ${field.candidateFieldId === state.selectedFieldId ? 'selected' : ''} ${state.snapshot.problems.some((problem) => problem.itemId === field.candidateFieldId) ? 'problem' : ''} ${field.assessment.resolution === 'REMOVED' ? 'removed' : ''} ${matches(field, search) ? '' : 'dimmed'}`,
      draggable: false,
      sourcePosition: Position.Right,
      targetPosition: Position.Left,
      ariaLabel: `${field.displayName || field.proposedFieldKey || `字段 ${index + 1}`}，${candidateTypeLabels[field.value.kind]}`,
    }));
    const detailNodes: Node[] = schema.fields.flatMap((field, index) => {
      if (field.value.kind !== 'ARRAY' && field.value.kind !== 'REFERENCE') return [];
      return [{
        id: `detail:${field.candidateFieldId}`,
        position: { x: 555, y: index * 88 + 48 },
        data: { label: summarizeValue(field.value, state.draft.schemas) },
        className: `candidate-map-node detail ${matches(field, search) ? '' : 'dimmed'}`,
        draggable: false,
        targetPosition: Position.Left,
      }];
    });
    return [{
      id: 'root',
      position: { x: 28, y: rootY },
      data: { label: <div className="candidate-map-node-content"><span>根节点</span><strong>{schema.displayName || '未命名 Schema'}</strong><code>{schema.proposedSchemaKey || 'schemaKey 待填写'}</code></div> },
      className: 'candidate-map-node root',
      draggable: false,
      sourcePosition: Position.Right,
    }, ...fieldNodes, ...detailNodes];
  }, [schema, search, state.draft.schemas, state.selectedFieldId, state.snapshot.problems]);
  const edges = useMemo<Edge[]>(() => [
    ...schema.fields.map((field) => ({
      id: `root:${field.candidateFieldId}`,
      source: 'root',
      target: field.candidateFieldId,
      markerEnd: { type: MarkerType.ArrowClosed, width: 13, height: 13 },
      style: { stroke: field.required ? 'var(--primary)' : 'var(--hairline-strong)', strokeWidth: field.required ? 1.7 : 1.1 },
    })),
    ...schema.fields.filter((field) => field.value.kind === 'ARRAY' || field.value.kind === 'REFERENCE').map((field) => ({
      id: `detail-edge:${field.candidateFieldId}`,
      source: field.candidateFieldId,
      target: `detail:${field.candidateFieldId}`,
      markerEnd: { type: MarkerType.ArrowClosed, width: 11, height: 11 },
      style: { stroke: '#aaa198', strokeDasharray: '4 3' },
    })),
  ], [schema.fields]);

  return (
    <section className="candidate-map-surface" aria-label="Candidate 一层树状图">
      <ReactFlow
        nodes={nodes}
        edges={edges}
        fitView
        fitViewOptions={{ padding: 0.16, maxZoom: 1.05 }}
        minZoom={0.45}
        maxZoom={1.25}
        nodesConnectable={false}
        onNodeClick={(_, node) => {
          const fieldId = node.id.startsWith('detail:') ? node.id.slice(7) : node.id;
          if (fieldId !== 'root') dispatch({ type: 'select-field', schemaId: schema.candidateSchemaId, fieldId });
        }}
        proOptions={{ hideAttribution: true }}
      >
        <Background color="#ded7cd" gap={22} size={1} />
        <Controls showInteractive={false} position="bottom-left" />
      </ReactFlow>
      <div className="candidate-map-legend"><GitBranch aria-hidden="true" size={14} />树图与表单共享顺序；使用上移、下移完成键盘排序。</div>
    </section>
  );
}

function MapNode({ field, index }: { field: CandidateField; index: number }) {
  return <div className="candidate-map-node-content"><span>{candidateTypeLabels[field.value.kind]} · {field.required ? '必填' : '可选'}</span><strong>{field.displayName || field.proposedFieldKey || `字段 ${index + 1}`}</strong><code>{field.proposedFieldKey || 'fieldKey 待填写'}</code></div>;
}

function matches(field: CandidateField, search: string) {
  if (!search) return true;
  return `${field.displayName ?? ''} ${field.proposedFieldKey ?? ''} ${candidateTypeLabels[field.value.kind]}`
    .toLocaleLowerCase('zh-CN')
    .includes(search);
}

function problemsForSchema(schema: CandidateSchema, problems: CandidateProblem[]) {
  const ids = new Set([schema.candidateSchemaId, ...schema.fields.map((field) => field.candidateFieldId)]);
  return problems.filter((problem) => problem.itemId !== null && ids.has(problem.itemId)).length;
}
