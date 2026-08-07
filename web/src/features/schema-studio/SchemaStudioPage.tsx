import * as Dialog from '@radix-ui/react-dialog';
import { useMutation, useQuery } from '@tanstack/react-query';
import {
  AlertCircle,
  CheckCircle2,
  Download,
  LoaderCircle,
  PanelRightOpen,
  RefreshCw,
  Search,
} from 'lucide-react';
import {
  useEffect,
  useMemo,
  useReducer,
  useRef,
  useState,
  type Dispatch,
} from 'react';
import { Link, useBlocker, useNavigate, useParams } from 'react-router-dom';

import type { Problem } from '../../api/generated';
import { FormSurface, MapSurface } from './EditorSurfaces';
import { DraftLifecyclePanel } from './DraftLifecyclePanel';
import { FieldInspector } from './FieldInspector';
import { diffDraftDefinitions } from './definition-diff';
import {
  StudioRequestError,
  createDraftSnapshotRequest,
  getDraftSnapshotRequest,
  saveDraftSnapshotRequest,
} from './lossless-api';
import {
  createNewEditorSession,
  editorReducer,
  sessionFromDraft,
  type EditorAction,
  type EditorSession,
} from './editor-session';
import { editorTypeLabels, serializeDefinition, type DraftSnapshot } from './editor-types';
import { localDiagnostics, type EditorDiagnostic } from './editor-validation';
import { StudioChrome, StudioRail, StudioViewToggle } from './StudioFrame';

type Feedback = {
  tone: 'success' | 'error' | 'conflict';
  title: string;
  detail: string;
  problem?: Problem;
};

export function SchemaStudioPage() {
  const { schemaKey } = useParams<{ schemaKey: string }>();
  if (!schemaKey) return <SchemaStudioWorkspace />;
  return <PersistedDraftLoader schemaKey={schemaKey} />;
}

function PersistedDraftLoader({ schemaKey }: { schemaKey: string }) {
  const query = useQuery({
    queryKey: ['schema-draft', schemaKey],
    queryFn: () => getDraftSnapshotRequest(schemaKey),
    retry: false,
  });

  if (query.isPending) {
    return (
      <div className="studio-route-state" role="status">
        <LoaderCircle className="spin" aria-hidden="true" size={22} />
        <strong>正在读取 Draft</strong>
        <span>{schemaKey}</span>
      </div>
    );
  }
  if (query.isError || !query.data) {
    const message = query.error instanceof StudioRequestError
      ? query.error.problem.detail ?? query.error.problem.title
      : '无法连接 RenderWeave API。';
    return (
      <div className="studio-route-state error-state" role="alert">
        <AlertCircle aria-hidden="true" size={22} />
        <strong>无法打开 Draft</strong>
        <span>{message}</span>
        <div>
          <button type="button" className="button ghost-button" onClick={() => void query.refetch()}>
            <RefreshCw aria-hidden="true" size={16} />重试
          </button>
          <Link className="button primary-button" to="/schemas/new">新建 Draft</Link>
        </div>
      </div>
    );
  }
  return <SchemaStudioWorkspace key={schemaKey} initialDraft={query.data} />;
}

function SchemaStudioWorkspace({ initialDraft }: { initialDraft?: DraftSnapshot }) {
  const navigate = useNavigate();
  const allowNavigation = useRef(false);
  const [session, dispatch] = useReducer(
    editorReducer,
    initialDraft,
    (draft) => draft ? sessionFromDraft(draft) : createNewEditorSession(),
  );
  const [search, setSearch] = useState('');
  const [inspectorOpen, setInspectorOpen] = useState(false);
  const [submitAttempted, setSubmitAttempted] = useState(false);
  const [touched, setTouched] = useState<Set<string>>(() => new Set());
  const [feedback, setFeedback] = useState<Feedback>();
  const [reloading, setReloading] = useState(false);
  const [conflictServer, setConflictServer] = useState<DraftSnapshot>();

  const diagnostics = useMemo(() => localDiagnostics(session), [session]);
  const definitionPreview = useMemo(
    () => previewDefinition(session),
    [session],
  );
  const selectedPosition = session.fields.findIndex((field) => field.rowKey === session.selectedRowKey);
  const selectedIndex = selectedPosition >= 0 ? selectedPosition : 0;
  const selectedField = session.fields[selectedIndex];
  const conflictDiffs = useMemo(
    () => conflictServer ? diffDraftDefinitions(session, conflictServer) : [],
    [conflictServer, session],
  );
  const blocker = useBlocker(({ currentLocation, nextLocation }) =>
    session.dirty
    && !allowNavigation.current
    && `${currentLocation.pathname}${currentLocation.search}` !== `${nextLocation.pathname}${nextLocation.search}`);

  useEffect(() => {
    const guard = (event: BeforeUnloadEvent) => {
      if (!session.dirty) return;
      event.preventDefault();
      event.returnValue = '';
    };
    window.addEventListener('beforeunload', guard);
    return () => window.removeEventListener('beforeunload', guard);
  }, [session.dirty]);

  useEffect(() => {
    const keyboardHistory = (event: KeyboardEvent) => {
      if (!(event.ctrlKey || event.metaKey) || event.altKey) return;
      const key = event.key.toLocaleLowerCase('en-US');
      if (key === 'z' && event.shiftKey) {
        event.preventDefault();
        dispatch({ type: 'redo' });
      } else if (key === 'z') {
        event.preventDefault();
        dispatch({ type: 'undo' });
      } else if (key === 'y') {
        event.preventDefault();
        dispatch({ type: 'redo' });
      }
    };
    document.addEventListener('keydown', keyboardHistory);
    return () => document.removeEventListener('keydown', keyboardHistory);
  }, []);

  const saveMutation = useMutation({
    mutationFn: () => {
      const definitionJson = serializeDefinition(session.displayName, session.description, session.fields);
      return session.revision === null
        ? createDraftSnapshotRequest(session.schemaKey, definitionJson)
        : saveDraftSnapshotRequest(session.schemaKey, session.revision, definitionJson);
    },
    onSuccess: (draft) => {
      const firstCreate = session.revision === null;
      dispatch({ type: 'accept-save', draft });
      setSubmitAttempted(false);
      setConflictServer(undefined);
      setTouched(new Set());
      setFeedback({
        tone: 'success',
        title: `revision ${draft.revision} 已保存`,
        detail: '服务端已持久化完整 definition snapshot；当前撤销历史仍然保留。',
      });
      if (firstCreate) {
        allowNavigation.current = true;
        navigate(`/schemas/${draft.schemaKey}`, { replace: true });
        window.setTimeout(() => { allowNavigation.current = false; }, 0);
      }
    },
    onError: (error) => {
      const problem = error instanceof StudioRequestError ? error.problem : undefined;
      const conflict = problem?.code === 'REVISION_CONFLICT';
      setFeedback({
        tone: conflict ? 'conflict' : 'error',
        title: conflict ? '检测到 revision 冲突，本地内容已保留' : 'Draft 未保存',
        detail: problem?.detail ?? (error instanceof Error ? error.message : '请求失败，请稍后重试。'),
        ...(problem ? { problem } : {}),
      });
      if (problem?.violations?.length) {
        selectProblem(problem.violations[0]?.pointer, session.fields, dispatch, setInspectorOpen);
      }
      if (conflict) {
        void getDraftSnapshotRequest(session.schemaKey)
          .then(setConflictServer)
          .catch(() => setConflictServer(undefined));
      }
    },
  });

  const save = () => {
    setSubmitAttempted(true);
    setFeedback(undefined);
    if (diagnostics.length > 0) {
      const first = diagnostics[0];
      if (first?.rowKey) {
        dispatch({ type: 'select-field', rowKey: first.rowKey });
        setInspectorOpen(true);
      }
      setFeedback({
        tone: 'error',
        title: `还有 ${diagnostics.length} 项需要处理`,
        detail: first?.message ?? '修正本地规则后再保存。',
      });
      focusProblem(first);
      return;
    }
    try {
      serializeDefinition(session.displayName, session.description, session.fields);
      saveMutation.mutate();
    } catch (error) {
      setFeedback({
        tone: 'error',
        title: 'Definition 暂时无法序列化',
        detail: error instanceof Error ? error.message : '请检查约束输入。',
      });
    }
  };

  const reloadServer = async () => {
    if (session.revision === null) return;
    setReloading(true);
    try {
      const draft = await getDraftSnapshotRequest(session.schemaKey);
      dispatch({ type: 'reload-draft', draft });
      setConflictServer(undefined);
      setFeedback({
        tone: 'success',
        title: `已载入服务端 revision ${draft.revision}`,
        detail: '本地未保存内容已由服务端 snapshot 替换；撤销与重做历史已清空。',
      });
    } catch (error) {
      setFeedback({
        tone: 'error',
        title: '重新载入失败',
        detail: error instanceof Error ? error.message : '请求失败，请稍后重试。',
      });
    } finally {
      setReloading(false);
    }
  };

  const touch = (pointer: string) => setTouched((current) => new Set(current).add(pointer));
  const showProblem = (pointer: string) =>
    diagnostics.some((item) => item.pointer === pointer)
    && (submitAttempted || [...touched].some((entry) => entry.startsWith(pointer) || pointer.startsWith(entry)));
  const selectField = (rowKey: string) => {
    dispatch({ type: 'select-field', rowKey });
    setInspectorOpen(true);
  };
  const addField = () => {
    dispatch({ type: 'add-field' });
    setInspectorOpen(true);
  };
  const typeCounts = countTypes(session);

  return (
    <div data-product="schema-studio" className="studio-shell">
      <a className="skip-link" href="#main-content">跳到主要内容</a>
      <StudioChrome
        session={session}
        saving={saveMutation.isPending}
        blockerCount={diagnostics.length}
        onSave={save}
        onUndo={() => dispatch({ type: 'undo' })}
        onRedo={() => dispatch({ type: 'redo' })}
        onRestore={() => dispatch({ type: 'restore-saved' })}
        onOpenInspector={() => setInspectorOpen(true)}
      />
      <div className="studio-body">
        <StudioRail session={session} />
        <main className="studio-workspace" id="main-content" tabIndex={-1}>
          <div className="studio-title-row">
            <div>
              <span className="eyebrow">SCHEMA DRAFT · {session.revision === null ? 'NEW' : `REVISION ${session.revision}`}</span>
              <h1>{session.displayName.trim() || '新建 Schema Draft'}</h1>
              <p>定义字段、约束与引用关系；本地即时检查可读规则，服务端在显式保存时校验完整引用图。</p>
            </div>
            <div className="studio-revision-card"><span>revision</span><strong>{session.revision ?? '—'}</strong></div>
          </div>

          {feedback && <FeedbackBanner feedback={feedback} reloading={reloading} conflictRevision={conflictServer?.revision} conflictDiffs={conflictDiffs} onReload={() => void reloadServer()} onExport={() => exportDefinition(session)} />}

          <section className="schema-identity-card" aria-labelledby="schema-identity-heading">
            <div className="section-heading">
              <div><span>IDENTITY & METADATA</span><h2 id="schema-identity-heading">Schema 基本信息</h2></div>
              <span>{session.revision === null ? 'schemaKey 创建后不可修改' : 'schemaKey 已锁定'}</span>
            </div>
            <div className="identity-grid">
              <div className="control-group">
                <label htmlFor="schema-key">schemaKey</label>
                <input
                  id="schema-key"
                  className="mono-input"
                  data-pointer="/schemaKey"
                  value={session.schemaKey}
                  readOnly={session.revision !== null}
                  aria-readonly={session.revision !== null}
                  aria-invalid={showProblem('/schemaKey')}
                  onChange={(event) => dispatch({ type: 'set-schema-key', value: event.target.value, historyGroup: 'schemaKey' })}
                  onBlur={() => { touch('/schemaKey'); dispatch({ type: 'commit-history-group' }); }}
                />
                {showProblem('/schemaKey') && <ProblemText diagnostics={diagnostics} pointer="/schemaKey" />}
                <p className="control-help">小写字母、数字和连字符；永久稳定，不是显示名称。</p>
              </div>
              <div className="control-group">
                <label htmlFor="schema-display-name">显示名称</label>
                <input
                  id="schema-display-name"
                  data-pointer="/definition/displayName"
                  value={session.displayName}
                  aria-invalid={showProblem('/definition/displayName')}
                  onChange={(event) => dispatch({ type: 'set-display-name', value: event.target.value, historyGroup: 'displayName' })}
                  onBlur={() => { touch('/definition/displayName'); dispatch({ type: 'commit-history-group' }); }}
                />
                {showProblem('/definition/displayName') && <ProblemText diagnostics={diagnostics} pointer="/definition/displayName" />}
                <p className="control-help">面向人的名称，保存时去除首尾空白。</p>
              </div>
              <div className="control-group identity-description">
                <label htmlFor="schema-description">用途说明（可选）</label>
                <textarea
                  id="schema-description"
                  rows={2}
                  data-pointer="/definition/description"
                  value={session.description}
                  aria-invalid={showProblem('/definition/description')}
                  onChange={(event) => dispatch({ type: 'set-description', value: event.target.value, historyGroup: 'description' })}
                  onBlur={() => { touch('/definition/description'); dispatch({ type: 'commit-history-group' }); }}
                />
              </div>
            </div>
          </section>

          <div className="studio-toolbar">
            <StudioViewToggle view={session.view} onChange={(view) => dispatch({ type: 'set-view', view })} />
            <label className="studio-search">
              <Search aria-hidden="true" size={16} />
              <span className="sr-only">搜索 fieldKey、显示名称、说明或类型</span>
              <input type="search" value={search} placeholder="搜索字段、说明或类型" onChange={(event) => setSearch(event.target.value)} />
            </label>
            <div className="studio-density-summary">
              <span>{session.fields.length} fields</span>
              <span>{typeCounts}</span>
              <span>additionalProperties=true</span>
            </div>
            <button type="button" className="button ghost-button inspector-trigger" onClick={() => setInspectorOpen(true)}>
              <PanelRightOpen aria-hidden="true" size={16} />检查器
            </button>
          </div>

          <DraftLifecyclePanel session={session} diagnostics={diagnostics} dispatch={dispatch} />

          {submitAttempted && diagnostics.length > 0 && <LocalProblemSummary diagnostics={diagnostics} onSelect={selectField} />}

          {session.view === 'form' ? (
            <FormSurface session={session} diagnostics={diagnostics} search={search} dispatch={dispatch} onSelectField={selectField} onAddField={addField} />
          ) : (
            <MapSurface session={session} diagnostics={diagnostics} search={search} dispatch={dispatch} onSelectField={selectField} onAddField={addField} />
          )}
        </main>
        <FieldInspector
          field={selectedField}
          fieldIndex={selectedIndex}
          allFields={session.fields}
          revision={session.revision}
          dirty={session.dirty}
          diagnostics={diagnostics}
          definitionPreview={definitionPreview}
          open={inspectorOpen}
          dispatch={dispatch}
          onClose={() => setInspectorOpen(false)}
          onAddField={addField}
          onTouch={touch}
          showProblem={showProblem}
        />
      </div>
      <DirtyGuardDialog blocker={blocker} />
      <div className="unsupported-width" role="status">
        <strong>RenderWeave v1 需要至少 1024px 宽度</strong>
        <span>请在桌面端扩大窗口后继续 Schema 设计。</span>
      </div>
    </div>
  );
}

function FeedbackBanner({
  feedback,
  reloading,
  conflictRevision,
  conflictDiffs,
  onReload,
  onExport,
}: {
  feedback: Feedback;
  reloading: boolean;
  conflictRevision?: number;
  conflictDiffs: ReturnType<typeof diffDraftDefinitions>;
  onReload: () => void;
  onExport: () => void;
}) {
  return (
    <section className={`studio-feedback feedback-${feedback.tone}`} role={feedback.tone === 'success' ? 'status' : 'alert'}>
      {feedback.tone === 'success' ? <CheckCircle2 aria-hidden="true" size={18} /> : <AlertCircle aria-hidden="true" size={18} />}
      <div>
        <strong>{feedback.title}</strong><span>{feedback.detail}</span>
        {feedback.problem?.violations?.map((violation) => <code key={`${violation.code}-${violation.pointer}`}>{violation.pointer} · {violation.message ?? violation.code}</code>)}
        {feedback.tone === 'conflict' && conflictRevision !== undefined && (
          <div className="conflict-diff">
            <strong>本地 revision 与服务端 revision {conflictRevision} 的结构化差异</strong>
            {conflictDiffs.length === 0 ? <span>Definition 内容相同；仅 revision 已变化。</span> : (
              <ul>{conflictDiffs.map((diff) => <li key={`${diff.kind}-${diff.path}`}><span className={`diff-kind diff-${diff.kind}`}>{diff.kind}</span><strong>{diff.label}</strong><code>本地：{diff.local}</code><code>服务端：{diff.server}</code></li>)}</ul>
            )}
          </div>
        )}
      </div>
      {feedback.tone === 'conflict' && (
        <div className="conflict-actions">
          <button type="button" className="button ghost-button" onClick={onExport}><Download aria-hidden="true" size={15} />导出本地</button>
          <button type="button" className="button ghost-button" disabled={reloading} onClick={onReload}><RefreshCw className={reloading ? 'spin' : ''} aria-hidden="true" size={15} />载入服务端</button>
        </div>
      )}
    </section>
  );
}

function LocalProblemSummary({ diagnostics, onSelect }: { diagnostics: EditorDiagnostic[]; onSelect: (rowKey: string) => void }) {
  return (
    <section className="local-problem-summary" role="alert" aria-labelledby="local-problem-title">
      <AlertCircle aria-hidden="true" size={18} />
      <div>
        <strong id="local-problem-title">保存前请处理 {diagnostics.length} 项问题</strong>
        <ul>{diagnostics.slice(0, 7).map((problem) => <li key={`${problem.code}-${problem.pointer}`}>{problem.rowKey ? <button type="button" onClick={() => onSelect(problem.rowKey!)}>{problem.message}</button> : problem.message}</li>)}</ul>
      </div>
    </section>
  );
}

function ProblemText({ diagnostics, pointer }: { diagnostics: EditorDiagnostic[]; pointer: string }) {
  return <div className="inline-problems" role="alert">{diagnostics.filter((item) => item.pointer === pointer).map((item) => <span key={`${item.code}-${item.pointer}`}>{item.message}</span>)}</div>;
}

type RouteBlocker = ReturnType<typeof useBlocker>;

function DirtyGuardDialog({ blocker }: { blocker: RouteBlocker }) {
  const blocked = blocker.state === 'blocked';
  return (
    <Dialog.Root open={blocked} onOpenChange={(open) => { if (!open && blocked) blocker.reset?.(); }}>
      <Dialog.Portal>
        <Dialog.Overlay className="dialog-overlay" />
        <Dialog.Content
          className="dialog-content dirty-dialog"
          onEscapeKeyDown={() => blocker.reset?.()}
          onCloseAutoFocus={(event) => {
            event.preventDefault();
            document.querySelector<HTMLElement>('#main-content')?.focus({ preventScroll: true });
          }}
        >
          <Dialog.Title>离开前保存更改？</Dialog.Title>
          <Dialog.Description>当前 Draft 有未保存的语义修改。离开会丢失这些修改，但不会影响服务端最近保存的 revision。</Dialog.Description>
          <div className="dialog-actions">
            <button type="button" className="button ghost-button" onClick={() => blocker.reset?.()}>继续编辑</button>
            <button type="button" className="button danger-button" onClick={() => blocker.proceed?.()}>放弃并离开</button>
          </div>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}

function previewDefinition(session: EditorSession): string {
  try {
    return serializeDefinition(session.displayName, session.description, session.fields, true);
  } catch {
    return '// 当前约束含未完成的数值输入；修正后将显示无损 Definition DSL。';
  }
}

function exportDefinition(session: EditorSession) {
  const content = previewDefinition(session);
  const url = URL.createObjectURL(new Blob([content], { type: 'application/json;charset=utf-8' }));
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = `${session.schemaKey || 'schema-draft'}-local.json`;
  anchor.click();
  URL.revokeObjectURL(url);
}

function countTypes(session: EditorSession): string {
  const counts = new Map<string, number>();
  session.fields.forEach((field) => counts.set(field.value.type, (counts.get(field.value.type) ?? 0) + 1));
  return [...counts.entries()].map(([type, count]) => `${editorTypeLabels[type as keyof typeof editorTypeLabels]} ${count}`).join(' · ') || '空 Schema';
}

function focusProblem(problem: EditorDiagnostic | undefined) {
  if (!problem) return;
  window.requestAnimationFrame(() => {
    const controls = document.querySelectorAll<HTMLElement>('[data-pointer]');
    [...controls].find((element) => element.dataset.pointer === problem.pointer)?.focus();
  });
}

function selectProblem(
  pointer: string | undefined,
  fields: { rowKey: string }[],
  dispatch: Dispatch<EditorAction>,
  openInspector: (open: boolean) => void,
) {
  const match = pointer?.match(/^\/definition\/fields\/(\d+)/);
  if (!match) return;
  const field = fields[Number(match[1])];
  if (!field) return;
  dispatch({ type: 'select-field', rowKey: field.rowKey });
  openInspector(true);
}
