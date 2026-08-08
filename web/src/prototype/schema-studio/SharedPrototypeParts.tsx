import {
  ArrowDown,
  ArrowUp,
  Braces,
  Check,
  FileClock,
  Network,
  Plus,
  Save,
  Sparkles,
} from 'lucide-react';
import type { Dispatch } from 'react';

import {
  fieldTypeLabels,
  type PrototypeEditorAction,
  type PrototypeEditorState,
  type SchemaField,
} from './model';

interface ChromeProps {
  state: PrototypeEditorState;
  dispatch: Dispatch<PrototypeEditorAction>;
  layoutName: string;
}

export function ProductChrome({ state, dispatch, layoutName }: ChromeProps) {
  return (
    <header className="product-chrome">
      <div className="product-mark" aria-label="RenderWeave">
        <span className="weave-mark" aria-hidden="true">RW</span>
        <span>RenderWeave</span>
      </div>
      <div className="chrome-context">
        <span className="prototype-kicker">原型</span>
        <span>{layoutName}</span>
      </div>
      <div className="chrome-actions">
        <span className={state.dirty ? 'status-chip status-dirty' : 'status-chip'}>
          {state.dirty ? '有未保存更改' : '已保存'}
        </span>
        <button type="button" className="button ghost-button">
          <FileClock aria-hidden="true" size={16} />
          历史
        </button>
        <button type="button" className="button primary-button" onClick={() => dispatch({ type: 'save' })}>
          <Save aria-hidden="true" size={16} />
          保存（模拟）
        </button>
      </div>
    </header>
  );
}

export function ResourceRail() {
  return (
    <nav className="resource-rail" aria-label="v1 主导航">
      <div className="rail-section-label">定义</div>
      <a className="rail-link active" href="#drafts" aria-current="page">
        <Braces aria-hidden="true" size={17} />
        Schema Draft
        <span>12</span>
      </a>
      <a className="rail-link" href="#static">
        <Check aria-hidden="true" size={17} />
        StaticSchema
        <span>28</span>
      </a>
      <a className="rail-link" href="#inference">
        <Sparkles aria-hidden="true" size={17} />
        AI 推断
        <span>3</span>
      </a>
      <a className="rail-link" href="#validator">
        <Network aria-hidden="true" size={17} />
        样本验证
      </a>
      <div className="rail-divider" />
      <div className="rail-context-card">
        <span>当前工作定义</span>
        <strong>campaign-card</strong>
        <small>revision 7 · 6 fields</small>
      </div>
    </nav>
  );
}

interface ViewToggleProps {
  state: PrototypeEditorState;
  dispatch: Dispatch<PrototypeEditorAction>;
}

export function ViewToggle({ state, dispatch }: ViewToggleProps) {
  return (
    <div className="view-toggle" aria-label="编辑模式">
      <button
        type="button"
        className={state.view === 'form' ? 'active' : ''}
        aria-pressed={state.view === 'form'}
        onClick={() => dispatch({ type: 'set-view', view: 'form' })}
      >
        表单
      </button>
      <button
        type="button"
        className={state.view === 'map' ? 'active' : ''}
        aria-pressed={state.view === 'map'}
        onClick={() => dispatch({ type: 'set-view', view: 'map' })}
      >
        树状图
      </button>
    </div>
  );
}

interface FieldRowProps {
  field: SchemaField;
  selected: boolean;
  index: number;
  total: number;
  dispatch: Dispatch<PrototypeEditorAction>;
}

export function FieldRow({ field, selected, index, total, dispatch }: FieldRowProps) {
  return (
    <article className={`field-row ${selected ? 'selected' : ''}`}>
      <button
        type="button"
        className="field-select"
        onClick={() => dispatch({ type: 'select', fieldKey: field.key })}
        aria-label={`编辑字段 ${field.label}`}
      >
        <span className={`type-dot type-${field.type}`} aria-hidden="true" />
        <span className="field-identity">
          <strong>{field.label}</strong>
          <code>{field.key}</code>
        </span>
        <span className="type-chip">{fieldTypeLabels[field.type]}</span>
        <span className="field-detail">{field.detail}</span>
        {field.confidence === 'review' && <span className="review-chip">需确认</span>}
      </button>
      <button
        type="button"
        className={`required-toggle ${field.required ? 'active' : ''}`}
        aria-pressed={field.required}
        onClick={() => dispatch({ type: 'toggle-required', fieldKey: field.key })}
      >
        {field.required ? '必填' : '可选'}
      </button>
      <div className="reorder-actions" aria-label={`${field.label} 排序`}>
        <button
          type="button"
          aria-label={`上移 ${field.label}`}
          disabled={index === 0}
          onClick={() => dispatch({ type: 'move', fieldKey: field.key, direction: -1 })}
        >
          <ArrowUp aria-hidden="true" size={15} />
        </button>
        <button
          type="button"
          aria-label={`下移 ${field.label}`}
          disabled={index === total - 1}
          onClick={() => dispatch({ type: 'move', fieldKey: field.key, direction: 1 })}
        >
          <ArrowDown aria-hidden="true" size={15} />
        </button>
      </div>
    </article>
  );
}

export function AddFieldButton({ dispatch }: { dispatch: Dispatch<PrototypeEditorAction> }) {
  return (
    <button type="button" className="add-field-button" onClick={() => dispatch({ type: 'add-field' })}>
      <Plus aria-hidden="true" size={17} />
      添加字段
    </button>
  );
}

export function FieldInspector({ field }: { field: SchemaField }) {
  return (
    <aside className="field-inspector" aria-label="字段检查器">
      <div className="inspector-heading">
        <span>字段检查器</span>
        <span className="path-chip">/{field.key}</span>
      </div>
      <label className="control-label" htmlFor="field-key">fieldKey</label>
      <input id="field-key" value={field.key} readOnly />
      <label className="control-label" htmlFor="display-name">显示名称</label>
      <input id="display-name" value={field.label} readOnly />
      <div className="inspector-two-col">
        <div>
          <span className="control-label">类型</span>
          <button type="button" className="select-like">{fieldTypeLabels[field.type]}</button>
        </div>
        <div>
          <span className="control-label">存在性</span>
          <button type="button" className="select-like">{field.required ? '必填' : '可选'}</button>
        </div>
      </div>
      <section className="constraint-card">
        <div>
          <strong>约束</strong>
          <span>1 条已配置</span>
        </div>
        <code>{field.detail}</code>
      </section>
      <section className="evidence-card">
        <div className="evidence-title">
          <Sparkles aria-hidden="true" size={16} />
          AI evidence preview
        </div>
        <p>JSON /products/0/{field.key}</p>
        <span>{field.confidence === 'review' ? '置信度 0.62 · 需要人工处置' : '已人工确认'}</span>
      </section>
    </aside>
  );
}

export function SchemaHeading({ state }: { state: PrototypeEditorState }) {
  return (
    <div className="schema-heading">
      <div>
        <span className="eyebrow">SCHEMA DRAFT / {state.schemaKey}</span>
        <h1>{state.displayName}</h1>
        <p>{state.description}</p>
      </div>
      <div className="schema-meta">
        <span>revision</span>
        <strong>{state.revision}</strong>
      </div>
    </div>
  );
}
