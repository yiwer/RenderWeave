import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  AlertCircle,
  Braces,
  Clock3,
  History,
  LoaderCircle,
  Plus,
  RefreshCw,
  Rocket,
  RotateCcw,
  Trash2,
  X,
} from 'lucide-react';
import { useState } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';

import { DeleteDraftDialog, DraftHistoryDialog } from '../schema-studio/DraftLifecyclePanel';
import { PublishStaticSchemaDialog } from '../schema-studio/PublishStaticSchemaDialog';
import { StudioRequestError, restoreDraftSnapshotRequest } from '../schema-studio/lossless-api';
import {
  ResourcePagination,
  ResourceSearchInput,
  ResourceSortSelect,
} from './ResourceListControls';
import { useDebouncedValue } from './resource-list-hooks';
import { listDraftsRequest, type DraftListSort } from './resource-api';
import { formatDateTime } from './resource-format';
import { ResourceFrame } from './ResourceFrame';

export function DraftListPage() {
  const location = useLocation();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(9);
  const [search, setSearch] = useState('');
  const [sort, setSort] = useState<DraftListSort>('UPDATED_DESC');
  const debouncedSearch = useDebouncedValue(search);
  const deletedDraft = deletedDraftFromState(location.state);
  const query = useQuery({
    queryKey: ['schema-drafts', 'list', page, pageSize, debouncedSearch, sort],
    queryFn: () => listDraftsRequest(page, pageSize, debouncedSearch, sort),
    placeholderData: keepPreviousData,
  });
  const items = query.data?.items ?? [];
  const dismissDeleted = () => navigate('/schemas', { replace: true, state: null });
  const restoreDeleted = useMutation({
    mutationFn: () => restoreDraftSnapshotRequest(
      deletedDraft!.schemaKey,
      deletedDraft!.revision,
      deletedDraft!.revision,
    ),
    onSuccess: (draft) => {
      queryClient.setQueryData(['schema-draft', draft.schemaKey], draft);
      void queryClient.invalidateQueries({ queryKey: ['schema-drafts'] });
      navigate(`/schemas/${draft.schemaKey}`, { replace: true });
    },
  });

  return (
    <ResourceFrame
      title="数据结构设计"
      description="设计可变 Schema 定义；每次显式保存都会追加一个不可修改的 revision。"
      actions={<Link className="button primary-button" to="/schemas/new"><Plus aria-hidden="true" size={16} />新建 Draft</Link>}
    >
      {deletedDraft && (
        <section className="deleted-draft-banner" role="status">
          <div><strong>{deletedDraft.displayName} 已软删除</strong><span><code>{deletedDraft.schemaKey}</code> 的历史和 tombstone 已保留，可立即恢复为新 revision。</span></div>
          <button type="button" className="button ghost-button" disabled={restoreDeleted.isPending} onClick={() => restoreDeleted.mutate()}><RotateCcw aria-hidden="true" size={15} />{restoreDeleted.isPending ? '恢复中…' : '撤销删除'}</button>
          <button type="button" className="icon-button" aria-label="关闭恢复提示" onClick={dismissDeleted}><X aria-hidden="true" size={16} /></button>
          {restoreDeleted.isError && <span className="deleted-restore-error" role="alert">{restoreDeleted.error instanceof Error ? restoreDeleted.error.message : '恢复失败'}</span>}
        </section>
      )}
      <section className="resource-toolbar resource-list-toolbar" aria-label="数据结构设计工具">
        <ResourceSearchInput id="draft-resource-search" value={search} label="搜索数据结构设计" placeholder="搜索 schemaKey 或显示名称" onChange={(value) => { setSearch(value); setPage(1); }} />
        <div className="resource-toolbar-controls">
          <ResourceSortSelect
            value={sort}
            options={draftSortOptions}
            onChange={(value) => { setSort(value); setPage(1); }}
          />
        </div>
        <div className="resource-summary">
          {query.isFetching && !query.isPending && <LoaderCircle className="spin" aria-hidden="true" size={13} />}
          <span>{query.data?.total ?? 0} 个设计</span>
          <span>第 {page} 页</span>
        </div>
      </section>

      {query.isPending && <ResourceLoading label="正在读取数据结构设计" />}
      {query.isError && <ResourceError error={query.error} onRetry={() => void query.refetch()} />}
      {query.data && items.length === 0 && (
        <section className="resource-empty" role="status">
          <Braces aria-hidden="true" size={25} />
          <strong>{debouncedSearch ? '没有匹配的数据结构设计' : '还没有数据结构设计'}</strong>
          <span>{debouncedSearch ? '尝试缩短关键词，或搜索 schemaKey。' : '从一个空定义或第一个字段开始。'}</span>
          {!debouncedSearch && <Link className="button primary-button" to="/schemas/new"><Plus aria-hidden="true" size={16} />创建第一个 Draft</Link>}
        </section>
      )}
      {items.length > 0 && (
        <div className="draft-card-grid" aria-label="数据结构设计卡片列表">
          {items.map((item) => (
            <article className="draft-schema-card" key={item.schemaKey}>
              <Link className="draft-card-main" to={`/schemas/${item.schemaKey}`} aria-label={`打开 ${item.displayName}`}>
                <header>
                  <div><strong>{item.displayName}</strong><code>{item.schemaKey}</code></div>
                  <span><i className={`source-dot source-${item.creationSource.toLocaleLowerCase()}`} />{item.creationSource === 'AI' ? 'AI 创建' : '用户创建'}</span>
                </header>
                <dl>
                  <div><dt>字段</dt><dd>{item.fieldCount}</dd></div>
                  <div><dt>Revision</dt><dd><code>r{item.revision}</code></dd></div>
                  <div><dt>最近保存</dt><dd title={formatDateTime(item.savedAt)}><Clock3 aria-hidden="true" size={13} />{formatDateTime(item.savedAt)}</dd></div>
                </dl>
              </Link>
              <footer className="draft-card-actions" aria-label={`${item.displayName} 操作`}>
                <DraftHistoryDialog
                  schemaKey={item.schemaKey}
                  currentRevision={item.revision}
                  trigger={<button type="button" className="card-action-button" aria-label={`查看 ${item.displayName} 的历史`}><History aria-hidden="true" size={15} />历史</button>}
                />
                <PublishStaticSchemaDialog
                  schemaKey={item.schemaKey}
                  revision={item.revision}
                  intent="publish"
                  trigger={<button type="button" className="card-action-button card-publish-button" aria-label={`发布 ${item.displayName} 为 StaticSchema`}><Rocket aria-hidden="true" size={15} />发布</button>}
                />
                <DeleteDraftDialog
                  schemaKey={item.schemaKey}
                  revision={item.revision}
                  displayName={item.displayName}
                  trigger={<button type="button" className="card-action-button card-delete-button" aria-label={`删除 ${item.displayName}`}><Trash2 aria-hidden="true" size={15} />删除</button>}
                />
              </footer>
            </article>
          ))}
        </div>
      )}
      {query.data && (
        <ResourcePagination
          label="数据结构设计"
          page={page}
          size={pageSize}
          total={query.data.total}
          onPageChange={setPage}
          onSizeChange={(size) => { setPageSize(size); setPage(1); }}
        />
      )}
    </ResourceFrame>
  );
}

const draftSortOptions: Array<{ value: DraftListSort; label: string }> = [
  { value: 'UPDATED_DESC', label: '最近更新' },
  { value: 'UPDATED_ASC', label: '最早更新' },
  { value: 'NAME_ASC', label: '名称 A–Z' },
  { value: 'NAME_DESC', label: '名称 Z–A' },
];

export function ResourceLoading({ label }: { label: string }) {
  return <div className="resource-state" role="status"><LoaderCircle className="spin" aria-hidden="true" size={21} /><strong>{label}</strong></div>;
}

export function ResourceError({ error, onRetry }: { error: unknown; onRetry: () => void }) {
  const message = error instanceof StudioRequestError
    ? error.problem.detail ?? error.problem.title
    : error instanceof Error ? error.message : '请求失败。';
  return (
    <div className="resource-state resource-state-error" role="alert">
      <AlertCircle aria-hidden="true" size={21} /><strong>资源读取失败</strong><span>{message}</span>
      <button type="button" className="button ghost-button" onClick={onRetry}><RefreshCw aria-hidden="true" size={15} />重试</button>
    </div>
  );
}

interface DeletedDraftState {
  schemaKey: string;
  revision: number;
  displayName: string;
}

function deletedDraftFromState(value: unknown): DeletedDraftState | undefined {
  if (typeof value !== 'object' || value === null || !('deletedDraft' in value)) return undefined;
  const candidate = (value as { deletedDraft?: unknown }).deletedDraft;
  if (typeof candidate !== 'object' || candidate === null) return undefined;
  const draft = candidate as Partial<DeletedDraftState>;
  return typeof draft.schemaKey === 'string'
    && typeof draft.revision === 'number'
    && Number.isSafeInteger(draft.revision)
    && typeof draft.displayName === 'string'
    ? { schemaKey: draft.schemaKey, revision: draft.revision, displayName: draft.displayName }
    : undefined;
}
