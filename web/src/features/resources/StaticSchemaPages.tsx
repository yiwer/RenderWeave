import * as Dialog from '@radix-ui/react-dialog';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { parse, stringify } from 'lossless-json';
import {
  ArrowRight,
  Check,
  Copy,
  Download,
  Layers3,
  LockKeyhole,
  Plus,
  Search,
} from 'lucide-react';
import { useMemo, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';

import {
  copyStaticToDraftRequest,
  getStaticArtifactRequest,
  getStaticSnapshotRequest,
} from '../schema-studio/lossless-api';
import { listStaticSchemasRequest } from './resource-api';
import { ResourceError, ResourceLoading } from './DraftListPage';
import { formatDateTime } from './resource-format';
import { ResourceFrame } from './ResourceFrame';

export function StaticSchemaListPage() {
  const [search, setSearch] = useState('');
  const query = useQuery({ queryKey: ['static-schemas'], queryFn: () => listStaticSchemasRequest(1, 100) });
  const items = useMemo(() => {
    const normalized = search.trim().toLocaleLowerCase('zh-CN');
    return (query.data?.items ?? []).filter((item) => !normalized
      || `${item.schemaKey} ${item.versionTag} ${item.displayName} ${item.origin}`
        .toLocaleLowerCase('zh-CN').includes(normalized));
  }, [query.data?.items, search]);
  return (
    <ResourceFrame
      eyebrow="STATIC SCHEMAS"
      title="不可变发布物"
      description="Template 只绑定精确 {schemaKey, versionTag}；定义与编译产物创建后永不改变。"
    >
      <section className="resource-toolbar">
        <label className="resource-search"><Search aria-hidden="true" size={16} /><span className="sr-only">搜索 StaticSchema</span><input type="search" value={search} placeholder="搜索 schemaKey、versionTag 或名称" onChange={(event) => setSearch(event.target.value)} /></label>
        <div className="resource-summary"><span>{query.data?.total ?? 0} immutable versions</span><span>system presets included</span></div>
      </section>
      {query.isPending && <ResourceLoading label="正在读取 StaticSchema" />}
      {query.isError && <ResourceError error={query.error} onRetry={() => void query.refetch()} />}
      {query.data && items.length === 0 && (
        <section className="resource-empty"><Layers3 aria-hidden="true" size={25} /><strong>没有匹配的 StaticSchema</strong><span>先保存一个只含 StaticSchemaRef 的 Draft，再从编辑器发布。</span></section>
      )}
      {items.length > 0 && (
        <div className="static-card-grid">
          {items.map((item) => (
            <Link key={`${item.schemaKey}@${item.versionTag}`} className={`static-card ${item.origin === 'SYSTEM' ? 'system-static-card' : ''}`} to={`/static-schemas/${item.schemaKey}/${item.versionTag}`}>
              <div className="static-card-top"><span className="immutable-chip"><LockKeyhole aria-hidden="true" size={12} />{item.origin === 'SYSTEM' ? 'SYSTEM' : 'IMMUTABLE'}</span><ArrowRight aria-hidden="true" size={16} /></div>
              <strong>{item.displayName}</strong>
              <code>{item.schemaKey}@{item.versionTag}</code>
              <div><span>{item.fieldCount} fields</span><span>depth {item.referenceDepth}</span><span>{formatDateTime(item.publishedAt)}</span></div>
            </Link>
          ))}
        </div>
      )}
    </ResourceFrame>
  );
}

export function StaticSchemaDetailPage() {
  const { schemaKey = '', versionTag = '' } = useParams<{ schemaKey: string; versionTag: string }>();
  const [activeArtifact, setActiveArtifact] = useState<'definition' | 'compiled'>('compiled');
  const [copied, setCopied] = useState(false);
  const query = useQuery({
    queryKey: ['static-schema', schemaKey, versionTag],
    queryFn: async () => {
      const [snapshot, definition, compiled] = await Promise.all([
        getStaticSnapshotRequest(schemaKey, versionTag),
        getStaticArtifactRequest(schemaKey, versionTag, 'definition'),
        getStaticArtifactRequest(schemaKey, versionTag, 'compiled-json-schema'),
      ]);
      return { snapshot, definition, compiled };
    },
  });
  const content = activeArtifact === 'compiled' ? query.data?.compiled ?? '' : query.data?.definition ?? '';
  const copy = async () => {
    await navigator.clipboard.writeText(content);
    setCopied(true);
    window.setTimeout(() => setCopied(false), 1_600);
  };
  const download = () => {
    if (!query.data) return;
    downloadText(
      `${schemaKey}-${versionTag}-${activeArtifact === 'compiled' ? 'json-schema' : 'definition'}.json`,
      content,
      activeArtifact === 'compiled' ? 'application/schema+json' : 'application/json',
    );
  };
  return (
    <ResourceFrame
      eyebrow="STATIC SCHEMA · EXACT VERSION"
      title={query.data?.snapshot.definition.displayName ?? `${schemaKey}@${versionTag}`}
      description="只读、不可变、不可删除；编译 JSON Schema 是发布时保存的精确产物，不会自动重算。"
      actions={<CopyStaticDialog sourceSchemaKey={schemaKey} versionTag={versionTag} defaultName={query.data?.snapshot.definition.displayName ?? ''} />}
    >
      {query.isPending && <ResourceLoading label="正在读取不可变产物" />}
      {query.isError && <ResourceError error={query.error} onRetry={() => void query.refetch()} />}
      {query.data && (
        <>
          <section className="immutable-banner"><LockKeyhole aria-hidden="true" size={19} /><div><strong>不可变边界已建立</strong><span>{schemaKey}@{versionTag} · {query.data.snapshot.origin === 'SYSTEM' ? '系统预置' : `源 Draft revision ${query.data.snapshot.sourceDraftRevision}`}</span></div><code>{query.data.snapshot.compilerVersion}</code></section>
          <dl className="static-metadata">
            <div><dt>schemaKey</dt><dd><code>{schemaKey}</code></dd></div>
            <div><dt>versionTag</dt><dd><code>{versionTag}</code></dd></div>
            <div><dt>字段</dt><dd>{query.data.snapshot.definition.fields.length}</dd></div>
            <div><dt>引用深度</dt><dd>{query.data.snapshot.referenceDepth}</dd></div>
            <div><dt>发布时间</dt><dd>{formatDateTime(query.data.snapshot.publishedAt)}</dd></div>
          </dl>
          {query.data.snapshot.releaseNote && <section className="release-note"><span>RELEASE NOTE</span><p>{query.data.snapshot.releaseNote}</p></section>}
          <section className="artifact-panel">
            <header>
              <div className="artifact-tabs" aria-label="产物类型">
                <button type="button" className={activeArtifact === 'compiled' ? 'active' : ''} onClick={() => setActiveArtifact('compiled')}>Compiled JSON Schema</button>
                <button type="button" className={activeArtifact === 'definition' ? 'active' : ''} onClick={() => setActiveArtifact('definition')}>Definition DSL</button>
              </div>
              <div><button type="button" onClick={() => void copy()}>{copied ? <Check aria-hidden="true" size={15} /> : <Copy aria-hidden="true" size={15} />}{copied ? '已复制' : '复制'}</button><button type="button" onClick={download}><Download aria-hidden="true" size={15} />下载</button></div>
            </header>
            <pre>{prettyJson(content)}</pre>
          </section>
        </>
      )}
    </ResourceFrame>
  );
}

function CopyStaticDialog({ sourceSchemaKey, versionTag, defaultName }: { sourceSchemaKey: string; versionTag: string; defaultName: string }) {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [open, setOpen] = useState(false);
  const [schemaKey, setSchemaKey] = useState('');
  const [displayName, setDisplayName] = useState(defaultName ? `${defaultName} 副本` : 'StaticSchema 副本');
  const mutation = useMutation({
    mutationFn: () => copyStaticToDraftRequest(sourceSchemaKey, versionTag, schemaKey, displayName),
    onSuccess: (draft) => {
      queryClient.setQueryData(['schema-draft', draft.schemaKey], draft);
      void queryClient.invalidateQueries({ queryKey: ['schema-drafts'] });
      navigate(`/schemas/${draft.schemaKey}`);
    },
  });
  return (
    <Dialog.Root open={open} onOpenChange={setOpen}>
      <Dialog.Trigger asChild><button type="button" className="button primary-button"><Plus aria-hidden="true" size={16} />复制为 Draft</button></Dialog.Trigger>
      <Dialog.Portal><Dialog.Overlay className="dialog-overlay" /><Dialog.Content className="dialog-content">
        <Dialog.Title>复制为新的 Schema Draft</Dialog.Title>
        <Dialog.Description>只复制字段定义、顺序、约束和引用；不会复制历史、发布时间或 release note。</Dialog.Description>
        <div className="dialog-form">
          <label>新 schemaKey<input className="mono-input" value={schemaKey} onChange={(event) => setSchemaKey(event.target.value)} /></label>
          <label>显示名称<input value={displayName} onChange={(event) => setDisplayName(event.target.value)} /></label>
        </div>
        {mutation.isError && <p className="dialog-error" role="alert">{mutation.error instanceof Error ? mutation.error.message : '复制失败'}</p>}
        <div className="dialog-actions"><Dialog.Close asChild><button type="button" className="button ghost-button">取消</button></Dialog.Close><button type="button" className="button primary-button" disabled={!schemaKey || !displayName || mutation.isPending} onClick={() => mutation.mutate()}>创建 Draft</button></div>
      </Dialog.Content></Dialog.Portal>
    </Dialog.Root>
  );
}

function downloadText(filename: string, content: string, type: string) {
  const url = URL.createObjectURL(new Blob([content], { type: `${type};charset=utf-8` }));
  const anchor = document.createElement('a');
  anchor.href = url; anchor.download = filename; anchor.click(); URL.revokeObjectURL(url);
}

function prettyJson(content: string): string {
  try { return stringify(parse(content), null, 2) ?? content; } catch { return content; }
}
