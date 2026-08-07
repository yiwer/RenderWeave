import { Braces, CheckCircle2, ChevronRight, Plus, Search, Sparkles } from 'lucide-react';
import type { Dispatch } from 'react';

import { fieldTypeLabels, type PrototypeEditorAction, type PrototypeEditorState, type SchemaField } from './model';
import { ProductChrome, SchemaHeading, ViewToggle } from './SharedPrototypeParts';

interface VariantProps {
  state: PrototypeEditorState;
  selectedField: SchemaField;
  dispatch: Dispatch<PrototypeEditorAction>;
}

function compiledPreview(state: PrototypeEditorState): string {
  const properties = state.fields
    .map((field) => `    "${field.key}": { "type": "${field.type === 'decimal' ? 'number' : field.type === 'array' ? 'array' : field.type === 'boolean' ? 'boolean' : 'string'}" }`)
    .join(',\n');
  const required = state.fields.filter((field) => field.required).map((field) => `"${field.key}"`).join(', ');
  return `{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "properties": {
${properties}
  },
  "required": [${required}],
  "additionalProperties": true
}`;
}

export function VariantC({ state, selectedField, dispatch }: VariantProps) {
  return (
    <div className="variant-shell variant-c">
      <ProductChrome state={state} dispatch={dispatch} layoutName="Schema Ledger" />
      <div className="ledger-context-bar">
        <nav aria-label="资源类型">
          <a href="#draft" className="active">Drafts</a>
          <a href="#static">StaticSchema</a>
          <a href="#ai">AI Runs</a>
          <a href="#validate">Validator</a>
        </nav>
        <label className="ledger-search">
          <Search aria-hidden="true" size={16} />
          <span className="sr-only">搜索字段</span>
          <input type="search" placeholder="搜索 fieldKey" />
        </label>
      </div>
      <main className="ledger-main" id="main-content">
        <section className="ledger-workspace">
          <div className="ledger-heading-row">
            <SchemaHeading state={state} />
            <ViewToggle state={state} dispatch={dispatch} />
          </div>
          <div className="ledger-summary">
            <span><CheckCircle2 aria-hidden="true" size={16} /> Draft 可保存</span>
            <span><Braces aria-hidden="true" size={16} /> {state.fields.length} fields</span>
            <span><Sparkles aria-hidden="true" size={16} /> 1 项 AI evidence 待确认</span>
          </div>
          <div className="ledger-table" role="table" aria-label="Schema 字段账本">
            <div className="ledger-table-head" role="row">
              <span role="columnheader">顺序</span>
              <span role="columnheader">字段</span>
              <span role="columnheader">类型</span>
              <span role="columnheader">约束 / 引用</span>
              <span role="columnheader">存在性</span>
              <span role="columnheader">状态</span>
              <span role="columnheader" aria-label="打开" />
            </div>
            {state.fields.map((field, index) => (
              <div
                role="row"
                key={field.key}
                className={`ledger-row ${field.key === state.selectedFieldKey ? 'selected' : ''}`}
              >
                <span role="cell" className="ledger-index">{String(index + 1).padStart(2, '0')}</span>
                <span role="cell" className="ledger-field">
                  <button
                    type="button"
                    className="ledger-field-button"
                    onClick={() => dispatch({ type: 'select', fieldKey: field.key })}
                  >
                    <strong>{field.label}</strong><code>{field.key}</code>
                  </button>
                </span>
                <span role="cell"><span className={`type-chip type-${field.type}`}>{fieldTypeLabels[field.type]}</span></span>
                <span role="cell" className="ledger-detail">{field.detail}</span>
                <span role="cell">
                  <button
                    type="button"
                    className={`required-toggle ${field.required ? 'active' : ''}`}
                    aria-pressed={field.required}
                    aria-label={`${field.label}设为${field.required ? '可选' : '必填'}`}
                    onClick={() => dispatch({ type: 'toggle-required', fieldKey: field.key })}
                  >
                    {field.required ? '必填' : '可选'}
                  </button>
                </span>
                <span role="cell">{field.confidence === 'review' ? <span className="review-chip">需确认</span> : <span className="ok-label">已确认</span>}</span>
                <span role="cell">
                  <button
                    type="button"
                    className="ledger-open-button"
                    aria-label={`编辑字段 ${field.label}`}
                    onClick={() => dispatch({ type: 'select', fieldKey: field.key })}
                  >
                    <ChevronRight aria-hidden="true" size={16} />
                  </button>
                </span>
              </div>
            ))}
            <button type="button" className="ledger-add" onClick={() => dispatch({ type: 'add-field' })}>
              <Plus aria-hidden="true" size={16} /> 添加字段
            </button>
          </div>
          <section className="ledger-inline-inspector" aria-label="选中字段摘要">
            <div><span>SELECTED</span><strong>{selectedField.label}</strong><code>/{selectedField.key}</code></div>
            <div><span>类型</span><strong>{fieldTypeLabels[selectedField.type]}</strong></div>
            <div><span>约束</span><strong>{selectedField.detail}</strong></div>
            <button type="button" className="button ghost-button">打开完整检查器</button>
          </section>
        </section>
        <aside className="compiled-preview" aria-label="编译后 JSON Schema 预览">
          <div className="preview-heading">
            <div><span>COMPILED PREVIEW</span><strong>JSON Schema 2020-12</strong></div>
            <span>只读</span>
          </div>
          <pre>{compiledPreview(state)}</pre>
          <div className="preview-foot">
            <span>estimated 1.8 KiB</span>
            <button type="button">复制</button>
          </div>
        </aside>
      </main>
    </div>
  );
}
