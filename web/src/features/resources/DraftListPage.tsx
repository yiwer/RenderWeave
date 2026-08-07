import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  AlertCircle,
  ArrowRight,
  Braces,
  Clock3,
  LoaderCircle,
  Plus,
  RefreshCw,
  RotateCcw,
  Search,
  X,
} from 'lucide-react';
import { useMemo, useState } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';

import { StudioRequestError, restoreDraftSnapshotRequest } from '../schema-studio/lossless-api';
import { listDraftsRequest } from './resource-api';
import { formatDateTime } from './resource-format';
import { ResourceFrame } from './ResourceFrame';

export function DraftListPage() {
  const location = useLocation();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [page, setPage] = useState(1);
  const [search, setSearch] = useState('');
  const deletedDraft = deletedDraftFromState(location.state);
  const query = useQuery({
    queryKey: ['schema-drafts', page],
    queryFn: () => listDraftsRequest(page, 50),
  });
  const items = useMemo(() => {
    const normalized = search.trim().toLocaleLowerCase('zh-CN');
    if (!normalized) return query.data?.items ?? [];
    return (query.data?.items ?? []).filter((item) =>
      `${item.schemaKey} ${item.displayName} ${item.creationSource}`
        .toLocaleLowerCase('zh-CN')
        .includes(normalized));
  }, [query.data?.items, search]);
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
      eyebrow="SCHEMA DRAFTS"
      title="可变数据定义"
      description="每次显式保存都追加一个完整、不可修改的 revision；列表只显示 active Draft。"
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
      <section className="resource-toolbar" aria-label="Draft 列表工具">
        <label className="resource-search">
          <Search aria-hidden="true" size={16} /><span className="sr-only">搜索 Draft</span>
          <input value={search} type="search" placeholder="搜索 schemaKey 或显示名称" onChange={(event) => setSearch(event.target.value)} />
        </label>
        <div className="resource-summary">
          <span>{query.data?.total ?? 0} active Drafts</span>
          <span>page {page}</span>
        </div>
      </section>

      {query.isPending && <ResourceLoading label="正在读取 Draft 列表" />}
      {query.isError && <ResourceError error={query.error} onRetry={() => void query.refetch()} />}
      {query.data && items.length === 0 && (
        <section className="resource-empty" role="status">
          <Braces aria-hidden="true" size={25} />
          <strong>{query.data.total === 0 ? '还没有 Schema Draft' : '当前页没有匹配项'}</strong>
          <span>{query.data.total === 0 ? '从一个空定义或第一个字段开始。' : '清除搜索词，或切换分页。'}</span>
          {query.data.total === 0 && <Link className="button primary-button" to="/schemas/new"><Plus aria-hidden="true" size={16} />创建第一个 Draft</Link>}
        </section>
      )}
      {items.length > 0 && (
        <div className="resource-table" role="table" aria-label="Active Schema Drafts">
          <div className="resource-table-head draft-grid" role="row">
            <span role="columnheader">Schema</span><span role="columnheader">来源</span><span role="columnheader">字段</span><span role="columnheader">Revision</span><span role="columnheader">最近保存</span><span aria-hidden="true" />
          </div>
          {items.map((item) => (
            <Link className="resource-table-row draft-grid" role="row" key={item.schemaKey} to={`/schemas/${item.schemaKey}`}>
              <span className="resource-identity" role="cell"><strong>{item.displayName}</strong><code>{item.schemaKey}</code></span>
              <span role="cell"><i className={`source-dot source-${item.creationSource.toLocaleLowerCase()}`} />{item.creationSource === 'AI' ? 'AI 候选落库' : '用户创建'}</span>
              <span role="cell">{item.fieldCount}</span>
              <span role="cell"><code>r{item.revision}</code></span>
              <span role="cell" title={formatDateTime(item.savedAt)}><Clock3 aria-hidden="true" size={13} />{formatDateTime(item.savedAt)}</span>
              <span role="cell"><ArrowRight aria-hidden="true" size={16} /></span>
            </Link>
          ))}
        </div>
      )}
      {query.data && query.data.total > query.data.size && (
        <div className="pagination">
          <button type="button" className="button ghost-button" disabled={page === 1} onClick={() => setPage((value) => value - 1)}>上一页</button>
          <span>{page} / {Math.ceil(query.data.total / query.data.size)}</span>
          <button type="button" className="button ghost-button" disabled={page * query.data.size >= query.data.total} onClick={() => setPage((value) => value + 1)}>下一页</button>
        </div>
      )}
    </ResourceFrame>
  );
}

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
