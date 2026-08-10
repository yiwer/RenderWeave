import { Braces, CornerDownRight, ListTree } from 'lucide-react';

import {
  editorTypeLabels,
  editorValueFromPersisted,
  summarizeEditorValue,
  type EditorValue,
  type PersistedDefinition,
  type PersistedField,
} from './editor-types';

export function ReadonlyDefinitionTree({
  schemaKey,
  definition,
  selectedIndex,
  onSelect,
}: {
  schemaKey: string;
  definition: PersistedDefinition;
  selectedIndex?: number;
  onSelect?: (index: number) => void;
}) {
  return (
    <section className="readonly-schema-tree" aria-label={`${definition.displayName} 字段树`}>
      <header className="readonly-tree-root">
        <span className="readonly-tree-root-icon" aria-hidden="true"><Braces size={18} /></span>
        <span><small>根对象</small><strong>{definition.displayName}</strong><code>{schemaKey}</code></span>
        <em>{definition.fields.length} 个字段</em>
      </header>
      {definition.description && <p className="readonly-tree-description">{definition.description}</p>}
      {definition.fields.length === 0 ? (
        <div className="readonly-tree-empty"><ListTree aria-hidden="true" size={20} /><span>此定义没有字段</span></div>
      ) : (
        <ol className="readonly-tree-branches">
          {definition.fields.map((field, index) => {
            const value = editorValueFromPersisted(field.value);
            const label = field.displayName?.trim() || field.fieldKey || `字段 ${index + 1}`;
            const content = <ReadonlyTreeField field={field} value={value} label={label} index={index} />;
            const detail = readonlyChildDetail(value);
            return (
              <li key={`${field.fieldKey}-${index}`}>
                <span className="readonly-tree-connector" aria-hidden="true" />
                {onSelect ? (
                  <button
                    type="button"
                    className={`readonly-tree-field ${selectedIndex === index ? 'is-selected' : ''}`}
                    aria-pressed={selectedIndex === index}
                    aria-label={`查看字段 ${label}`}
                    onClick={() => onSelect(index)}
                  >{content}</button>
                ) : <article className="readonly-tree-field">{content}</article>}
                {detail && (
                  <div className="readonly-tree-detail">
                    <CornerDownRight aria-hidden="true" size={14} />
                    <span><small>{detail.label}</small><strong>{detail.type}</strong><code>{detail.summary}</code></span>
                  </div>
                )}
              </li>
            );
          })}
        </ol>
      )}
    </section>
  );
}

export function ReadonlyDefinitionForm({ definition }: { definition: PersistedDefinition }) {
  if (definition.fields.length === 0) {
    return <section className="readonly-definition-form readonly-definition-empty" role="status"><ListTree aria-hidden="true" size={20} /><span>此 definition 没有字段</span></section>;
  }
  return (
    <section className="readonly-definition-form" aria-label={`${definition.displayName} 字段表单`}>
      {definition.fields.map((field, index) => {
        const value = editorValueFromPersisted(field.value);
        const label = field.displayName?.trim() || field.fieldKey || `字段 ${index + 1}`;
        return (
          <article className="readonly-form-field" key={`${field.fieldKey}-${index}`}>
            <span className="readonly-field-order">{String(index + 1).padStart(2, '0')}</span>
            <span className={`type-dot type-${value.type}`} aria-hidden="true" />
            <span className="readonly-field-identity"><strong>{label}</strong><code>{field.fieldKey}</code></span>
            <span className="type-chip">{editorTypeLabels[value.type]}</span>
            <span className="readonly-field-summary">{summarizeEditorValue(value)}</span>
            <span className={`readonly-required ${field.required ? 'is-required' : ''}`}>{field.required ? '必填' : '可选'}</span>
            {field.description && <p>{field.description}</p>}
          </article>
        );
      })}
    </section>
  );
}

function ReadonlyTreeField({
  field,
  value,
  label,
  index,
}: {
  field: PersistedField;
  value: EditorValue;
  label: string;
  index: number;
}) {
  return (
    <>
      <span className="readonly-tree-order">{String(index + 1).padStart(2, '0')}</span>
      <span className={`type-dot type-${value.type}`} aria-hidden="true" />
      <span className="readonly-tree-identity"><strong>{label}</strong><code>{field.fieldKey}</code></span>
      <span className="type-chip">{editorTypeLabels[value.type]}</span>
      <span className="readonly-tree-summary">{summarizeEditorValue(value)}</span>
      <span className={`readonly-required ${field.required ? 'is-required' : ''}`}>{field.required ? '必填' : '可选'}</span>
      {field.description && <small className="readonly-tree-field-description">{field.description}</small>}
    </>
  );
}

function readonlyChildDetail(value: EditorValue): { label: string; type: string; summary: string } | null {
  if (value.type === 'array') {
    return {
      label: '数组元素',
      type: editorTypeLabels[value.items.type],
      summary: summarizeEditorValue(value.items),
    };
  }
  if (value.type === 'reference') {
    return {
      label: value.referenceKind === 'static' ? 'StaticSchemaRef' : 'SchemaRef',
      type: '引用目标',
      summary: value.referenceKind === 'static'
        ? `${value.schemaKey || '未设置'}@${value.versionTag || '?'}`
        : value.schemaKey || '未设置',
    };
  }
  return null;
}
