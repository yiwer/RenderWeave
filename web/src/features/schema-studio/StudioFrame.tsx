import {
  Braces,
  Check,
  ChevronRight,
  CircleAlert,
  FileCheck2,
  History,
  Layers3,
  ListTree,
  LoaderCircle,
  PanelRightOpen,
  Redo2,
  RotateCcw,
  Undo2,
} from 'lucide-react';
import { Link } from 'react-router-dom';
import type { ReactNode } from 'react';

import type { EditorSession } from './editor-session';
import type { EditorView } from './editor-types';

interface StudioChromeProps {
  session: EditorSession;
  saving: boolean;
  blockerCount: number;
  actions: ReactNode;
}

interface StudioEditToolsProps {
  session: EditorSession;
  onUndo: () => void;
  onRedo: () => void;
  onRestore: () => void;
  onOpenInspector: () => void;
}

export function StudioChrome({
  session,
  saving,
  blockerCount,
  actions,
}: StudioChromeProps) {
  const status = saving
    ? '正在保存'
    : session.dirty
      ? '有未保存更改'
      : `revision ${session.revision ?? 0} 已保存`;
  return (
    <header className="studio-chrome">
      <Link className="product-mark" to="/schemas" aria-label="RenderWeave 数据结构设计">
        <span className="weave-mark" aria-hidden="true">RW</span>
        <span>RenderWeave</span>
      </Link>
      <nav className="studio-breadcrumb" aria-label="面包屑">
        <Link to="/schemas">数据结构设计</Link>
        <ChevronRight aria-hidden="true" size={14} />
        <strong aria-current="page">{session.revision === null ? '新建' : session.displayName.trim() || session.schemaKey}</strong>
        <span
          className={`breadcrumb-status ${session.dirty ? 'is-dirty' : ''} ${blockerCount > 0 ? 'is-blocked' : ''}`}
          aria-live="polite"
          title={blockerCount > 0 ? `${blockerCount} 项本地规则未通过` : status}
        >
          {saving
            ? <LoaderCircle className="spin" aria-hidden="true" size={13} />
            : blockerCount > 0
              ? <CircleAlert aria-hidden="true" size={13} />
              : <Check aria-hidden="true" size={13} />}
          {blockerCount > 0 ? `${blockerCount} 项待修正` : status}
        </span>
      </nav>
      <div className="chrome-actions studio-chrome-actions">{actions}</div>
    </header>
  );
}

export function StudioEditTools({
  session,
  onUndo,
  onRedo,
  onRestore,
  onOpenInspector,
}: StudioEditToolsProps) {
  return (
    <div className="studio-edit-tools">
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
        <PanelRightOpen aria-hidden="true" size={16} />字段检查器
      </button>
    </div>
  );
}

export function StudioRail({ session }: { session: EditorSession }) {
  const references = session.fields.filter((field) =>
    field.value.type === 'reference'
    || (field.value.type === 'array' && field.value.items.type === 'reference')).length;
  return (
    <nav className="resource-rail studio-rail" aria-label="Schema 工作区导航">
      <Link className="rail-link" to="/schemas">
        <ListTree aria-hidden="true" size={17} />
        数据结构设计
      </Link>
      <Link className="rail-link active" to={session.revision === null ? '/schemas/new' : `/schemas/${session.schemaKey}`} aria-current="page">
        <Braces aria-hidden="true" size={17} />
        当前 Draft
      </Link>
      <Link className="rail-link rail-secondary-link" to="/static-schemas">
        <Layers3 aria-hidden="true" size={17} />数据结构资产
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
          {' · '}{session.fields.length} 个字段
        </small>
      </div>
      <div className="rail-facts" aria-label="当前 Schema 摘要">
        <span><History aria-hidden="true" size={14} />{session.undoStack.length} 步可撤销</span>
        <span><Braces aria-hidden="true" size={14} />{references} 个引用字段</span>
      </div>
      <div className="rail-note">
        <strong>结构设计 · v1</strong>
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
