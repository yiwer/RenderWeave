import * as Dialog from '@radix-ui/react-dialog';
import * as Tabs from '@radix-ui/react-tabs';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Braces,
  Eye,
  List,
  ListTree,
  LoaderCircle,
  RotateCcw,
  Trash2,
} from 'lucide-react';
import { useState, type ReactElement } from 'react';
import { useNavigate } from 'react-router-dom';

import { listDraftHistoryRequest } from '../resources/resource-api';
import {
  StudioRequestError,
  copyDraftSnapshotRequest,
  deleteDraftRequest,
  getDraftRevisionSnapshotRequest,
  restoreDraftSnapshotRequest,
  type DraftRevisionSnapshot,
} from './lossless-api';
import { formatDateTime } from '../resources/resource-format';
import { editorValueFromPersisted, serializeDefinition, type DraftSnapshot } from './editor-types';
import { ReadonlyDefinitionForm, ReadonlyDefinitionTree } from './ReadonlyDefinitionViews';

export function DraftHistoryDialog({
  schemaKey,
  currentRevision,
  dirty = false,
  trigger,
  onRestored,
}: {
  schemaKey: string;
  currentRevision: number;
  dirty?: boolean;
  trigger: ReactElement;
  onRestored?: (draft: DraftSnapshot) => void;
}) {
  const [open, setOpen] = useState(false);
  const [selectedRevision, setSelectedRevision] = useState<number | null>(null);
  const [previewView, setPreviewView] = useState<HistoryPreviewView>('tree');
  const queryClient = useQueryClient();
  const history = useQuery({
    queryKey: ['draft-history', schemaKey],
    queryFn: () => listDraftHistoryRequest(schemaKey, 1, 100),
    enabled: open,
  });
  const snapshot = useQuery({
    queryKey: ['draft-revision', schemaKey, selectedRevision],
    queryFn: () => getDraftRevisionSnapshotRequest(schemaKey, selectedRevision!),
    enabled: open && selectedRevision !== null,
  });
  const restore = useMutation({
    mutationFn: (sourceRevision: number) => restoreDraftSnapshotRequest(schemaKey, currentRevision, sourceRevision),
    onSuccess: (draft) => {
      queryClient.setQueryData(['schema-draft', draft.schemaKey], draft);
      onRestored?.(draft);
      void queryClient.invalidateQueries({ queryKey: ['draft-history', schemaKey] });
      void queryClient.invalidateQueries({ queryKey: ['schema-drafts'] });
      setOpen(false);
    },
  });
  return (
    <Dialog.Root
      open={open}
      onOpenChange={(value) => {
        setOpen(value);
        setSelectedRevision(value ? currentRevision : null);
        if (value) setPreviewView('tree');
      }}
    >
      <Dialog.Trigger asChild>{trigger}</Dialog.Trigger>
      <Dialog.Portal><Dialog.Overlay className="dialog-overlay" /><Dialog.Content className="dialog-content lifecycle-dialog history-dialog">
        <Dialog.Title>不可变 revision 历史</Dialog.Title>
        <Dialog.Description>查看任意完整 snapshot；恢复会把旧定义复制成新的 current revision，不会改写历史。</Dialog.Description>
        {history.isPending && <div className="dialog-loading"><LoaderCircle className="spin" size={18} />读取历史…</div>}
        {history.isError && <DialogProblem error={history.error} />}
        {history.data && (
          <div className="history-layout">
            <div className="history-list">
              {history.data.items.map((item) => (
                <button
                  type="button"
                  key={item.revision}
                  className={selectedRevision === item.revision ? 'active' : ''}
                  aria-pressed={selectedRevision === item.revision}
                  onClick={() => setSelectedRevision(item.revision)}
                >
                  <span><strong>revision {item.revision}</strong>{item.revision === currentRevision && <i>当前</i>}</span>
                  <span>{item.displayName}</span><small>{item.fieldCount} 个字段 · {formatDateTime(item.savedAt)}</small>
                </button>
              ))}
            </div>
            <div className="history-preview">
              {selectedRevision === null && <div><Eye aria-hidden="true" size={22} /><span>选择一个 revision 查看完整定义</span></div>}
              {snapshot.isPending && selectedRevision !== null && <div><LoaderCircle className="spin" size={18} /><span>读取 snapshot…</span></div>}
              {snapshot.data && (
                <Tabs.Root className="history-preview-views" value={previewView} onValueChange={(value) => setPreviewView(value as HistoryPreviewView)}>
                  <header className="history-preview-toolbar">
                    <span><strong>revision {snapshot.data.revision}</strong><small>{snapshot.data.definition.fields.length} 个字段 · 完整只读 snapshot</small></span>
                    <Tabs.List className="readonly-view-tabs" aria-label="revision 查看方式">
                      <Tabs.Trigger value="tree"><ListTree aria-hidden="true" size={14} />字段树</Tabs.Trigger>
                      <Tabs.Trigger value="form"><List aria-hidden="true" size={14} />字段表单</Tabs.Trigger>
                      <Tabs.Trigger value="dsl"><Braces aria-hidden="true" size={14} />DSL JSON</Tabs.Trigger>
                    </Tabs.List>
                  </header>
                  <Tabs.Content className="history-preview-panel" value="tree">
                    <ReadonlyDefinitionTree schemaKey={snapshot.data.schemaKey} definition={snapshot.data.definition} />
                  </Tabs.Content>
                  <Tabs.Content className="history-preview-panel" value="form">
                    <ReadonlyDefinitionForm definition={snapshot.data.definition} />
                  </Tabs.Content>
                  <Tabs.Content className="history-preview-panel history-dsl-panel" value="dsl">
                    <pre>{revisionPreview(snapshot.data)}</pre>
                  </Tabs.Content>
                </Tabs.Root>
              )}
            </div>
          </div>
        )}
        {restore.isError && <DialogProblem error={restore.error} />}
        <div className="dialog-actions"><Dialog.Close asChild><button type="button" className="button ghost-button">关闭</button></Dialog.Close><button type="button" className="button primary-button" disabled={selectedRevision === null || selectedRevision === currentRevision || dirty || restore.isPending} onClick={() => restore.mutate(selectedRevision!)}><RotateCcw aria-hidden="true" size={15} />恢复为新 revision</button></div>
        {dirty && <p className="dialog-footnote">先保存或恢复最近保存内容，才能执行服务端 revision restore。</p>}
      </Dialog.Content></Dialog.Portal>
    </Dialog.Root>
  );
}

type HistoryPreviewView = 'tree' | 'form' | 'dsl';

export function CopyDraftDialog({
  schemaKey: sourceSchemaKey,
  displayName: sourceDisplayName,
  trigger,
}: {
  schemaKey: string;
  displayName: string;
  trigger: ReactElement;
}) {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [open, setOpen] = useState(false);
  const [schemaKey, setSchemaKey] = useState('');
  const [displayName, setDisplayName] = useState('');
  const mutation = useMutation({
    mutationFn: () => copyDraftSnapshotRequest(sourceSchemaKey, schemaKey, displayName),
    onSuccess: (draft) => {
      queryClient.setQueryData(['schema-draft', draft.schemaKey], draft);
      void queryClient.invalidateQueries({ queryKey: ['schema-drafts'] });
      navigate(`/schemas/${draft.schemaKey}`);
    },
  });
  return (
    <Dialog.Root open={open} onOpenChange={(value) => { setOpen(value); if (value && !displayName) setDisplayName(`${sourceDisplayName.trim()} 副本`); }}>
      <Dialog.Trigger asChild>{trigger}</Dialog.Trigger>
      <Dialog.Portal><Dialog.Overlay className="dialog-overlay" /><Dialog.Content className="dialog-content lifecycle-dialog">
        <Dialog.Title>复制 current Draft</Dialog.Title><Dialog.Description>复制服务端最近保存的 revision；不会递归复制依赖，也不会包含本地未保存修改。</Dialog.Description>
        <div className="dialog-form"><label>新 schemaKey<input className="mono-input" value={schemaKey} onChange={(event) => setSchemaKey(event.target.value)} /></label><label>显示名称<input value={displayName} onChange={(event) => setDisplayName(event.target.value)} /></label></div>
        {mutation.isError && <DialogProblem error={mutation.error} />}
        <div className="dialog-actions"><Dialog.Close asChild><button className="button ghost-button" type="button">取消</button></Dialog.Close><button className="button primary-button" type="button" disabled={!schemaKey || !displayName.trim() || mutation.isPending} onClick={() => mutation.mutate()}>创建副本</button></div>
      </Dialog.Content></Dialog.Portal>
    </Dialog.Root>
  );
}

export function DeleteDraftDialog({
  schemaKey,
  revision,
  displayName,
  trigger,
}: {
  schemaKey: string;
  revision: number;
  displayName: string;
  trigger: ReactElement;
}) {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [open, setOpen] = useState(false);
  const mutation = useMutation({
    mutationFn: () => deleteDraftRequest(schemaKey, revision),
    onSuccess: () => {
      queryClient.removeQueries({ queryKey: ['schema-draft', schemaKey], exact: true });
      void queryClient.invalidateQueries({ queryKey: ['schema-drafts'] });
      navigate('/schemas', { replace: true, state: { deletedDraft: { schemaKey, revision, displayName } } });
    },
  });
  return (
    <Dialog.Root open={open} onOpenChange={setOpen}>
      <Dialog.Trigger asChild>{trigger}</Dialog.Trigger>
      <Dialog.Portal><Dialog.Overlay className="dialog-overlay" /><Dialog.Content className="dialog-content lifecycle-dialog">
        <Dialog.Title>软删除 {schemaKey}</Dialog.Title><Dialog.Description>默认列表和引用选择器将隐藏该 Draft；revision、来源记录与 schemaKey tombstone 永久保留。有其他 Draft 正在引用时服务端会拒绝。</Dialog.Description>
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
