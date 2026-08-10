import * as Dialog from '@radix-ui/react-dialog';
import * as Tabs from '@radix-ui/react-tabs';
import { keepPreviousData, useMutation, useQuery, useQueryClient, type UseQueryResult } from '@tanstack/react-query';
import { parse, stringify } from 'lossless-json';
import {
  ArrowRight,
  Braces,
  Check,
  Copy,
  Download,
  FileCode2,
  Layers3,
  List,
  ListTree,
  LockKeyhole,
  LoaderCircle,
  PanelRightOpen,
  Plus,
  X,
} from 'lucide-react';
import { useState } from 'react';
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
  type PersistedField,
  type EditorValue,
} from '../schema-studio/editor-types';
import {
  ResourceOriginSwitch,
  ResourcePagination,
  ResourceSearchInput,
  ResourceSortSelect,
} from './ResourceListControls';
import { useDebouncedValue } from './resource-list-hooks';
import { listStaticSchemasRequest, type StaticSchemaListSort } from './resource-api';
import { ResourceError, ResourceLoading } from './DraftListPage';
import { formatDateTime } from './resource-format';
import { ResourceFrame } from './ResourceFrame';
import { ReadonlyDefinitionTree } from '../schema-studio/ReadonlyDefinitionViews';

export function StaticSchemaListPage() {
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(9);
  const [search, setSearch] = useState('');
  const [sort, setSort] = useState<StaticSchemaListSort>('PUBLISHED_DESC');
  const [systemOnly, setSystemOnly] = useState(false);
  const debouncedSearch = useDebouncedValue(search);
  const origin = systemOnly ? 'SYSTEM' : 'DRAFT';
  const query = useQuery({
    queryKey: ['static-schemas', 'list', page, pageSize, debouncedSearch, sort, origin],
    queryFn: () => listStaticSchemasRequest(page, pageSize, debouncedSearch, sort, origin),
    placeholderData: keepPreviousData,
  });
  const items = query.data?.items ?? [];
  return (
    <ResourceFrame
      title="数据结构资产"
      description="Template 只绑定精确 {schemaKey, versionTag}；定义与编译产物创建后永不改变。"
    >
      <section className="resource-toolbar resource-list-toolbar" aria-label="数据结构资产工具">
        <ResourceSearchInput id="static-resource-search" value={search} label="搜索数据结构资产" placeholder="搜索 schemaKey、版本或显示名称" onChange={(value) => { setSearch(value); setPage(1); }} />
        <div className="resource-toolbar-controls">
          <ResourceSortSelect
            value={sort}
            options={staticSortOptions}
            onChange={(value) => { setSort(value); setPage(1); }}
          />
          <ResourceOriginSwitch systemOnly={systemOnly} onChange={(value) => { setSystemOnly(value); setPage(1); }} />
        </div>
        <div className="resource-summary">
          {query.isFetching && !query.isPending && <LoaderCircle className="spin" aria-hidden="true" size={13} />}
          <span>{query.data?.total ?? 0} 个{systemOnly ? '系统预设' : '用户资产'}</span><span>第 {page} 页</span>
        </div>
      </section>
      {query.isPending && <ResourceLoading label="正在读取数据结构资产" />}
      {query.isError && <ResourceError error={query.error} onRetry={() => void query.refetch()} />}
      {query.data && items.length === 0 && (
        <section className="resource-empty" role="status"><Layers3 aria-hidden="true" size={25} /><strong>{debouncedSearch ? '没有匹配的数据结构资产' : systemOnly ? '没有系统预设' : '还没有数据结构资产'}</strong><span>{debouncedSearch ? '尝试缩短关键词，或搜索 schemaKey 与版本号。' : systemOnly ? '当前环境未提供系统预设。' : '先保存一份有效的数据结构设计，再从卡片或详情页发布。'}</span></section>
      )}
      {items.length > 0 && (
        <div className="static-card-grid" aria-label="数据结构资产卡片列表">
          {items.map((item) => (
            <Link key={`${item.schemaKey}@${item.versionTag}`} className={`static-card ${item.origin === 'SYSTEM' ? 'system-static-card' : ''}`} to={`/static-schemas/${item.schemaKey}/${item.versionTag}`}>
              <div className="static-card-top"><span className="immutable-chip"><LockKeyhole aria-hidden="true" size={12} />{item.origin === 'SYSTEM' ? '系统预置' : '不可变'}</span><ArrowRight aria-hidden="true" size={16} /></div>
              <div className="static-card-title"><strong>{item.displayName}</strong><span className="static-version-badge" aria-label={`版本 ${item.versionTag}`}>{item.versionTag}</span></div>
              <code>{item.schemaKey}</code>
              <div><span>{item.fieldCount} 个字段</span><span>深度 {item.referenceDepth}</span><span>{formatDateTime(item.publishedAt)}</span></div>
            </Link>
          ))}
        </div>
      )}
      {query.data && (
        <ResourcePagination
          label="数据结构资产"
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

const staticSortOptions: Array<{ value: StaticSchemaListSort; label: string }> = [
  { value: 'PUBLISHED_DESC', label: '最新发布' },
  { value: 'PUBLISHED_ASC', label: '最早发布' },
  { value: 'NAME_ASC', label: '名称 A–Z' },
  { value: 'NAME_DESC', label: '名称 Z–A' },
];

export function StaticSchemaDetailPage() {
  const { schemaKey = '', versionTag = '' } = useParams<{ schemaKey: string; versionTag: string }>();
  return <StaticSchemaDetailContent key={`${schemaKey}@${versionTag}`} schemaKey={schemaKey} versionTag={versionTag} />;
}

function StaticSchemaDetailContent({ schemaKey, versionTag }: { schemaKey: string; versionTag: string }) {
  const [activeView, setActiveView] = useState<StaticDetailView>('tree');
  const [copied, setCopied] = useState(false);
  const [selectedFieldIndex, setSelectedFieldIndex] = useState(0);
  const [inspectorOpen, setInspectorOpen] = useState(false);
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
        { label: '数据结构资产', to: '/static-schemas' },
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

          <Tabs.Root className="static-detail-views" value={activeView} onValueChange={(value) => { setActiveView(value as StaticDetailView); setCopied(false); setInspectorOpen(false); }}>
            <div className="static-view-toolbar">
              <Tabs.List className="static-view-tabs" aria-label="StaticSchema 查看方式">
                <Tabs.Trigger value="tree"><ListTree aria-hidden="true" size={15} />字段树</Tabs.Trigger>
                <Tabs.Trigger value="form"><List aria-hidden="true" size={15} />字段表单</Tabs.Trigger>
                <Tabs.Trigger value="compiled"><Braces aria-hidden="true" size={15} />Compiled JSON Schema</Tabs.Trigger>
                <Tabs.Trigger value="definition"><FileCode2 aria-hidden="true" size={15} />Definition DSL</Tabs.Trigger>
              </Tabs.List>
              <div className="static-view-tools">
                {activeView === 'tree' || activeView === 'form' ? (
                  <>
                    <span>{snapshot.data.definition.fields.length} 个字段</span><span>additionalProperties=true</span>
                    {snapshot.data.definition.fields.length > 0 && (
                      <button type="button" className="static-inspector-trigger" onClick={() => setInspectorOpen(true)}><PanelRightOpen aria-hidden="true" size={15} />字段信息</button>
                    )}
                  </>
                ) : (
                  <>
                    <button type="button" disabled={!content || artifact.isPending} onClick={() => void copy()}>{copied ? <Check aria-hidden="true" size={15} /> : <Copy aria-hidden="true" size={15} />}{copied ? '已复制' : '复制'}</button>
                    <button type="button" disabled={!content || artifact.isPending} onClick={download}><Download aria-hidden="true" size={15} />下载</button>
                  </>
                )}
              </div>
            </div>
            <Tabs.Content className="static-view-panel" value="tree">
              <StaticDefinitionTree
                schemaKey={schemaKey}
                definition={snapshot.data.definition}
                selectedIndex={selectedFieldIndex}
                inspectorOpen={inspectorOpen}
                onSelect={(index) => { setSelectedFieldIndex(index); setInspectorOpen(true); }}
                onCloseInspector={() => setInspectorOpen(false)}
              />
            </Tabs.Content>
            <Tabs.Content className="static-view-panel" value="form">
              <StaticDefinitionForm
                definition={snapshot.data.definition}
                selectedIndex={selectedFieldIndex}
                inspectorOpen={inspectorOpen}
                onSelect={(index) => { setSelectedFieldIndex(index); setInspectorOpen(true); }}
                onCloseInspector={() => setInspectorOpen(false)}
              />
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

type StaticDetailView = 'tree' | 'form' | 'compiled' | 'definition';

function StaticDefinitionTree({
  schemaKey,
  definition,
  selectedIndex,
  inspectorOpen,
  onSelect,
  onCloseInspector,
}: {
  schemaKey: string;
  definition: PersistedDefinition;
  selectedIndex: number;
  inspectorOpen: boolean;
  onSelect: (index: number) => void;
  onCloseInspector: () => void;
}) {
  if (definition.fields.length === 0) {
    return <ReadonlyDefinitionTree schemaKey={schemaKey} definition={definition} />;
  }
  const resolvedIndex = definition.fields[selectedIndex] ? selectedIndex : 0;
  return (
    <div className="static-form-workbench static-tree-workbench">
      <ReadonlyDefinitionTree
        schemaKey={schemaKey}
        definition={definition}
        selectedIndex={resolvedIndex}
        onSelect={onSelect}
      />
      <StaticFieldInspector
        field={definition.fields[resolvedIndex]!}
        index={resolvedIndex}
        open={inspectorOpen}
        onClose={onCloseInspector}
      />
    </div>
  );
}

function StaticDefinitionForm({
  definition,
  selectedIndex,
  inspectorOpen,
  onSelect,
  onCloseInspector,
}: {
  definition: PersistedDefinition;
  selectedIndex: number;
  inspectorOpen: boolean;
  onSelect: (index: number) => void;
  onCloseInspector: () => void;
}) {
  if (definition.fields.length === 0) {
    return <section className="resource-empty static-definition-empty" role="status"><ListTree aria-hidden="true" size={23} /><strong>这是一个空 StaticSchema</strong><span>定义中没有字段；未知字段仍由 additionalProperties=true 接受。</span></section>;
  }
  const selectedField = definition.fields[selectedIndex] ?? definition.fields[0]!;
  const resolvedIndex = definition.fields[selectedIndex] ? selectedIndex : 0;
  return (
    <div className="static-form-workbench">
      <section className="static-definition-form" aria-label="StaticSchema 字段表单">
        {definition.fields.map((field, index) => {
          const value = editorValueFromPersisted(field.value);
          const label = field.displayName?.trim() || field.fieldKey || `字段 ${index + 1}`;
          return (
            <button
              type="button"
              className={`static-definition-field ${index === resolvedIndex ? 'is-selected' : ''}`}
              key={`${field.fieldKey}-${index}`}
              aria-pressed={index === resolvedIndex}
              aria-label={`查看字段 ${label}，${editorTypeLabels[value.type]}，${field.required ? '必填' : '可选'}`}
              onClick={() => onSelect(index)}
            >
              <span className="static-field-main">
                <span className={`type-dot type-${value.type}`} aria-hidden="true" />
                <span className="field-identity"><strong>{label}</strong><code>{field.fieldKey}</code></span>
                <span className="type-chip">{editorTypeLabels[value.type]}</span>
                <span className="field-detail">{summarizeEditorValue(value)}</span>
                <span className={`required-toggle ${field.required ? 'active' : ''}`}>{field.required ? '必填' : '可选'}</span>
              </span>
              {field.description && <span className="static-field-description">{field.description}</span>}
            </button>
          );
        })}
      </section>
      <StaticFieldInspector field={selectedField} index={resolvedIndex} open={inspectorOpen} onClose={onCloseInspector} />
    </div>
  );
}

function StaticFieldInspector({
  field,
  index,
  open,
  onClose,
}: {
  field: PersistedField;
  index: number;
  open: boolean;
  onClose: () => void;
}) {
  const value = editorValueFromPersisted(field.value);
  const label = field.displayName?.trim() || field.fieldKey || `字段 ${index + 1}`;
  const details = staticValueDetails(value);
  return (
    <aside className={`static-field-inspector ${open ? 'is-open' : ''}`} aria-label="StaticSchema 字段信息" tabIndex={0}>
      <div className="static-inspector-heading">
        <div><span>字段信息</span><h2>{label}</h2></div>
        <button type="button" className="icon-button static-inspector-close" onClick={onClose} aria-label="关闭字段信息"><X aria-hidden="true" size={17} /></button>
      </div>
      <div className="static-inspector-context">
        <code>/{field.fieldKey || '未命名'}</code>
        <span><LockKeyhole aria-hidden="true" size={12} />只读</span>
      </div>

      <section className="static-inspector-card" aria-labelledby={`static-basics-${index}`}>
        <h3 id={`static-basics-${index}`}>基础信息</h3>
        <div className="static-readonly-form">
          <div className="static-readonly-control"><span>fieldKey</span><p><code>{field.fieldKey}</code></p></div>
          <div className="static-readonly-control"><span>显示名称</span><p>{field.displayName?.trim() || '未设置'}</p></div>
          <div className="static-readonly-pair">
            <div className="static-readonly-control"><span>字段类型</span><p>{editorTypeLabels[value.type]}</p></div>
            <div className="static-readonly-control"><span>必填状态</span><p className={field.required ? 'is-required' : ''}>{field.required ? '必填' : '可选'}</p></div>
          </div>
          <div className="static-readonly-control"><span>字段说明</span><p className="static-readonly-description">{field.description?.trim() || '未填写说明'}</p></div>
        </div>
      </section>

      <section className="static-inspector-card static-value-card" aria-labelledby={`static-value-${index}`}>
        <h3 id={`static-value-${index}`}>约束与引用</h3>
        {details.length > 0 ? (
          <dl className="static-value-details">
            {details.map((detail) => <div key={detail.label}><dt>{detail.label}</dt><dd>{detail.value}</dd></div>)}
          </dl>
        ) : <p className="static-no-constraints">未设置约束</p>}
      </section>
    </aside>
  );
}

function staticValueDetails(value: EditorValue): Array<{ label: string; value: string }> {
  switch (value.type) {
    case 'text':
      return compactStaticDetails([
        ['最小长度', value.minLength.enabled ? value.minLength.value : ''],
        ['最大长度', value.maxLength.enabled ? value.maxLength.value : ''],
        ['正则表达式', value.pattern.enabled ? value.pattern.value : ''],
        ['枚举值', value.enumValues.enabled ? value.enumValues.values.join('、') : ''],
        ['固定值', value.constValue.enabled ? value.constValue.value : ''],
      ]);
    case 'decimal':
      return compactStaticDetails([
        ['最小值', value.min.enabled ? value.min.value : ''],
        ['大于', value.exclusiveMin.enabled ? value.exclusiveMin.value : ''],
        ['最大值', value.max.enabled ? value.max.value : ''],
        ['小于', value.exclusiveMax.enabled ? value.exclusiveMax.value : ''],
        ['倍数', value.multipleOf.enabled ? value.multipleOf.value : ''],
        ['枚举值', value.enumValues.enabled ? value.enumValues.values.join('、') : ''],
        ['固定值', value.constValue.enabled ? value.constValue.value : ''],
      ]);
    case 'date':
    case 'time':
      return compactStaticDetails([
        ['最小值', value.min.enabled ? value.min.value : ''],
        ['大于', value.exclusiveMin.enabled ? value.exclusiveMin.value : ''],
        ['最大值', value.max.enabled ? value.max.value : ''],
        ['小于', value.exclusiveMax.enabled ? value.exclusiveMax.value : ''],
        ['枚举值', value.enumValues.enabled ? value.enumValues.values.join('、') : ''],
        ['固定值', value.constValue.enabled ? value.constValue.value : ''],
      ]);
    case 'boolean':
      return compactStaticDetails([['固定值', value.constValue.enabled ? value.constValue.value : '']]);
    case 'reference':
      return [{ label: '引用目标', value: `${value.schemaKey}@${value.versionTag}` }];
    case 'array':
      return compactStaticDetails([
        ['元素类型', editorTypeLabels[value.items.type]],
        ['最少元素', value.minItems.enabled ? value.minItems.value : ''],
        ['最多元素', value.maxItems.enabled ? value.maxItems.value : ''],
        ['元素唯一', value.uniqueItems ? '是' : ''],
        ['元素配置', summarizeEditorValue(value.items)],
      ]);
  }
}

function compactStaticDetails(entries: Array<[string, string]>): Array<{ label: string; value: string }> {
  return entries.filter(([, value]) => value !== '').map(([label, value]) => ({ label, value }));
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
