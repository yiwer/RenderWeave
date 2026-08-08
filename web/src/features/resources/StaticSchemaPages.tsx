import * as Dialog from '@radix-ui/react-dialog';
import * as Tabs from '@radix-ui/react-tabs';
import { useMutation, useQuery, useQueryClient, type UseQueryResult } from '@tanstack/react-query';
import { parse, stringify } from 'lossless-json';
import {
  ArrowRight,
  Braces,
  Check,
  Copy,
  Download,
  FileCode2,
  Layers3,
  ListTree,
  LockKeyhole,
  LoaderCircle,
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
import {
  editorTypeLabels,
  editorValueFromPersisted,
  summarizeEditorValue,
  type PersistedDefinition,
} from '../schema-studio/editor-types';
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
      title="不可变发布物"
      description="Template 只绑定精确 {schemaKey, versionTag}；定义与编译产物创建后永不改变。"
    >
      <section className="resource-toolbar">
        <label className="resource-search"><Search aria-hidden="true" size={16} /><span className="sr-only">搜索 StaticSchema</span><input type="search" value={search} placeholder="搜索 schemaKey、versionTag 或名称" onChange={(event) => setSearch(event.target.value)} /></label>
        <div className="resource-summary"><span>{query.data?.total ?? 0} 个不可变版本</span><span>包含系统预置</span></div>
      </section>
      {query.isPending && <ResourceLoading label="正在读取 StaticSchema" />}
      {query.isError && <ResourceError error={query.error} onRetry={() => void query.refetch()} />}
      {query.data && items.length === 0 && (
        <section className="resource-empty"><Layers3 aria-hidden="true" size={25} /><strong>没有匹配的 StaticSchema</strong><span>先保存一个只含 StaticSchemaRef 的 Draft，再从 DraftSchema 卡片或详情页发布。</span></section>
      )}
      {items.length > 0 && (
        <div className="static-card-grid">
          {items.map((item) => (
            <Link key={`${item.schemaKey}@${item.versionTag}`} className={`static-card ${item.origin === 'SYSTEM' ? 'system-static-card' : ''}`} to={`/static-schemas/${item.schemaKey}/${item.versionTag}`}>
              <div className="static-card-top"><span className="immutable-chip"><LockKeyhole aria-hidden="true" size={12} />{item.origin === 'SYSTEM' ? '系统预置' : '不可变'}</span><ArrowRight aria-hidden="true" size={16} /></div>
              <strong>{item.displayName}</strong>
              <code>{item.schemaKey}@{item.versionTag}</code>
              <div><span>{item.fieldCount} 个字段</span><span>深度 {item.referenceDepth}</span><span>{formatDateTime(item.publishedAt)}</span></div>
            </Link>
          ))}
        </div>
      )}
    </ResourceFrame>
  );
}

export function StaticSchemaDetailPage() {
  const { schemaKey = '', versionTag = '' } = useParams<{ schemaKey: string; versionTag: string }>();
  return <StaticSchemaDetailContent key={`${schemaKey}@${versionTag}`} schemaKey={schemaKey} versionTag={versionTag} />;
}

function StaticSchemaDetailContent({ schemaKey, versionTag }: { schemaKey: string; versionTag: string }) {
  const [activeView, setActiveView] = useState<StaticDetailView>('form');
  const [copied, setCopied] = useState(false);
  const snapshot = useQuery({
    queryKey: ['static-schema', schemaKey, versionTag],
    queryFn: () => getStaticSnapshotRequest(schemaKey, versionTag),
  });
  const artifactKind = activeView === 'compiled'
    ? 'compiled-json-schema'
    : activeView === 'definition' ? 'definition' : null;
  const artifact = useQuery({
    queryKey: ['static-schema-artifact', schemaKey, versionTag, artifactKind],
    queryFn: () => getStaticArtifactRequest(schemaKey, versionTag, artifactKind!),
    enabled: artifactKind !== null,
  });
  const content = artifact.data ?? '';

  const copy = async () => {
    if (!content) return;
    await navigator.clipboard.writeText(content);
    setCopied(true);
    window.setTimeout(() => setCopied(false), 1_600);
  };
  const download = () => {
    if (!content || artifactKind === null) return;
    downloadText(
      `${schemaKey}-${versionTag}-${activeView === 'compiled' ? 'json-schema' : 'definition'}.json`,
      content,
      activeView === 'compiled' ? 'application/schema+json' : 'application/json',
    );
  };
  return (
    <ResourceFrame
      title={snapshot.data?.definition.displayName ?? `${schemaKey}@${versionTag}`}
      description="只读、不可变、不可删除；编译 JSON Schema 是发布时保存的精确产物，不会自动重算。"
      detail
      breadcrumbs={[
        { label: 'StaticSchema', to: '/static-schemas' },
        { label: snapshot.data?.definition.displayName ?? `${schemaKey}@${versionTag}` },
      ]}
      actions={snapshot.data ? <CopyStaticDialog sourceSchemaKey={schemaKey} versionTag={versionTag} defaultName={snapshot.data.definition.displayName} /> : undefined}
    >
      {snapshot.isPending && <ResourceLoading label="正在读取不可变产物" />}
      {snapshot.isError && <ResourceError error={snapshot.error} onRetry={() => void snapshot.refetch()} />}
      {snapshot.data && (
        <>
          <section className="immutable-banner"><LockKeyhole aria-hidden="true" size={19} /><div><strong>不可变边界已建立</strong><span>{schemaKey}@{versionTag} · {snapshot.data.origin === 'SYSTEM' ? '系统预置' : `源 Draft revision ${snapshot.data.sourceDraftRevision}`}</span></div><code>{snapshot.data.compilerVersion}</code></section>
          <dl className="static-metadata">
            <div><dt>schemaKey</dt><dd><code>{schemaKey}</code></dd></div>
            <div><dt>versionTag</dt><dd><code>{versionTag}</code></dd></div>
            <div><dt>字段</dt><dd>{snapshot.data.definition.fields.length}</dd></div>
            <div><dt>引用深度</dt><dd>{snapshot.data.referenceDepth}</dd></div>
            <div><dt>发布时间</dt><dd>{formatDateTime(snapshot.data.publishedAt)}</dd></div>
          </dl>
          {snapshot.data.releaseNote && <section className="release-note"><span>发布说明</span><p>{snapshot.data.releaseNote}</p></section>}

          <Tabs.Root className="static-detail-views" value={activeView} onValueChange={(value) => { setActiveView(value as StaticDetailView); setCopied(false); }}>
            <div className="static-view-toolbar">
              <Tabs.List className="static-view-tabs" aria-label="StaticSchema 查看方式">
                <Tabs.Trigger value="form"><ListTree aria-hidden="true" size={15} />字段表单</Tabs.Trigger>
                <Tabs.Trigger value="compiled"><Braces aria-hidden="true" size={15} />Compiled JSON Schema</Tabs.Trigger>
                <Tabs.Trigger value="definition"><FileCode2 aria-hidden="true" size={15} />Definition DSL</Tabs.Trigger>
              </Tabs.List>
              <div className="static-view-tools">
                {activeView === 'form' ? (
                  <><span>{snapshot.data.definition.fields.length} 个字段</span><span>additionalProperties=true</span></>
                ) : (
                  <>
                    <button type="button" disabled={!content || artifact.isPending} onClick={() => void copy()}>{copied ? <Check aria-hidden="true" size={15} /> : <Copy aria-hidden="true" size={15} />}{copied ? '已复制' : '复制'}</button>
                    <button type="button" disabled={!content || artifact.isPending} onClick={download}><Download aria-hidden="true" size={15} />下载</button>
                  </>
                )}
              </div>
            </div>
            <Tabs.Content className="static-view-panel" value="form">
              <StaticDefinitionForm definition={snapshot.data.definition} />
            </Tabs.Content>
            <Tabs.Content className="static-view-panel" value="compiled">
              <StaticArtifactView artifact={artifact} content={content} label="Compiled JSON Schema" />
            </Tabs.Content>
            <Tabs.Content className="static-view-panel" value="definition">
              <StaticArtifactView artifact={artifact} content={content} label="Definition DSL" />
            </Tabs.Content>
          </Tabs.Root>
        </>
      )}
    </ResourceFrame>
  );
}

type StaticDetailView = 'form' | 'compiled' | 'definition';

function StaticDefinitionForm({ definition }: { definition: PersistedDefinition }) {
  if (definition.fields.length === 0) {
    return <section className="resource-empty static-definition-empty" role="status"><ListTree aria-hidden="true" size={23} /><strong>这是一个空 StaticSchema</strong><span>定义中没有字段；未知字段仍由 additionalProperties=true 接受。</span></section>;
  }
  return (
    <section className="static-definition-form" aria-label="StaticSchema 字段表单">
      {definition.fields.map((field, index) => {
        const value = editorValueFromPersisted(field.value);
        const label = field.displayName?.trim() || field.fieldKey || `字段 ${index + 1}`;
        return (
          <article className="static-definition-field" key={`${field.fieldKey}-${index}`}>
            <div className="static-field-main">
              <span className={`type-dot type-${value.type}`} aria-hidden="true" />
              <span className="field-identity"><strong>{label}</strong><code>{field.fieldKey}</code></span>
              <span className="type-chip">{editorTypeLabels[value.type]}</span>
              <span className="field-detail">{summarizeEditorValue(value)}</span>
              <span className="static-readonly-chip"><LockKeyhole aria-hidden="true" size={12} />只读</span>
            </div>
            <span className={`required-toggle ${field.required ? 'active' : ''}`}>{field.required ? '必填' : '可选'}</span>
            {field.description && <p className="static-field-description">{field.description}</p>}
          </article>
        );
      })}
    </section>
  );
}

function StaticArtifactView({
  artifact,
  content,
  label,
}: {
  artifact: UseQueryResult<string, Error>;
  content: string;
  label: string;
}) {
  if (artifact.isPending) return <div className="static-view-state" role="status"><LoaderCircle className="spin" aria-hidden="true" size={19} /><span>正在读取 {label}…</span></div>;
  if (artifact.isError) return <ResourceError error={artifact.error} onRetry={() => void artifact.refetch()} />;
  return <section className="artifact-panel static-artifact-panel" aria-label={label}><pre>{prettyJson(content)}</pre></section>;
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
