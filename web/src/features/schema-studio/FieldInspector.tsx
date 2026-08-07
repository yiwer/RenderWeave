import {
  Braces,
  Check,
  CircleAlert,
  Copy,
  CopyPlus,
  Plus,
  Trash2,
  X,
} from 'lucide-react';
import { useState, type Dispatch, type ReactNode } from 'react';

import type { EditorAction } from './editor-session';
import {
  createEditorScalarValue,
  editorTypeLabels,
  type ArrayEditorValue,
  type DecimalEditorValue,
  type EditorField,
  type EditorScalarType,
  type EditorScalarValue,
  type EditorValue,
  type EditorValueType,
  type EnumInput,
  type OptionalInput,
  type OrderedEditorValue,
  type TextEditorValue,
} from './editor-types';
import type { EditorDiagnostic } from './editor-validation';

interface FieldInspectorProps {
  field?: EditorField;
  fieldIndex: number;
  allFields: EditorField[];
  revision: number | null;
  dirty: boolean;
  diagnostics: EditorDiagnostic[];
  definitionPreview: string;
  open: boolean;
  dispatch: Dispatch<EditorAction>;
  onClose: () => void;
  onAddField: () => void;
  onTouch: (pointer: string) => void;
  showProblem: (pointer: string) => boolean;
}

const valueTypes = Object.keys(editorTypeLabels) as EditorValueType[];
const scalarTypes = valueTypes.filter((type): type is EditorScalarType => type !== 'array');

export function FieldInspector({
  field,
  fieldIndex,
  allFields,
  revision,
  dirty,
  diagnostics,
  definitionPreview,
  open,
  dispatch,
  onClose,
  onAddField,
  onTouch,
  showProblem,
}: FieldInspectorProps) {
  const [copied, setCopied] = useState(false);

  if (!field) {
    return (
      <aside className={`studio-inspector ${open ? 'is-open' : ''}`} aria-label="字段检查器">
        <InspectorHeading title="尚未选择字段" onClose={onClose} />
        <div className="studio-empty-inspector">
          <Braces aria-hidden="true" size={24} />
          <strong>这是一个空 Schema</strong>
          <span>空定义可以直接保存；也可以从任意基础字段、引用或数组开始。</span>
          <button type="button" className="button primary-button" onClick={onAddField}>
            <Plus aria-hidden="true" size={16} />添加字段
          </button>
        </div>
        <PublishPreparation fields={allFields} revision={revision} dirty={dirty} diagnostics={diagnostics} />
      </aside>
    );
  }

  const base = `/definition/fields/${fieldIndex}`;
  const valueBase = `${base}/value`;
  const fieldProblems = diagnostics.filter((item) => item.rowKey === field.rowKey);
  const visibleProblems = fieldProblems.filter((item) => showProblem(item.pointer));
  const keyPointer = `${base}/fieldKey`;
  const displayNamePointer = `${base}/displayName`;
  const descriptionPointer = `${base}/description`;
  const byteLength = new TextEncoder().encode(field.fieldKey).length;

  const update = (patch: Partial<Omit<EditorField, 'rowKey'>>, historyGroup?: string) => {
    dispatch({ type: 'update-field', rowKey: field.rowKey, patch, ...(historyGroup ? { historyGroup } : {}) });
  };
  const updateValue = (value: EditorValue, historyGroup?: string) => {
    dispatch({ type: 'set-field-value', rowKey: field.rowKey, value, ...(historyGroup ? { historyGroup } : {}) });
  };
  const finish = (pointer: string) => {
    onTouch(pointer);
    const constraintAt = pointer.indexOf('/constraints/');
    if (constraintAt >= 0) onTouch(pointer.slice(0, constraintAt + '/constraints'.length));
    dispatch({ type: 'commit-history-group' });
  };
  const copyDefinition = async () => {
    try {
      await navigator.clipboard.writeText(definitionPreview);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 1_800);
    } catch {
      setCopied(false);
    }
  };

  return (
    <aside className={`studio-inspector ${open ? 'is-open' : ''}`} aria-label="字段检查器">
      <InspectorHeading title={field.displayName || field.fieldKey || `字段 ${fieldIndex + 1}`} onClose={onClose} />
      <div className="inspector-path-actions">
        <span className="path-chip studio-path-chip">/{field.fieldKey || '未命名'}</span>
        <div>
          <button type="button" className="mini-icon-button" title="复制字段" aria-label="复制字段" onClick={() => dispatch({ type: 'duplicate-field', rowKey: field.rowKey })}>
            <CopyPlus aria-hidden="true" size={15} />
          </button>
          <button type="button" className="mini-icon-button danger-icon-button" title="删除字段" aria-label="删除字段" onClick={() => dispatch({ type: 'delete-field', rowKey: field.rowKey })}>
            <Trash2 aria-hidden="true" size={15} />
          </button>
        </div>
      </div>

      {visibleProblems.length > 0 && (
        <div className="inspector-problem-box" role="alert">
          <CircleAlert aria-hidden="true" size={16} />
          <div>
            <strong>{visibleProblems.length} 项需要修正</strong>
            {visibleProblems.map((problem) => <span key={`${problem.code}-${problem.pointer}`}>{problem.message}</span>)}
          </div>
        </div>
      )}

      <div className="control-group">
        <div className="control-label-row">
          <label htmlFor={`field-key-${field.rowKey}`}>fieldKey</label>
          <span className={byteLength > 128 ? 'count-error' : ''}>{byteLength}/128 bytes</span>
        </div>
        <input
          id={`field-key-${field.rowKey}`}
          className="mono-input"
          data-pointer={keyPointer}
          value={field.fieldKey}
          aria-invalid={showProblem(keyPointer)}
          onChange={(event) => update({ fieldKey: event.target.value }, `${field.rowKey}:fieldKey`)}
          onBlur={() => finish(keyPointer)}
        />
        <p className="control-help">大小写敏感；允许中文、/ 与 ~，不会执行 Unicode normalization。</p>
      </div>

      <div className="control-group">
        <label htmlFor={`field-name-${field.rowKey}`}>显示名称（可选）</label>
        <input
          id={`field-name-${field.rowKey}`}
          data-pointer={displayNamePointer}
          value={field.displayName}
          aria-invalid={showProblem(displayNamePointer)}
          onChange={(event) => update({ displayName: event.target.value }, `${field.rowKey}:displayName`)}
          onBlur={() => finish(displayNamePointer)}
        />
      </div>

      <div className="control-group">
        <label htmlFor={`field-description-${field.rowKey}`}>字段说明（可选）</label>
        <textarea
          id={`field-description-${field.rowKey}`}
          rows={3}
          data-pointer={descriptionPointer}
          value={field.description}
          aria-invalid={showProblem(descriptionPointer)}
          onChange={(event) => update({ description: event.target.value }, `${field.rowKey}:description`)}
          onBlur={() => finish(descriptionPointer)}
        />
      </div>

      <div className="inspector-two-col inspector-field-kind">
        <div className="control-group compact">
          <label htmlFor={`field-type-${field.rowKey}`}>字段类型</label>
          <select
            id={`field-type-${field.rowKey}`}
            value={field.value.type}
            onChange={(event) => dispatch({
              type: 'set-field-type', rowKey: field.rowKey, valueType: event.target.value as EditorValueType,
            })}
          >
            {valueTypes.map((type) => <option key={type} value={type}>{editorTypeLabels[type]}</option>)}
          </select>
        </div>
        <label className="required-control compact-required-control">
          <input
            type="checkbox"
            checked={field.required}
            onChange={(event) => update({ required: event.target.checked })}
          />
          <span><strong>必填字段</strong><small>RootDocument 必须出现</small></span>
        </label>
      </div>

      <ValueEditor
        value={field.value}
        pointer={valueBase}
        rowKey={field.rowKey}
        diagnostics={fieldProblems}
        showProblem={showProblem}
        onChange={updateValue}
        onFinish={finish}
      />

      <PublishPreparation fields={allFields} revision={revision} dirty={dirty} diagnostics={diagnostics} />

      <details className="dsl-preview">
        <summary>
          <span><Braces aria-hidden="true" size={16} />Definition DSL 预览</span>
          <span>只读</span>
        </summary>
        <pre>{definitionPreview}</pre>
        <button type="button" onClick={() => void copyDefinition()}>
          {copied ? <Check aria-hidden="true" size={15} /> : <Copy aria-hidden="true" size={15} />}
          {copied ? '已复制' : '复制 JSON'}
        </button>
      </details>
    </aside>
  );
}

function InspectorHeading({ title, onClose }: { title: string; onClose: () => void }) {
  return (
    <div className="studio-inspector-heading">
      <div><span>FIELD INSPECTOR</span><h2>{title}</h2></div>
      <button type="button" className="icon-button inspector-close" onClick={onClose} aria-label="关闭字段检查器">
        <X aria-hidden="true" size={17} />
      </button>
    </div>
  );
}

interface ValueEditorProps {
  value: EditorValue;
  pointer: string;
  rowKey: string;
  diagnostics: EditorDiagnostic[];
  showProblem: (pointer: string) => boolean;
  onChange: (value: EditorValue, historyGroup?: string) => void;
  onFinish: (pointer: string) => void;
}

function ValueEditor(props: ValueEditorProps) {
  const { value } = props;
  switch (value.type) {
    case 'text':
      return <TextConstraints {...props} value={value} onChange={(next, group) => props.onChange(next, group)} />;
    case 'decimal':
      return <DecimalConstraints {...props} value={value} onChange={(next, group) => props.onChange(next, group)} />;
    case 'date':
    case 'time':
      return <OrderedConstraints {...props} value={value} onChange={(next, group) => props.onChange(next, group)} />;
    case 'boolean':
      return <BooleanConstraints {...props} value={value} onChange={(next, group) => props.onChange(next, group)} />;
    case 'reference':
      return <ReferenceEditor {...props} value={value} onChange={(next, group) => props.onChange(next, group)} />;
    case 'array':
      return <ArrayEditor {...props} value={value} />;
  }
}

type ScalarEditorProps<T extends EditorScalarValue> = Omit<ValueEditorProps, 'value' | 'onChange'> & {
  value: T;
  onChange: (value: T, historyGroup?: string) => void;
};

function TextConstraints(props: ScalarEditorProps<TextEditorValue>) {
  const { value, pointer, rowKey, onChange } = props;
  return (
    <ConstraintSection title="文本约束" eyebrow="TEXT CONSTRAINTS">
      <ConstraintGrid>
        <OptionalConstraint label="最小长度" value={value.minLength} pointer={`${pointer}/constraints/minLength`} inputMode="numeric" onChange={(next, group) => onChange({ ...value, minLength: next }, group)} {...controlProps(props, rowKey, 'minLength')} />
        <OptionalConstraint label="最大长度" value={value.maxLength} pointer={`${pointer}/constraints/maxLength`} inputMode="numeric" onChange={(next, group) => onChange({ ...value, maxLength: next }, group)} {...controlProps(props, rowKey, 'maxLength')} />
      </ConstraintGrid>
      <OptionalConstraint label="正则 pattern" value={value.pattern} pointer={`${pointer}/constraints/pattern`} monospace onChange={(next, group) => onChange({ ...value, pattern: next }, group)} {...controlProps(props, rowKey, 'pattern')} />
      <EnumConstraint label="允许值 enum" value={value.enumValues} pointer={`${pointer}/constraints/enum`} onChange={(next, group) => onChange({ ...value, enumValues: next }, group)} {...controlProps(props, rowKey, 'enum')} />
      <OptionalConstraint label="固定值 const" value={value.constValue} pointer={`${pointer}/constraints/const`} onChange={(next, group) => onChange({ ...value, constValue: next }, group)} {...controlProps(props, rowKey, 'const')} />
      <ConstraintProblems {...props} />
    </ConstraintSection>
  );
}

function DecimalConstraints(props: ScalarEditorProps<DecimalEditorValue>) {
  const { value, pointer, rowKey, onChange } = props;
  const entries: Array<[keyof Pick<DecimalEditorValue, 'min' | 'exclusiveMin' | 'max' | 'exclusiveMax' | 'multipleOf'>, string]> = [
    ['min', '最小值（含）'], ['exclusiveMin', '最小值（不含）'], ['max', '最大值（含）'],
    ['exclusiveMax', '最大值（不含）'], ['multipleOf', '倍数 multipleOf'],
  ];
  return (
    <ConstraintSection title="精确数值约束" eyebrow="DECIMAL CONSTRAINTS">
      <p className="constraint-note">以 JSON number 保存，最多 128 位精度；编辑器不会转为 JavaScript 浮点数。</p>
      <ConstraintGrid>
        {entries.map(([key, label]) => (
          <OptionalConstraint
            key={key}
            label={label}
            value={value[key]}
            pointer={`${pointer}/constraints/${key}`}
            inputMode="decimal"
            monospace
            onChange={(next, group) => onChange({ ...value, [key]: next }, group)}
            {...controlProps(props, rowKey, key)}
          />
        ))}
      </ConstraintGrid>
      <EnumConstraint label="允许值 enum" value={value.enumValues} pointer={`${pointer}/constraints/enum`} inputMode="decimal" monospace onChange={(next, group) => onChange({ ...value, enumValues: next }, group)} {...controlProps(props, rowKey, 'enum')} />
      <OptionalConstraint label="固定值 const" value={value.constValue} pointer={`${pointer}/constraints/const`} inputMode="decimal" monospace onChange={(next, group) => onChange({ ...value, constValue: next }, group)} {...controlProps(props, rowKey, 'const')} />
      <ConstraintProblems {...props} />
    </ConstraintSection>
  );
}

function OrderedConstraints(props: ScalarEditorProps<OrderedEditorValue>) {
  const { value, pointer, rowKey, onChange } = props;
  const format = value.type === 'date' ? 'YYYY-MM-DD' : 'HH:mm:ss';
  const entries: Array<[keyof Pick<OrderedEditorValue, 'min' | 'exclusiveMin' | 'max' | 'exclusiveMax'>, string]> = [
    ['min', '最小值（含）'], ['exclusiveMin', '最小值（不含）'], ['max', '最大值（含）'], ['exclusiveMax', '最大值（不含）'],
  ];
  return (
    <ConstraintSection title={`${editorTypeLabels[value.type]}约束`} eyebrow={`${value.type.toUpperCase()} CONSTRAINTS`}>
      <p className="constraint-note">标准格式：<code>{format}</code></p>
      <ConstraintGrid>
        {entries.map(([key, label]) => (
          <OptionalConstraint key={key} label={label} value={value[key]} pointer={`${pointer}/constraints/${key}`} placeholder={format} monospace onChange={(next, group) => onChange({ ...value, [key]: next }, group)} {...controlProps(props, rowKey, key)} />
        ))}
      </ConstraintGrid>
      <EnumConstraint label="允许值 enum" value={value.enumValues} pointer={`${pointer}/constraints/enum`} placeholder={format} monospace onChange={(next, group) => onChange({ ...value, enumValues: next }, group)} {...controlProps(props, rowKey, 'enum')} />
      <OptionalConstraint label="固定值 const" value={value.constValue} pointer={`${pointer}/constraints/const`} placeholder={format} monospace onChange={(next, group) => onChange({ ...value, constValue: next }, group)} {...controlProps(props, rowKey, 'const')} />
      <ConstraintProblems {...props} />
    </ConstraintSection>
  );
}

function BooleanConstraints(props: ScalarEditorProps<Extract<EditorScalarValue, { type: 'boolean' }>>) {
  const { value, pointer, rowKey, onChange, onFinish, showProblem } = props;
  const constPointer = `${pointer}/constraints/const`;
  return (
    <ConstraintSection title="布尔约束" eyebrow="BOOLEAN CONSTRAINTS">
      <div className="constraint-toggle-card boolean-constraint">
        <label className="constraint-toggle-heading">
          <input type="checkbox" checked={value.constValue.enabled} onChange={(event) => onChange({ ...value, constValue: { ...value.constValue, enabled: event.target.checked } })} />
          <span>启用固定值 const</span>
        </label>
        <select
          aria-label="布尔 const 值"
          value={value.constValue.value}
          disabled={!value.constValue.enabled}
          data-pointer={constPointer}
          aria-invalid={showProblem(constPointer)}
          onChange={(event) => onChange({ ...value, constValue: { enabled: true, value: event.target.value } }, `${rowKey}:booleanConst`)}
          onBlur={() => onFinish(constPointer)}
        >
          <option value="true">true</option><option value="false">false</option>
        </select>
      </div>
      <ConstraintProblems {...props} />
    </ConstraintSection>
  );
}

function ReferenceEditor(props: ScalarEditorProps<Extract<EditorScalarValue, { type: 'reference' }>>) {
  const { value, pointer, rowKey, onChange, onFinish, showProblem } = props;
  const schemaPointer = `${pointer}/ref/schemaKey`;
  const versionPointer = `${pointer}/ref/versionTag`;
  return (
    <ConstraintSection title="引用目标" eyebrow="REFERENCE">
      <div className="reference-kind" role="group" aria-label="引用类型">
        <button type="button" className={value.referenceKind === 'draft' ? 'active' : ''} aria-pressed={value.referenceKind === 'draft'} onClick={() => onChange({ ...value, referenceKind: 'draft', versionTag: '' })}>SchemaRef · Draft</button>
        <button type="button" className={value.referenceKind === 'static' ? 'active' : ''} aria-pressed={value.referenceKind === 'static'} onClick={() => onChange({ ...value, referenceKind: 'static' })}>StaticSchemaRef</button>
      </div>
      <div className="control-group compact">
        <label htmlFor={`${rowKey}-ref-schema`}>目标 schemaKey</label>
        <input id={`${rowKey}-ref-schema`} className="mono-input" data-pointer={schemaPointer} value={value.schemaKey} aria-invalid={showProblem(schemaPointer)} onChange={(event) => onChange({ ...value, schemaKey: event.target.value }, `${rowKey}:${pointer}:schemaKey`)} onBlur={() => onFinish(schemaPointer)} />
      </div>
      {value.referenceKind === 'static' && (
        <div className="control-group compact">
          <label htmlFor={`${rowKey}-ref-version`}>versionTag</label>
          <input id={`${rowKey}-ref-version`} className="mono-input" data-pointer={versionPointer} value={value.versionTag} aria-invalid={showProblem(versionPointer)} onChange={(event) => onChange({ ...value, versionTag: event.target.value }, `${rowKey}:${pointer}:versionTag`)} onBlur={() => onFinish(versionPointer)} />
        </div>
      )}
      <p className="constraint-note">Draft 引用会锁定到保存时解析到的 revision；发布前必须显式改成带 versionTag 的 StaticSchemaRef。</p>
      <ConstraintProblems {...props} />
    </ConstraintSection>
  );
}

function ArrayEditor(props: ValueEditorProps & { value: ArrayEditorValue }) {
  const { value, pointer, rowKey, onChange } = props;
  const setArray = (next: ArrayEditorValue, group?: string) => onChange(next, group);
  return (
    <ConstraintSection title="数组与元素" eyebrow="ARRAY">
      <p className="constraint-note">数组元素可为任一标量或引用类型；不允许嵌套数组。</p>
      <ConstraintGrid>
        <OptionalConstraint label="最少元素" value={value.minItems} pointer={`${pointer}/constraints/minItems`} inputMode="numeric" onChange={(next, group) => setArray({ ...value, minItems: next }, group)} {...controlProps(props, rowKey, 'minItems')} />
        <OptionalConstraint label="最多元素" value={value.maxItems} pointer={`${pointer}/constraints/maxItems`} inputMode="numeric" onChange={(next, group) => setArray({ ...value, maxItems: next }, group)} {...controlProps(props, rowKey, 'maxItems')} />
      </ConstraintGrid>
      <label className={`constraint-toggle-card ${value.items.type === 'reference' ? 'is-disabled' : ''}`}>
        <input type="checkbox" checked={value.uniqueItems} disabled={value.items.type === 'reference'} onChange={(event) => setArray({ ...value, uniqueItems: event.target.checked })} />
        <span><strong>uniqueItems</strong><small>{value.items.type === 'reference' ? '对象数组不支持唯一性约束' : '要求数组元素按类型相等后唯一'}</small></span>
      </label>
      <div className="array-item-editor">
        <div className="array-item-heading">
          <div><span>ITEM DESCRIPTOR</span><strong>数组元素</strong></div>
          <select
            aria-label="数组元素类型"
            value={value.items.type}
            onChange={(event) => {
              const type = event.target.value as EditorScalarType;
              setArray({ ...value, uniqueItems: type === 'reference' ? false : value.uniqueItems, items: createEditorScalarValue(type) });
            }}
          >
            {scalarTypes.map((type) => <option key={type} value={type}>{editorTypeLabels[type]}</option>)}
          </select>
        </div>
        <ScalarValueEditor
          {...props}
          value={value.items}
          pointer={`${pointer}/items`}
          onChange={(items, group) => setArray({ ...value, items }, group)}
        />
      </div>
      <ConstraintProblems {...props} />
    </ConstraintSection>
  );
}

function ScalarValueEditor(props: ScalarEditorProps<EditorScalarValue>) {
  switch (props.value.type) {
    case 'text': return <TextConstraints {...props} value={props.value} />;
    case 'decimal': return <DecimalConstraints {...props} value={props.value} />;
    case 'date':
    case 'time': return <OrderedConstraints {...props} value={props.value} />;
    case 'boolean': return <BooleanConstraints {...props} value={props.value} />;
    case 'reference': return <ReferenceEditor {...props} value={props.value} />;
  }
}

interface ConstraintControlProps {
  pointer: string;
  rowKey: string;
  showProblem: (pointer: string) => boolean;
  onFinish: (pointer: string) => void;
  historyKey: string;
}

function OptionalConstraint({
  label,
  value,
  pointer,
  rowKey,
  historyKey,
  inputMode,
  placeholder,
  monospace,
  showProblem,
  onChange,
  onFinish,
}: ConstraintControlProps & {
  label: string;
  value: OptionalInput;
  inputMode?: 'numeric' | 'decimal';
  placeholder?: string;
  monospace?: boolean;
  onChange: (value: OptionalInput, historyGroup?: string) => void;
}) {
  return (
    <div className={`constraint-toggle-card ${value.enabled ? 'is-enabled' : ''}`}>
      <label className="constraint-toggle-heading">
        <input type="checkbox" checked={value.enabled} onChange={(event) => onChange({ ...value, enabled: event.target.checked })} />
        <strong>{label}</strong>
      </label>
      <input
        aria-label={label}
        className={monospace ? 'mono-input' : ''}
        data-pointer={pointer}
        disabled={!value.enabled}
        value={value.value}
        inputMode={inputMode}
        placeholder={placeholder}
        aria-invalid={showProblem(pointer)}
        onChange={(event) => onChange({ enabled: true, value: event.target.value }, `${rowKey}:${historyKey}`)}
        onBlur={() => onFinish(pointer)}
      />
    </div>
  );
}

function EnumConstraint({
  label,
  value,
  pointer,
  rowKey,
  historyKey,
  inputMode,
  placeholder,
  monospace,
  showProblem,
  onChange,
  onFinish,
}: ConstraintControlProps & {
  label: string;
  value: EnumInput;
  inputMode?: 'decimal';
  placeholder?: string;
  monospace?: boolean;
  onChange: (value: EnumInput, historyGroup?: string) => void;
}) {
  const updateEntry = (index: number, entry: string) => {
    const values = [...value.values];
    values[index] = entry;
    onChange({ enabled: true, values }, `${rowKey}:${historyKey}:${index}`);
  };
  return (
    <div className={`constraint-toggle-card enum-control ${value.enabled ? 'is-enabled' : ''}`}>
      <label className="constraint-toggle-heading">
        <input type="checkbox" checked={value.enabled} onChange={(event) => onChange({ ...value, enabled: event.target.checked })} />
        <strong>{label}</strong>
      </label>
      {value.enabled && (
        <div className="enum-values">
          {value.values.map((entry, index) => {
            const entryPointer = `${pointer}/${index}`;
            return (
              <div key={index}>
                <input className={monospace ? 'mono-input' : ''} data-pointer={entryPointer} inputMode={inputMode} placeholder={placeholder} value={entry} aria-label={`${label} 第 ${index + 1} 项`} aria-invalid={showProblem(entryPointer)} onChange={(event) => updateEntry(index, event.target.value)} onBlur={() => onFinish(entryPointer)} />
                <button type="button" className="mini-icon-button" aria-label={`删除第 ${index + 1} 项`} onClick={() => onChange({ enabled: true, values: value.values.filter((_, entryIndex) => entryIndex !== index) })}><X aria-hidden="true" size={14} /></button>
              </div>
            );
          })}
          <button type="button" className="enum-add" disabled={value.values.length >= 256} onClick={() => onChange({ enabled: true, values: [...value.values, ''] })}><Plus aria-hidden="true" size={14} />添加值</button>
        </div>
      )}
    </div>
  );
}

function ConstraintSection({ title, eyebrow, children }: { title: string; eyebrow: string; children: ReactNode }) {
  return (
    <section className="inspector-section">
      <div className="inspector-section-heading"><div><span>{eyebrow}</span><h3>{title}</h3></div><span>0..n</span></div>
      {children}
    </section>
  );
}

function ConstraintGrid({ children }: { children: ReactNode }) {
  return <div className="constraint-grid">{children}</div>;
}

function ConstraintProblems({ diagnostics, pointer, showProblem }: Pick<ValueEditorProps, 'diagnostics' | 'pointer' | 'showProblem'>) {
  const problems = diagnostics.filter((problem) => problem.pointer.startsWith(pointer) && showProblem(problem.pointer));
  if (problems.length === 0) return null;
  return <div className="inline-problems constraint-problems" role="alert">{problems.map((problem) => <span key={`${problem.code}-${problem.pointer}`}>{problem.message}</span>)}</div>;
}

function controlProps(
  props: Pick<ValueEditorProps, 'showProblem' | 'onFinish'>,
  rowKey: string,
  historyKey: PropertyKey,
): Pick<ConstraintControlProps, 'rowKey' | 'showProblem' | 'onFinish' | 'historyKey'> {
  return { rowKey, showProblem: props.showProblem, onFinish: props.onFinish, historyKey: String(historyKey) };
}

function PublishPreparation({
  fields,
  revision,
  dirty,
  diagnostics,
}: {
  fields: EditorField[];
  revision: number | null;
  dirty: boolean;
  diagnostics: EditorDiagnostic[];
}) {
  const references = fields.flatMap((field) => {
    const value = field.value.type === 'array' ? field.value.items : field.value;
    return value.type === 'reference' ? [value] : [];
  });
  const draftRefs = references.filter((value) => value.referenceKind === 'draft').length;
  const ready = revision !== null && !dirty && diagnostics.length === 0 && draftRefs === 0;
  return (
    <details className={`publish-preparation ${ready ? 'is-ready' : ''}`} open={draftRefs > 0}>
      <summary>
        <span>发布准备</span>
        <strong>{ready ? '可发布' : '尚未就绪'}</strong>
      </summary>
      <ul>
        <li className={revision !== null && !dirty ? 'is-done' : ''}>{revision === null ? '先创建并保存 Draft' : dirty ? '保存当前未提交更改' : `exact revision ${revision} 已保存`}</li>
        <li className={diagnostics.length === 0 ? 'is-done' : ''}>{diagnostics.length === 0 ? '本地定义规则通过' : `${diagnostics.length} 项本地规则待修正`}</li>
        <li className={draftRefs === 0 ? 'is-done' : ''}>{draftRefs === 0 ? '所有引用均为 StaticSchemaRef' : `${draftRefs} 个 SchemaRef 必须选择 versionTag`}</li>
      </ul>
      <p>发布不会隐式保存，也不会自动选择 latest；最终服务端会重新校验引用并自底向上编译。</p>
    </details>
  );
}
