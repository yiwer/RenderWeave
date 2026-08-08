import * as Dialog from '@radix-ui/react-dialog';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Archive, LoaderCircle } from 'lucide-react';
import { useState, type ReactElement } from 'react';
import { useNavigate } from 'react-router-dom';

import { StudioRequestError, publishStaticSnapshotRequest } from './lossless-api';

const VERSION_TAG = /^[a-z0-9][a-z0-9._-]{0,63}$/;

export function PublishStaticSchemaDialog({
  trigger,
  schemaKey,
  revision,
  intent,
  dirty = false,
  blockerCount,
  draftRefs,
  onPrepareRevision,
}: {
  trigger: ReactElement;
  schemaKey: string;
  revision: number;
  intent: 'publish' | 'save-and-publish';
  dirty?: boolean;
  blockerCount?: number;
  draftRefs?: number;
  onPrepareRevision?: () => Promise<number | null>;
}) {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [open, setOpen] = useState(false);
  const [versionTag, setVersionTag] = useState('');
  const [releaseNote, setReleaseNote] = useState('');
  const [preparing, setPreparing] = useState(false);
  const mutation = useMutation({
    mutationFn: (exactRevision: number) => publishStaticSnapshotRequest(
      schemaKey,
      exactRevision,
      versionTag,
      releaseNote,
    ),
    onSuccess: (snapshot) => {
      void queryClient.invalidateQueries({ queryKey: ['static-schemas'] });
      navigate(`/static-schemas/${snapshot.schemaKey}/${snapshot.versionTag}`);
    },
  });
  const knownBlockers = (blockerCount ?? 0) > 0 || (draftRefs ?? 0) > 0;
  const pending = preparing || mutation.isPending;

  const publish = async () => {
    setPreparing(true);
    mutation.reset();
    try {
      const exactRevision = onPrepareRevision ? await onPrepareRevision() : revision;
      if (exactRevision === null) return;
      await mutation.mutateAsync(exactRevision);
    } catch {
      // React Query exposes the request problem in mutation.error for the dialog.
    } finally {
      setPreparing(false);
    }
  };

  return (
    <Dialog.Root
      open={open}
      onOpenChange={(value) => {
        setOpen(value);
        if (!value) mutation.reset();
      }}
    >
      <Dialog.Trigger asChild>{trigger}</Dialog.Trigger>
      <Dialog.Portal>
        <Dialog.Overlay className="dialog-overlay" />
        <Dialog.Content className="dialog-content lifecycle-dialog">
          <Dialog.Title>{intent === 'save-and-publish' ? '保存并发布 StaticSchema' : '发布 StaticSchema'}</Dialog.Title>
          <Dialog.Description>
            {intent === 'save-and-publish'
              ? '存在未保存修改时，先显式保存为新 revision，再发布服务端返回的精确 revision。若发布失败，已经保存的 revision 仍会保留。'
              : `发布列表中的 ${schemaKey}@revision:${revision}；服务端会重新校验引用并自底向上编译。`}
          </Dialog.Description>
          <ul className="publish-checklist">
            <li className="done">
              {intent === 'save-and-publish' && dirty
                ? '当前修改将先保存为新 revision'
                : `revision ${revision} 已保存`}
            </li>
            {blockerCount === undefined
              ? <li>服务端将重新检查定义规则</li>
              : <li className={blockerCount === 0 ? 'done' : ''}>{blockerCount === 0 ? '本地规则通过' : `${blockerCount} 项定义问题`}</li>}
            {draftRefs === undefined
              ? <li>服务端将重新检查引用图</li>
              : <li className={draftRefs === 0 ? 'done' : ''}>{draftRefs === 0 ? '没有可变 Draft 引用' : `${draftRefs} 个 SchemaRef 需要 versionTag`}</li>}
          </ul>
          <div className="dialog-form">
            <label>versionTag<input className="mono-input" placeholder="例如 v1 或 2026-08" value={versionTag} onChange={(event) => setVersionTag(event.target.value)} /></label>
            <label>发布说明（可选）<textarea rows={3} value={releaseNote} onChange={(event) => setReleaseNote(event.target.value)} /></label>
          </div>
          {mutation.isError && <DialogProblem error={mutation.error} />}
          {pending && <p className="dialog-progress" role="status">{preparing && !mutation.isPending ? '正在保存当前 revision…' : '正在发布 StaticSchema…'}</p>}
          <div className="dialog-actions">
            <Dialog.Close asChild><button className="button ghost-button" type="button" disabled={pending}>取消</button></Dialog.Close>
            <button
              className="button primary-button"
              type="button"
              disabled={knownBlockers || !VERSION_TAG.test(versionTag) || pending}
              onClick={() => void publish()}
            >
              {pending ? <LoaderCircle className="spin" aria-hidden="true" size={15} /> : <Archive aria-hidden="true" size={15} />}
              {intent === 'save-and-publish' && dirty ? '保存并原子发布' : '原子发布'}
            </button>
          </div>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}

function DialogProblem({ error }: { error: unknown }) {
  const message = error instanceof StudioRequestError
    ? error.problem.detail ?? error.problem.title
    : error instanceof Error ? error.message : '请求失败';
  return <p className="dialog-error" role="alert">{message}</p>;
}
