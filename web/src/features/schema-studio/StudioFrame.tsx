import {
  Braces,
  Check,
  CircleAlert,
  FileCheck2,
  History,
  Layers3,
  ListTree,
  LoaderCircle,
  PanelRightOpen,
  Plus,
  Redo2,
  RotateCcw,
  Save,
  Undo2,
} from 'lucide-react';
import { Link } from 'react-router-dom';

import type { EditorSession } from './editor-session';
import type { EditorView } from './editor-types';

interface StudioChromeProps {
  session: EditorSession;
  saving: boolean;
  blockerCount: number;
  onSave: () => void;
  onUndo: () => void;
  onRedo: () => void;
  onRestore: () => void;
  onOpenInspector: () => void;
}

export function StudioChrome({
  session,
  saving,
  blockerCount,
  onSave,
  onUndo,
  onRedo,
  onRestore,
  onOpenInspector,
}: StudioChromeProps) {
  const status = saving
    ? '正在保存'
    : session.dirty
      ? '有未保存更改'
      : `revision ${session.revision ?? 0} 已保存`;
  return (
    <header className="studio-chrome">
      <Link className="product-mark" to="/schemas/new" aria-label="RenderWeave 新建 Schema Draft">
        <span className="weave-mark" aria-hidden="true">RW</span>
        <span>RenderWeave</span>
      </Link>
      <div className="studio-breadcrumb" aria-label="当前位置">
        <span>Schema Draft</span>
        <span aria-hidden="true">/</span>
        <strong>{session.schemaKey || '新建'}</strong>
      </div>
      <div className="chrome-actions studio-chrome-actions">
        <span
          className={`status-chip ${session.dirty ? 'status-dirty' : ''} ${blockerCount > 0 ? 'status-blocked' : ''}`}
          aria-live="polite"
          title={blockerCount > 0 ? `${blockerCount} 项本地规则未通过` : status}
        >
          {saving
            ? <LoaderCircle className="spin" aria-hidden="true" size={14} />
            : blockerCount > 0
              ? <CircleAlert aria-hidden="true" size={14} />
              : <Check aria-hidden="true" size={14} />}
          {blockerCount > 0 ? `${blockerCount} 项待修正` : status}
        </span>
        <div className="history-actions" aria-label="编辑历史">
          <button type="button" className="icon-button" disabled={session.undoStack.length === 0} onClick={onUndo} title="撤销（Ctrl/⌘ Z）" aria-label="撤销">
            <Undo2 aria-hidden="true" size={16} />
          </button>
          <button type="button" className="icon-button" disabled={session.redoStack.length === 0} onClick={onRedo} title="重做（Ctrl/⌘ Shift Z）" aria-label="重做">
            <Redo2 aria-hidden="true" size={16} />
          </button>
          <button type="button" className="icon-button" disabled={!session.saved || !session.dirty} onClick={onRestore} title="恢复到最近保存" aria-label="恢复到最近保存">
            <RotateCcw aria-hidden="true" size={16} />
          </button>
        </div>
        <button type="button" className="button ghost-button inspector-trigger" onClick={onOpenInspector}>
          <PanelRightOpen aria-hidden="true" size={16} />
          字段检查器
        </button>
        <button
          type="button"
          className="button primary-button"
          disabled={saving || !session.dirty}
          onClick={onSave}
        >
          {saving ? <LoaderCircle className="spin" aria-hidden="true" size={16} /> : <Save aria-hidden="true" size={16} />}
          {session.revision === null ? '创建 Draft' : '保存 revision'}
        </button>
      </div>
    </header>
  );
}

export function StudioRail({ session }: { session: EditorSession }) {
  const references = session.fields.filter((field) =>
    field.value.type === 'reference'
    || (field.value.type === 'array' && field.value.items.type === 'reference')).length;
  return (
    <nav className="resource-rail studio-rail" aria-label="Schema 工作区导航">
      <div className="rail-section-label">SCHEMA</div>
      <Link className="rail-link" to="/schemas">
        <ListTree aria-hidden="true" size={17} />
        Draft 列表
      </Link>
      <Link className="rail-link active" to={session.revision === null ? '/schemas/new' : `/schemas/${session.schemaKey}`} aria-current="page">
        <Braces aria-hidden="true" size={17} />
        当前 Draft
      </Link>
      <Link className="rail-create" to="/schemas/new">
        <Plus aria-hidden="true" size={15} />
        新建 Draft
      </Link>
      <Link className="rail-link rail-secondary-link" to="/static-schemas">
        <Layers3 aria-hidden="true" size={17} />StaticSchema
      </Link>
      <Link className="rail-link rail-secondary-link" to="/validator">
        <FileCheck2 aria-hidden="true" size={17} />样本验证器
      </Link>
      <div className="rail-divider" />
      <div className="rail-context-card">
        <span>{session.revision === null ? '尚未创建' : '当前工作定义'}</span>
        <strong>{session.schemaKey || '填写 schemaKey'}</strong>
        <small>
          {session.revision === null ? '首次保存后生成 revision 0' : `revision ${session.revision}`}
          {' · '}{session.fields.length} fields
        </small>
      </div>
      <div className="rail-facts" aria-label="当前 Schema 摘要">
        <span><History aria-hidden="true" size={14} />{session.undoStack.length} 步可撤销</span>
        <span><Braces aria-hidden="true" size={14} />{references} 个引用字段</span>
      </div>
      <div className="rail-note">
        <strong>Schema Studio · v1</strong>
        <span>七种字段类型、显式保存、表单与一层树状图共享同一编辑会话。</span>
      </div>
    </nav>
  );
}

interface ViewToggleProps {
  view: EditorView;
  onChange: (view: EditorView) => void;
}

export function StudioViewToggle({ view, onChange }: ViewToggleProps) {
  return (
    <div className="view-toggle" aria-label="编辑模式">
      <button
        type="button"
        className={view === 'form' ? 'active' : ''}
        aria-pressed={view === 'form'}
        onClick={() => onChange('form')}
      >
        表单
      </button>
      <button
        type="button"
        className={view === 'map' ? 'active' : ''}
        aria-pressed={view === 'map'}
        onClick={() => onChange('map')}
      >
        树状图
      </button>
    </div>
  );
}
