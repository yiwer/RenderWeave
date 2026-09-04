import {
  keepPreviousData,
  useInfiniteQuery,
  useMutation,
  useQuery,
  useQueryClient,
} from '@tanstack/react-query';
import {
  ArrowRight,
  CheckCircle2,
  CircleAlert,
  FileStack,
  LoaderCircle,
  Plus,
  RefreshCw,
  ShieldCheck,
} from 'lucide-react';
import { useState, type FormEvent } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';

import { SelectField } from '../../components/SelectField';
import { TemplateEditorSurface } from '../template-editor/TemplateEditorShell';
import { localCandidateTemplatePreviewTransport } from '../template-editor/template-preview';
import { ResourceError, ResourceLoading } from '../resources/DraftListPage';
import { ResourceFrame } from '../resources/ResourceFrame';
import { ResourcePagination, ResourceSearchInput } from '../resources/ResourceListControls';
import { formatDateTime } from '../resources/resource-format';
import { useDebouncedValue } from '../resources/resource-list-hooks';
import { listStaticSchemasRequest } from '../resources/resource-api';
import { getStaticSnapshotRequest } from '../schema-studio/lossless-api';
import {
  createTemplateRequest,
  listTemplatesRequest,
  type CreateTemplateInput,
  type CreateTemplateOutcome,
} from './template-product-api';
import './template-product.css';

const TEMPLATE_PAGE_SIZE = 20;
const TEMPLATE_CREATE_SCHEMA_PAGE_SIZE = 9;

export function TemplateListPage() {
  const [search, setSearch] = useState('');
  const debouncedSearch = useDebouncedValue(search.trim());
  const query = useInfiniteQuery({
    queryKey: ['templates', 'catalog', debouncedSearch],
    queryFn: ({ pageParam }) => listTemplatesRequest(
      debouncedSearch,
      pageParam ?? undefined,
      TEMPLATE_PAGE_SIZE,
    ),
    initialPageParam: null as string | null,
    getNextPageParam: (lastPage) => lastPage.nextCursor,
  });
  const items = query.data?.pages.flatMap((page) => page.items) ?? [];

  return (
    <ResourceFrame
      title="模板"
      description="基于永久 StaticSchema 创建并维护可审计的 DesignDSL；目录只展示当前 owner scope 内的 ACTIVE Template。"
      actions={(
        <Link className="button primary-button" to="/templates/new">
          <Plus aria-hidden="true" size={15} />新建模板
        </Link>
      )}
    >
      <section className="resource-toolbar template-list-toolbar" aria-label="模板目录工具">
        <ResourceSearchInput
          id="template-search"
          value={search}
          label="搜索模板"
          placeholder="搜索名称或 Template ID"
          maxLength={200}
          onChange={setSearch}
        />
        <div className="resource-summary" aria-live="polite">
          {query.isFetching && !query.isPending ? (
            <LoaderCircle className="spin" aria-hidden="true" size={13} />
          ) : null}
          <span>已载入 {items.length} 项</span>
        </div>
      </section>

      {query.isPending ? <ResourceLoading label="正在读取 Template 目录" /> : null}
      {query.isError ? (
        <ResourceError error={query.error} onRetry={() => void query.refetch()} />
      ) : null}
      {query.data && items.length === 0 ? (
        <section className="resource-empty template-empty" role="status">
          <FileStack aria-hidden="true" size={25} />
          <strong>{debouncedSearch ? '没有匹配的 Template' : '还没有 Template'}</strong>
          <span>
            {debouncedSearch
              ? '缩短关键词，或直接搜索完整 Template ID。'
              : '选择一份永久 StaticSchema，创建第一个最小 Canvas。'}
          </span>
          {!debouncedSearch ? (
            <Link className="button primary-button" to="/templates/new">
              <Plus aria-hidden="true" size={15} />新建模板
            </Link>
          ) : null}
        </section>
      ) : null}

      {items.length > 0 ? (
        <div className="template-card-grid" aria-label="Template 目录">
          {items.map((item) => (
            <Link
              className="template-card"
              key={item.templateId}
              to={`/templates/${encodeURIComponent(item.templateId)}`}
            >
              <div className="template-card-heading">
                <span className={`template-readiness is-${item.readiness.toLowerCase()}`}>
                  <ShieldCheck aria-hidden="true" size={13} />{readinessLabel(item.readiness)}
                </span>
                <ArrowRight aria-hidden="true" size={16} />
              </div>
              <strong>{item.displayName}</strong>
              <code title={item.templateId}>{item.templateId}</code>
              <dl>
                <div><dt>StaticSchema</dt><dd>{item.staticSchema.schemaKey}@{item.staticSchema.versionTag}</dd></div>
                <div><dt>Revision</dt><dd>{item.revision}</dd></div>
                <div><dt>更新时间</dt><dd>{formatDateTime(item.updatedAt)}</dd></div>
              </dl>
            </Link>
          ))}
        </div>
      ) : null}

      {query.hasNextPage ? (
        <div className="template-load-more">
          <button
            type="button"
            className="button ghost-button"
            disabled={query.isFetchingNextPage}
            onClick={() => void query.fetchNextPage()}
          >
            {query.isFetchingNextPage ? (
              <LoaderCircle className="spin" aria-hidden="true" size={15} />
            ) : (
              <RefreshCw aria-hidden="true" size={15} />
            )}
            {query.isFetchingNextPage ? '正在加载' : '继续加载'}
          </button>
        </div>
      ) : null}
    </ResourceFrame>
  );
}

export function TemplateCreatePage() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [searchParams] = useSearchParams();
  const requestedSchema = requestedStaticSchema(searchParams);
  const [displayName, setDisplayName] = useState('未命名模板');
  const [widthMm, setWidthMm] = useState('210');
  const [heightMm, setHeightMm] = useState('297');
  const [schemaSearch, setSchemaSearch] = useState('');
  const [schemaPage, setSchemaPage] = useState(1);
  const [schemaPageSize, setSchemaPageSize] = useState(TEMPLATE_CREATE_SCHEMA_PAGE_SIZE);
  const [selectedSchema, setSelectedSchema] = useState<SchemaOption>();
  const [validationError, setValidationError] = useState<string>();
  const [createOutcome, setCreateOutcome] = useState<CreateTemplateOutcome>();
  const [catalogRefresh, setCatalogRefresh] = useState<CatalogRefreshState>('idle');
  const debouncedSchemaSearch = useDebouncedValue(schemaSearch.trim());
  const schemas = useQuery({
    queryKey: [
      'static-schemas',
      'template-create',
      schemaPage,
      schemaPageSize,
      debouncedSchemaSearch,
    ],
    queryFn: () => listStaticSchemasRequest(
      schemaPage,
      schemaPageSize,
      debouncedSchemaSearch,
      'PUBLISHED_DESC',
      'ALL',
    ),
    placeholderData: keepPreviousData,
  });
  const exactSchema = useQuery({
    queryKey: [
      'static-schema',
      requestedSchema?.schemaKey,
      requestedSchema?.versionTag,
      'template-create',
    ],
    queryFn: () => getStaticSnapshotRequest(
      requestedSchema!.schemaKey,
      requestedSchema!.versionTag,
    ),
    enabled: requestedSchema !== undefined,
  });
  const catalogOptions: SchemaOption[] = (schemas.data?.items ?? []).map((item) => ({
    value: schemaIdentity(item.schemaKey, item.versionTag),
    label: `${item.displayName} · ${item.schemaKey}@${item.versionTag}`,
  }));
  const exactOption: SchemaOption | undefined = requestedSchema && exactSchema.data
    ? {
        value: schemaIdentity(requestedSchema.schemaKey, requestedSchema.versionTag),
        label: `${exactSchema.data.definition.displayName} · ${requestedSchema.schemaKey}@${requestedSchema.versionTag}`,
      }
    : undefined;
  const schemaOptions = uniqueSchemaOptions(selectedSchema, exactOption, ...catalogOptions);
  const effectiveSchema = selectedSchema
    ?? (requestedSchema && !exactSchema.isError ? exactOption : catalogOptions[0]);
  const mutation = useMutation({
    mutationFn: (input: CreateTemplateInput) => createTemplateRequest(input),
    retry: false,
    onSuccess: (outcome) => {
      if (outcome.kind === 'READABLE') {
        navigate(`/templates/${encodeURIComponent(outcome.template.templateId)}`);
        return;
      }
      setCreateOutcome(outcome);
      if (outcome.kind === 'TRANSPORT_UNKNOWN') {
        setCatalogRefresh('refreshing');
        void listTemplatesRequest('', undefined, TEMPLATE_PAGE_SIZE).then((catalog) => {
          queryClient.setQueryData(
            ['templates', 'catalog', ''],
            { pages: [catalog], pageParams: [null] },
          );
          setCatalogRefresh('refreshed');
        }).catch(() => setCatalogRefresh('failed'));
      }
    },
  });

  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const selected = schemaFromIdentity(effectiveSchema?.value ?? '');
    const normalizedName = displayName.trim();
    const parsedWidth = Number(widthMm);
    const parsedHeight = Number(heightMm);
    const error = validateCreateInput(normalizedName, selected, parsedWidth, parsedHeight);
    setValidationError(error);
    if (error || !selected) return;
    const input: CreateTemplateInput = {
      schemaKey: selected.schemaKey,
      versionTag: selected.versionTag,
      displayName: normalizedName,
      widthMm: parsedWidth,
      heightMm: parsedHeight,
    };
    mutation.mutate(input);
  };

  return (
    <ResourceFrame
      title="新建模板"
      description="Template 永久绑定一份精确 StaticSchema；创建后从 revision 0 开始追加不可变 DesignDSL 修订。"
      detail
      breadcrumbs={[{ label: '模板', to: '/templates' }, { label: '新建模板' }]}
    >
      {createOutcome?.kind === 'OPAQUE' ? (
        <section className="template-create-outcome is-opaque" role="status" aria-live="polite">
          <CheckCircle2 aria-hidden="true" size={24} />
          <div>
            <strong>Template 已提交</strong>
            <p>当前调用者不具备读取权限，因此只显示服务端允许的不透明回执，不会打开编辑器。</p>
            <code>{createOutcome.receipt.templateId}</code>
          </div>
          <Link className="button ghost-button" to="/templates">返回 Template 目录</Link>
        </section>
      ) : null}
      {createOutcome?.kind !== 'OPAQUE' ? (
        <>
      {schemas.isPending ? <ResourceLoading label="正在读取可用 StaticSchema" /> : null}
      {schemas.isError ? (
        <ResourceError error={schemas.error} onRetry={() => void schemas.refetch()} />
      ) : null}
      {exactSchema.isError ? (
        <p className="template-form-error" role="alert">
          无法读取导航指定的精确 StaticSchema；请从下方可见目录重新选择。
        </p>
      ) : null}
      {schemas.data ? (
        <section className="template-schema-browser" aria-label="StaticSchema 目录">
          <ResourceSearchInput
            id="template-static-schema-search"
            value={schemaSearch}
            label="搜索 StaticSchema"
            placeholder="搜索 schemaKey、版本或显示名称"
            maxLength={128}
            onChange={(value) => {
              setSchemaSearch(value);
              setSchemaPage(1);
            }}
          />
          <span className="resource-summary" aria-live="polite">
            {schemas.isFetching && !schemas.isPending ? (
              <LoaderCircle className="spin" aria-hidden="true" size={13} />
            ) : null}
            共 {schemas.data.total} 项 · 第 {schemaPage} 页
          </span>
          <ResourcePagination
            label="可绑定 StaticSchema"
            page={schemaPage}
            size={schemaPageSize}
            total={schemas.data.total}
            onPageChange={setSchemaPage}
            onSizeChange={(size) => {
              setSchemaPageSize(size);
              setSchemaPage(1);
            }}
          />
        </section>
      ) : null}
      {schemas.data && schemas.data.items.length === 0 && !effectiveSchema ? (
        <section className="resource-empty template-empty" role="status">
          <FileStack aria-hidden="true" size={25} />
          <strong>{debouncedSchemaSearch ? '没有匹配的 StaticSchema' : '没有可绑定的 StaticSchema'}</strong>
          <span>{debouncedSchemaSearch
            ? '缩短关键词，或搜索精确 schemaKey 与版本。'
            : '先发布一份 StaticSchema；Template 不会绑定可变 Draft。'}</span>
          <Link className="button ghost-button" to="/static-schemas">查看数据结构资产</Link>
        </section>
      ) : null}
      {createOutcome?.kind === 'TRANSPORT_UNKNOWN' ? (
        <section className="template-create-outcome is-unknown" role="alert">
          <CircleAlert aria-hidden="true" size={24} />
          <div>
            <strong>创建结果未知</strong>
            <p>响应在确认结果前中断。已保留全部输入并刷新 Template 目录；再次创建可能创建重复 Template。</p>
            <small>{catalogRefreshLabel(catalogRefresh)}</small>
          </div>
          <Link className="button ghost-button" to="/templates">检查 Template 目录</Link>
        </section>
      ) : null}
      {schemas.data && effectiveSchema ? (
        <form className="template-create-form" onSubmit={submit} noValidate>
          <header>
            <span>最小可保存 Canvas</span>
            <h2>建立 Template 身份</h2>
            <p>首个 revision 只包含已准入的 Canvas kernel，不创建虚构节点、绑定或定义。</p>
          </header>
          <div className="template-create-fields">
            <label className="template-form-field template-schema-field">
              <span>StaticSchema</span>
              <SelectField
                ariaLabel="StaticSchema"
                value={effectiveSchema.value}
                options={schemaOptions}
                onChange={(value) => {
                  const option = schemaOptions.find((candidate) => candidate.value === value);
                  if (option) setSelectedSchema(option);
                }}
                disabled={mutation.isPending}
              />
              <small>创建后不可重新绑定。</small>
            </label>
            <label className="template-form-field">
              <span>Template 名称</span>
              <input
                type="text"
                value={displayName}
                maxLength={128}
                disabled={mutation.isPending}
                onChange={(event) => setDisplayName(event.currentTarget.value)}
              />
            </label>
            <fieldset className="template-canvas-size">
              <legend>Canvas 尺寸</legend>
              <label className="template-form-field">
                <span>画布宽度（毫米）</span>
                <input
                  type="number"
                  min="0.001"
                  step="any"
                  value={widthMm}
                  disabled={mutation.isPending}
                  onChange={(event) => setWidthMm(event.currentTarget.value)}
                />
              </label>
              <label className="template-form-field">
                <span>画布高度（毫米）</span>
                <input
                  type="number"
                  min="0.001"
                  step="any"
                  value={heightMm}
                  disabled={mutation.isPending}
                  onChange={(event) => setHeightMm(event.currentTarget.value)}
                />
              </label>
            </fieldset>
          </div>
          {validationError ? <p className="template-form-error" role="alert">{validationError}</p> : null}
          {mutation.isError ? (
            <p className="template-form-error" role="alert">
              {mutation.error instanceof Error ? mutation.error.message : '创建 Template 失败。'}
            </p>
          ) : null}
          <footer>
            <Link className="button ghost-button" to="/templates">取消</Link>
            <button className="button primary-button" type="submit" disabled={mutation.isPending}>
              {mutation.isPending ? <LoaderCircle className="spin" aria-hidden="true" size={15} /> : <Plus aria-hidden="true" size={15} />}
              {mutation.isPending
                ? '正在创建'
                : createOutcome?.kind === 'TRANSPORT_UNKNOWN'
                  ? '我已检查目录，仍要再次创建'
                  : '创建并打开'}
            </button>
          </footer>
        </form>
      ) : null}
        </>
      ) : null}
    </ResourceFrame>
  );
}

export function TemplateEditorPage() {
  const { templateId = '' } = useParams<{ templateId: string }>();
  const [searchParams] = useSearchParams();
  const candidatePreview = searchParams.getAll('candidatePreview');
  const previewTransport = candidatePreview.length === 1 && candidatePreview[0] === 'local'
    ? localCandidateTemplatePreviewTransport
    : undefined;
  return <TemplateEditorSurface templateId={templateId} previewTransport={previewTransport} />;
}

function readinessLabel(readiness: 'READY' | 'INVALID' | 'STALE'): string {
  switch (readiness) {
    case 'READY': return '就绪';
    case 'INVALID': return '依赖无效';
    case 'STALE': return '待复核';
  }
}

function schemaIdentity(schemaKey: string, versionTag: string): string {
  return `${schemaKey}@${versionTag}`;
}

function schemaFromIdentity(value: string): { schemaKey: string; versionTag: string } | undefined {
  const separator = value.lastIndexOf('@');
  if (separator <= 0 || separator === value.length - 1) return undefined;
  return { schemaKey: value.slice(0, separator), versionTag: value.slice(separator + 1) };
}

interface SchemaOption {
  readonly value: string;
  readonly label: string;
}

type CatalogRefreshState = 'idle' | 'refreshing' | 'refreshed' | 'failed';

function uniqueSchemaOptions(...options: Array<SchemaOption | undefined>): SchemaOption[] {
  return options.filter((option, index, all): option is SchemaOption =>
    option !== undefined && all.findIndex((candidate) => candidate?.value === option.value) === index);
}

function requestedStaticSchema(
  searchParams: URLSearchParams,
): { schemaKey: string; versionTag: string } | undefined {
  const schemaKeys = searchParams.getAll('schemaKey');
  const versionTags = searchParams.getAll('versionTag');
  if (schemaKeys.length !== 1 || versionTags.length !== 1) return undefined;
  const schemaKey = schemaKeys[0]?.trim() ?? '';
  const versionTag = versionTags[0]?.trim() ?? '';
  return schemaKey && versionTag ? { schemaKey, versionTag } : undefined;
}

function catalogRefreshLabel(state: CatalogRefreshState): string {
  switch (state) {
    case 'refreshing': return '正在刷新 Template 目录…';
    case 'refreshed': return 'Template 目录已刷新，请先检查是否已创建。';
    case 'failed': return 'Template 目录刷新失败，请手动打开目录检查。';
    case 'idle': return '';
  }
}

function validateCreateInput(
  displayName: string,
  schema: { schemaKey: string; versionTag: string } | undefined,
  widthMm: number,
  heightMm: number,
): string | undefined {
  if (!schema) return '请选择一份 StaticSchema。';
  if (!displayName) return 'Template 名称不能为空。';
  if (displayName.length > 128) return 'Template 名称不能超过 128 个字符。';
  if (!Number.isFinite(widthMm) || widthMm <= 0) return '画布宽度必须是大于 0 的毫米数。';
  if (!Number.isFinite(heightMm) || heightMm <= 0) return '画布高度必须是大于 0 的毫米数。';
  return undefined;
}
