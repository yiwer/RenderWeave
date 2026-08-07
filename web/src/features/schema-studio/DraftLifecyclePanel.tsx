import * as Dialog from '@radix-ui/react-dialog';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Archive,
  CopyPlus,
  Eye,
  History,
  LoaderCircle,
  Rocket,
  RotateCcw,
  Trash2,
} from 'lucide-react';
import { useState, type Dispatch } from 'react';
import { useNavigate } from 'react-router-dom';

import { listDraftHistoryRequest } from '../resources/resource-api';
import {
  StudioRequestError,
  copyDraftSnapshotRequest,
  deleteDraftRequest,
  getDraftRevisionSnapshotRequest,
  publishStaticSnapshotRequest,
  restoreDraftSnapshotRequest,
  type DraftRevisionSnapshot,
} from './lossless-api';
import { formatDateTime } from '../resources/resource-format';
import type { EditorAction, EditorSession } from './editor-session';
import { editorValueFromPersisted, serializeDefinition } from './editor-types';
import type { EditorDiagnostic } from './editor-validation';

export function DraftLifecyclePanel({
  session,
  diagnostics,
  dispatch,
}: {
  session: EditorSession;
  diagnostics: EditorDiagnostic[];
  dispatch: Dispatch<EditorAction>;
}) {
  const saved = session.revision !== null;
  const draftRefs = countDraftReferences(session);
  const publishReady = saved && !session.dirty && diagnostics.length === 0 && draftRefs === 0;
  return (
    <section className="draft-lifecycle-bar" aria-label="Draft 生命周期操作">
      <div><span>LIFECYCLE</span><strong>{saved ? `revision ${session.revision}` : '尚未创建'}</strong></div>
      <HistoryDialog session={session} dispatch={dispatch} />
      <CopyDraftDialog session={session} />
      <PublishDialog session={session} ready={publishReady} blockerCount={diagnostics.length} draftRefs={draftRefs} />
      <DeleteDraftDialog session={session} />
    </section>
  );
}

function HistoryDialog({ session, dispatch }: { session: EditorSession; dispatch: Dispatch<EditorAction> }) {
  const [open, setOpen] = useState(false);
  const [selectedRevision, setSelectedRevision] = useState<number | null>(null);
  const queryClient = useQueryClient();
  const history = useQuery({
    queryKey: ['draft-history', session.schemaKey],
    queryFn: () => listDraftHistoryRequest(session.schemaKey, 1, 100),
    enabled: open && session.revision !== null,
  });
  const snapshot = useQuery({
    queryKey: ['draft-revision', session.schemaKey, selectedRevision],
    queryFn: () => getDraftRevisionSnapshotRequest(session.schemaKey, selectedRevision!),
    enabled: open && selectedRevision !== null,
  });
  const restore = useMutation({
    mutationFn: (sourceRevision: number) => restoreDraftSnapshotRequest(session.schemaKey, session.revision!, sourceRevision),
    onSuccess: (draft) => {
      queryClient.setQueryData(['schema-draft', draft.schemaKey], draft);
      dispatch({ type: 'reload-draft', draft });
      void queryClient.invalidateQueries({ queryKey: ['draft-history', session.schemaKey] });
      void queryClient.invalidateQueries({ queryKey: ['schema-drafts'] });
      setOpen(false);
    },
  });
  return (
    <Dialog.Root open={open} onOpenChange={(value) => { setOpen(value); if (!value) setSelectedRevision(null); }}>
      <Dialog.Trigger asChild><button type="button" className="lifecycle-action" disabled={session.revision === null}><History aria-hidden="true" size={15} />历史</button></Dialog.Trigger>
      <Dialog.Portal><Dialog.Overlay className="dialog-overlay" /><Dialog.Content className="dialog-content lifecycle-dialog history-dialog">
        <Dialog.Title>不可变 revision 历史</Dialog.Title>
        <Dialog.Description>查看任意完整 snapshot；恢复会把旧定义复制成新的 current revision，不会改写历史。</Dialog.Description>
        {history.isPending && <div className="dialog-loading"><LoaderCircle className="spin" size={18} />读取历史…</div>}
        {history.isError && <DialogProblem error={history.error} />}
        {history.data && (
          <div className="history-layout">
            <div className="history-list">
              {history.data.items.map((item) => (
                <button type="button" key={item.revision} className={selectedRevision === item.revision ? 'active' : ''} onClick={() => setSelectedRevision(item.revision)}>
                  <span><strong>revision {item.revision}</strong>{item.revision === session.revision && <i>CURRENT</i>}</span>
                  <span>{item.displayName}</span><small>{item.fieldCount} fields · {formatDateTime(item.savedAt)}</small>
                </button>
              ))}
            </div>
            <div className="history-preview">
              {selectedRevision === null && <div><Eye aria-hidden="true" size={22} /><span>选择一个 revision 查看 Definition DSL</span></div>}
              {snapshot.isPending && selectedRevision !== null && <div><LoaderCircle className="spin" size={18} /><span>读取 snapshot…</span></div>}
              {snapshot.data && <pre>{revisionPreview(snapshot.data)}</pre>}
            </div>
          </div>
        )}
        {restore.isError && <DialogProblem error={restore.error} />}
        <div className="dialog-actions"><Dialog.Close asChild><button type="button" className="button ghost-button">关闭</button></Dialog.Close><button type="button" className="button primary-button" disabled={selectedRevision === null || selectedRevision === session.revision || session.dirty || restore.isPending} onClick={() => restore.mutate(selectedRevision!)}><RotateCcw aria-hidden="true" size={15} />恢复为新 revision</button></div>
        {session.dirty && <p className="dialog-footnote">先保存或恢复最近保存内容，才能执行服务端 revision restore。</p>}
      </Dialog.Content></Dialog.Portal>
    </Dialog.Root>
  );
}

function CopyDraftDialog({ session }: { session: EditorSession }) {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [open, setOpen] = useState(false);
  const [schemaKey, setSchemaKey] = useState('');
  const [displayName, setDisplayName] = useState('');
  const mutation = useMutation({
    mutationFn: () => copyDraftSnapshotRequest(session.schemaKey, schemaKey, displayName),
    onSuccess: (draft) => {
      queryClient.setQueryData(['schema-draft', draft.schemaKey], draft);
      void queryClient.invalidateQueries({ queryKey: ['schema-drafts'] });
      navigate(`/schemas/${draft.schemaKey}`);
    },
  });
  return (
    <Dialog.Root open={open} onOpenChange={(value) => { setOpen(value); if (value && !displayName) setDisplayName(`${session.displayName.trim()} 副本`); }}>
      <Dialog.Trigger asChild><button type="button" className="lifecycle-action" disabled={session.revision === null}><CopyPlus aria-hidden="true" size={15} />复制</button></Dialog.Trigger>
      <Dialog.Portal><Dialog.Overlay className="dialog-overlay" /><Dialog.Content className="dialog-content lifecycle-dialog">
        <Dialog.Title>复制 current Draft</Dialog.Title><Dialog.Description>复制服务端最近保存的 revision；不会递归复制依赖，也不会包含本地未保存修改。</Dialog.Description>
        <div className="dialog-form"><label>新 schemaKey<input className="mono-input" value={schemaKey} onChange={(event) => setSchemaKey(event.target.value)} /></label><label>显示名称<input value={displayName} onChange={(event) => setDisplayName(event.target.value)} /></label></div>
        {mutation.isError && <DialogProblem error={mutation.error} />}
        <div className="dialog-actions"><Dialog.Close asChild><button className="button ghost-button" type="button">取消</button></Dialog.Close><button className="button primary-button" type="button" disabled={!schemaKey || !displayName.trim() || mutation.isPending} onClick={() => mutation.mutate()}>创建副本</button></div>
      </Dialog.Content></Dialog.Portal>
    </Dialog.Root>
  );
}

function PublishDialog({ session, ready, blockerCount, draftRefs }: { session: EditorSession; ready: boolean; blockerCount: number; draftRefs: number }) {
  const navigate = useNavigate();
  const [open, setOpen] = useState(false);
  const [versionTag, setVersionTag] = useState('');
  const [releaseNote, setReleaseNote] = useState('');
  const mutation = useMutation({
    mutationFn: () => publishStaticSnapshotRequest(session.schemaKey, session.revision!, versionTag, releaseNote),
    onSuccess: (snapshot) => navigate(`/static-schemas/${snapshot.schemaKey}/${snapshot.versionTag}`),
  });
  return (
    <Dialog.Root open={open} onOpenChange={setOpen}>
      <Dialog.Trigger asChild><button type="button" className={`lifecycle-action lifecycle-publish ${ready ? 'is-ready' : ''}`} disabled={session.revision === null}><Rocket aria-hidden="true" size={15} />发布</button></Dialog.Trigger>
      <Dialog.Portal><Dialog.Overlay className="dialog-overlay" /><Dialog.Content className="dialog-content lifecycle-dialog">
        <Dialog.Title>发布不可变 StaticSchema</Dialog.Title><Dialog.Description>只消费当前 exact saved revision；不会隐式保存，也不会自动选择 latest。</Dialog.Description>
        <ul className="publish-checklist"><li className={!session.dirty && session.revision !== null ? 'done' : ''}>{session.dirty ? '存在未保存修改' : `revision ${session.revision ?? '—'} 已保存`}</li><li className={blockerCount === 0 ? 'done' : ''}>{blockerCount === 0 ? '本地规则通过' : `${blockerCount} 项定义问题`}</li><li className={draftRefs === 0 ? 'done' : ''}>{draftRefs === 0 ? '没有 live Draft 引用' : `${draftRefs} 个 SchemaRef 需要 versionTag`}</li></ul>
        <div className="dialog-form"><label>versionTag<input className="mono-input" placeholder="例如 v1 或 2026-08" value={versionTag} onChange={(event) => setVersionTag(event.target.value)} /></label><label>release note（可选）<textarea rows={3} value={releaseNote} onChange={(event) => setReleaseNote(event.target.value)} /></label></div>
        {mutation.isError && <DialogProblem error={mutation.error} />}
        <div className="dialog-actions"><Dialog.Close asChild><button className="button ghost-button" type="button">取消</button></Dialog.Close><button className="button primary-button" type="button" disabled={!ready || !/^[a-z0-9][a-z0-9._-]{0,63}$/.test(versionTag) || mutation.isPending} onClick={() => mutation.mutate()}><Archive aria-hidden="true" size={15} />原子发布</button></div>
      </Dialog.Content></Dialog.Portal>
    </Dialog.Root>
  );
}

function DeleteDraftDialog({ session }: { session: EditorSession }) {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [open, setOpen] = useState(false);
  const mutation = useMutation({
    mutationFn: () => deleteDraftRequest(session.schemaKey, session.revision!),
    onSuccess: () => {
      queryClient.removeQueries({ queryKey: ['schema-draft', session.schemaKey], exact: true });
      void queryClient.invalidateQueries({ queryKey: ['schema-drafts'] });
      navigate('/schemas', { replace: true, state: { deletedDraft: { schemaKey: session.schemaKey, revision: session.revision, displayName: session.displayName } } });
    },
  });
  return (
    <Dialog.Root open={open} onOpenChange={setOpen}>
      <Dialog.Trigger asChild><button type="button" className="lifecycle-action lifecycle-delete" disabled={session.revision === null || session.dirty}><Trash2 aria-hidden="true" size={15} />删除</button></Dialog.Trigger>
      <Dialog.Portal><Dialog.Overlay className="dialog-overlay" /><Dialog.Content className="dialog-content lifecycle-dialog">
        <Dialog.Title>软删除 {session.schemaKey}</Dialog.Title><Dialog.Description>默认列表和引用选择器将隐藏该 Draft；revision、provenance 与 schemaKey tombstone 永久保留。有 active incoming Draft 引用时服务端会拒绝。</Dialog.Description>
        {mutation.isError && <DialogProblem error={mutation.error} />}
        <div className="dialog-actions"><Dialog.Close asChild><button className="button ghost-button" type="button">取消</button></Dialog.Close><button className="button danger-button" type="button" disabled={mutation.isPending} onClick={() => mutation.mutate()}><Trash2 aria-hidden="true" size={15} />确认软删除</button></div>
      </Dialog.Content></Dialog.Portal>
    </Dialog.Root>
  );
}

function DialogProblem({ error }: { error: unknown }) {
  const message = error instanceof StudioRequestError ? error.problem.detail ?? error.problem.title : error instanceof Error ? error.message : '请求失败';
  return <p className="dialog-error" role="alert">{message}</p>;
}

function revisionPreview(snapshot: DraftRevisionSnapshot): string {
  const fields = snapshot.definition.fields.map((field, index) => ({
    rowKey: `preview-${index}`, fieldKey: field.fieldKey, displayName: field.displayName ?? '',
    description: field.description ?? '', required: field.required, value: editorValueFromPersisted(field.value),
  }));
  return serializeDefinition(snapshot.definition.displayName, snapshot.definition.description ?? '', fields, true);
}

function countDraftReferences(session: EditorSession): number {
  return session.fields.filter((field) => {
    const value = field.value.type === 'array' ? field.value.items : field.value;
    return value.type === 'reference' && value.referenceKind === 'draft';
  }).length;
}
