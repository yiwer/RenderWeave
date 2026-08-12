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
  CheckCircle2,
  Copy,
  ListFilter,
  Plus,
  Trash2,
} from 'lucide-react';
import { useMemo, type Dispatch } from 'react';

import type { EditorAction, EditorSession } from './editor-session';
import {
  editorTypeLabels,
  summarizeEditorValue,
  type EditorField,
} from './editor-types';
import type { EditorDiagnostic } from './editor-validation';

interface EditorSurfaceProps {
  session: EditorSession;
  diagnostics: EditorDiagnostic[];
  search: string;
  dispatch: Dispatch<EditorAction>;
  onSelectField: (rowKey: string) => void;
  onAddField: () => void;
}

export function FormSurface({
  session,
  diagnostics,
  search,
  dispatch,
  onSelectField,
  onAddField,
}: EditorSurfaceProps) {
  const normalizedSearch = search.trim().toLocaleLowerCase('zh-CN');
  const visibleFields = session.fields
    .map((field, index) => ({ field, index }))
    .filter(({ field }) => matchesSearch(field, normalizedSearch));

  return (
    <section className="studio-field-list" aria-label="Schema 字段表单">
      {visibleFields.map(({ field, index }) => (
        <FieldRow
          key={field.rowKey}
          field={field}
          index={index}
          total={session.fields.length}
          selected={field.rowKey === session.selectedRowKey}
          problemCount={diagnostics.filter((item) => item.rowKey === field.rowKey).length}
          dispatch={dispatch}
          onSelect={() => onSelectField(field.rowKey)}
        />
      ))}
      {visibleFields.length === 0 && (
        <div className="studio-empty-search" role="status">
          <ListFilter aria-hidden="true" size={20} />
          <strong>{session.fields.length === 0 ? 'Schema 暂无字段' : '没有匹配字段'}</strong>
          <span>{session.fields.length === 0 ? '空定义可以直接保存，也可以添加第一个字段。' : '清除搜索词即可返回完整字段列表。'}</span>
        </div>
      )}
      <button
        type="button"
        className="add-field-button studio-add-field"
        disabled={session.fields.length >= 256}
        onClick={onAddField}
      >
        <Plus aria-hidden="true" size={17} />
        {session.fields.length >= 256 ? '已达到 256 个字段上限' : '添加字段'}
      </button>
    </section>
  );
}

interface FieldRowProps {
  field: EditorField;
  index: number;
  total: number;
  selected: boolean;
  problemCount: number;
  dispatch: Dispatch<EditorAction>;
  onSelect: () => void;
}

function FieldRow({ field, index, total, selected, problemCount, dispatch, onSelect }: FieldRowProps) {
  const label = field.displayName.trim() || field.fieldKey || `未命名字段 ${index + 1}`;
  return (
    <article className={`field-row studio-field-row ${selected ? 'selected' : ''}`}>
      <button type="button" className="field-select" onClick={onSelect} aria-label={`编辑字段 ${label}`}>
        <span className={`type-dot type-${field.value.type}`} aria-hidden="true" />
        <span className="field-identity">
          <strong>{label}</strong>
          <code>{field.fieldKey || '等待填写 fieldKey'}</code>
        </span>
        <span className="type-chip">{editorTypeLabels[field.value.type]}</span>
        <span className="field-detail">{summarizeEditorValue(field.value)}</span>
        {problemCount > 0 ? (
          <span className="problem-chip"><AlertCircle aria-hidden="true" size={13} />{problemCount} 项</span>
        ) : (
          <span className="ok-label"><CheckCircle2 aria-hidden="true" size={14} />有效</span>
        )}
      </button>
      <button
        type="button"
        className={`required-toggle ${field.required ? 'active' : ''}`}
        aria-pressed={field.required}
        aria-label={`${label}设为${field.required ? '可选' : '必填'}`}
        onClick={() => dispatch({
          type: 'update-field', rowKey: field.rowKey, patch: { required: !field.required },
        })}
      >
        {field.required ? '必填' : '可选'}
      </button>
      <div className="field-row-actions" aria-label={`${label} 操作`}>
        <button
          type="button"
          aria-label={`复制 ${label}`}
          title="复制字段"
          disabled={total >= 256}
          onClick={() => dispatch({ type: 'duplicate-field', rowKey: field.rowKey })}
        >
          <Copy aria-hidden="true" size={14} />
        </button>
        <button
          type="button"
          aria-label={`删除 ${label}`}
          title="删除字段"
          onClick={() => dispatch({ type: 'delete-field', rowKey: field.rowKey })}
        >
          <Trash2 aria-hidden="true" size={14} />
        </button>
      </div>
      <div className="reorder-actions" aria-label={`${label} 排序`}>
        <button
          type="button"
          aria-label={`上移 ${label}`}
          disabled={index === 0}
          onClick={() => dispatch({ type: 'move-field', rowKey: field.rowKey, direction: -1 })}
        >
          <ArrowUp aria-hidden="true" size={15} />
        </button>
        <button
          type="button"
          aria-label={`下移 ${label}`}
          disabled={index === total - 1}
          onClick={() => dispatch({ type: 'move-field', rowKey: field.rowKey, direction: 1 })}
        >
          <ArrowDown aria-hidden="true" size={15} />
        </button>
      </div>
    </article>
  );
}

export function MapSurface({
  session,
  diagnostics,
  search,
  dispatch,
  onSelectField,
  onAddField,
}: EditorSurfaceProps) {
  const normalizedSearch = search.trim().toLocaleLowerCase('zh-CN');
  const nodes = useMemo<Node[]>(() => {
    const rootY = Math.max(72, (session.fields.length - 1) * 48 + 42);
    const fieldNodes: Node[] = session.fields.map((field, index) => {
      const dimmed = !matchesSearch(field, normalizedSearch);
      return {
        id: field.rowKey,
        position: { x: 300, y: index * 96 + 54 },
        data: {
          label: (
            <div className="map-node-content">
              <span>{editorTypeLabels[field.value.type]}{field.required ? ' · 必填' : ' · 可选'}</span>
              <strong>{field.displayName || field.fieldKey || `字段 ${index + 1}`}</strong>
              <code>{field.fieldKey || '未填写 fieldKey'}</code>
            </div>
          ),
        },
        className: `map-node studio-map-node type-border-${field.value.type} ${field.rowKey === session.selectedRowKey ? 'map-selected-node' : ''} ${diagnostics.some((item) => item.rowKey === field.rowKey) ? 'map-problem-node' : ''} ${dimmed ? 'map-dimmed-node' : ''}`,
        draggable: true,
        sourcePosition: Position.Right,
        targetPosition: Position.Left,
        ariaLabel: `${field.displayName || field.fieldKey || `字段 ${index + 1}`}，${editorTypeLabels[field.value.type]}，${field.required ? '必填' : '可选'}；拖动可排序`,
      };
    });
    const detailNodes: Node[] = session.fields.flatMap((field, index) => {
      const value = field.value;
      if (value.type !== 'reference' && value.type !== 'array') return [];
      const label = value.type === 'reference'
        ? `${value.referenceKind === 'static' ? 'StaticSchemaRef' : 'SchemaRef'}\n${value.schemaKey || '未选择目标'}${value.referenceKind === 'static' ? `@${value.versionTag || '?'}` : ''}`
        : `items · ${editorTypeLabels[value.items.type]}\n${summarizeEditorValue(value.items)}`;
      return [{
        id: `detail:${field.rowKey}`,
        position: { x: 570, y: index * 96 + 62 },
        data: { label },
        className: `map-node map-detail-node ${!matchesSearch(field, normalizedSearch) ? 'map-dimmed-node' : ''}`,
        draggable: false,
        targetPosition: Position.Left,
        ariaLabel: `${field.fieldKey || `字段 ${index + 1}`} 的${value.type === 'array' ? '数组元素' : '引用'}摘要`,
      }];
    });
    return [
      {
        id: 'root',
        position: { x: 34, y: rootY },
        data: {
          label: (
            <div className="map-node-content">
              <span>根节点</span>
              <strong>{session.displayName || '未命名 Schema'}</strong>
              <code>{session.schemaKey || '等待填写 schemaKey'}</code>
            </div>
          ),
        },
        className: 'map-node map-root-node studio-map-node',
        draggable: false,
        sourcePosition: Position.Right,
        ariaLabel: `Schema 根节点 ${session.displayName || '未命名'}`,
      },
      ...fieldNodes,
      ...detailNodes,
    ];
  }, [diagnostics, normalizedSearch, session.displayName, session.fields, session.schemaKey, session.selectedRowKey]);

  const edges = useMemo<Edge[]>(() => [
    ...session.fields.map((field) => ({
      id: `root-${field.rowKey}`,
      source: 'root',
      target: field.rowKey,
      markerEnd: { type: MarkerType.ArrowClosed, width: 14, height: 14 },
      style: {
        stroke: field.required ? 'var(--primary)' : 'var(--hairline-strong)',
        strokeWidth: field.required ? 1.8 : 1.2,
      },
    })),
    ...session.fields
      .filter((field) => field.value.type === 'array' || field.value.type === 'reference')
      .map((field) => ({
        id: `${field.rowKey}-detail`,
        source: field.rowKey,
        target: `detail:${field.rowKey}`,
        markerEnd: { type: MarkerType.ArrowClosed, width: 12, height: 12 },
        style: { stroke: 'var(--color-map-edge)', strokeDasharray: '4 3', strokeWidth: 1.1 },
      })),
  ], [session.fields]);

  return (
    <section className="studio-map" aria-label="Schema 一层树状图；完整键盘编辑可切回表单模式">
      <ReactFlow
        key={session.fields.length}
        nodes={nodes}
        edges={edges}
        fitView
        fitViewOptions={{ padding: 0.18, maxZoom: 1.05 }}
        minZoom={0.48}
        maxZoom={1.3}
        nodesConnectable={false}
        elementsSelectable
        onNodeClick={(_, node) => {
          const rowKey = node.id.startsWith('detail:') ? node.id.slice('detail:'.length) : node.id;
          if (rowKey !== 'root') onSelectField(rowKey);
        }}
        onNodeDragStop={(_, node) => {
          if (node.id === 'root' || node.id.startsWith('detail:')) return;
          const targetIndex = Math.max(0, Math.min(session.fields.length - 1, Math.round((node.position.y - 54) / 96)));
          dispatch({ type: 'move-field-to', rowKey: node.id, targetIndex });
        }}
        proOptions={{ hideAttribution: true }}
      >
        <Background color="var(--color-map-grid)" gap={22} size={1} />
        <Controls showInteractive={false} position="bottom-left" />
      </ReactFlow>
      <div className="map-legend studio-map-legend">
        <span><i className="legend-required" />必填</span>
        <span><i />可选</span>
        <span><AlertCircle aria-hidden="true" size={13} />红色描边表示待修正</span>
      </div>
      <button
        type="button"
        className="button ghost-button studio-map-add"
        disabled={session.fields.length >= 256}
        onClick={onAddField}
      >
        <Plus aria-hidden="true" size={16} />
        添加字段
      </button>
      <div className="studio-map-status" role="status">
        {diagnostics.length === 0 ? (
          <><CheckCircle2 aria-hidden="true" size={16} /><strong>本地规则通过</strong><span>{session.fields.length} 个字段 · 拖动节点可排序</span></>
        ) : (
          <><AlertCircle aria-hidden="true" size={16} /><strong>{diagnostics.length} 项待修正</strong><span>保存前必须处理</span></>
        )}
      </div>
      <p className="sr-only">树状图只负责结构浏览和拖动排序；切换到表单模式可用键盘完成所有操作。</p>
    </section>
  );
}

function matchesSearch(field: EditorField, normalizedSearch: string): boolean {
  if (!normalizedSearch) return true;
  return `${field.fieldKey} ${field.displayName} ${field.description} ${editorTypeLabels[field.value.type]}`
    .toLocaleLowerCase('zh-CN')
    .includes(normalizedSearch);
}
