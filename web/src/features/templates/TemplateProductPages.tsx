import { useInfiniteQuery, useMutation, useQuery } from '@tanstack/react-query';
import {
  ArrowRight,
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
import { ResourceSearchInput } from '../resources/ResourceListControls';
import { formatDateTime } from '../resources/resource-format';
import { useDebouncedValue } from '../resources/resource-list-hooks';
import { listStaticSchemasRequest } from '../resources/resource-api';
import {
  createTemplateRequest,
  listTemplatesRequest,
  type CreateTemplateInput,
} from './template-product-api';
import './template-product.css';

const TEMPLATE_PAGE_SIZE = 20;

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
  const [displayName, setDisplayName] = useState('未命名模板');
  const [widthMm, setWidthMm] = useState('210');
  const [heightMm, setHeightMm] = useState('297');
  const [selectedSchema, setSelectedSchema] = useState('');
  const [validationError, setValidationError] = useState<string>();
  const schemas = useQuery({
    queryKey: ['static-schemas', 'template-create'],
    queryFn: () => listStaticSchemasRequest(1, 50, '', 'PUBLISHED_DESC', 'ALL'),
  });
  const schemaOptions = (schemas.data?.items ?? []).map((item) => ({
    value: schemaIdentity(item.schemaKey, item.versionTag),
    label: `${item.displayName} · ${item.schemaKey}@${item.versionTag}`,
  }));
  const effectiveSchema = selectedSchema || schemaOptions[0]?.value || '';
  const mutation = useMutation({
    mutationFn: (input: CreateTemplateInput) => createTemplateRequest(input),
    onSuccess: (created) => navigate(`/templates/${encodeURIComponent(created.templateId)}`),
  });

  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const selected = schemaFromIdentity(effectiveSchema);
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
      {schemas.isPending ? <ResourceLoading label="正在读取可用 StaticSchema" /> : null}
      {schemas.isError ? (
        <ResourceError error={schemas.error} onRetry={() => void schemas.refetch()} />
      ) : null}
      {schemas.data && schemas.data.items.length === 0 ? (
        <section className="resource-empty template-empty" role="status">
          <FileStack aria-hidden="true" size={25} />
          <strong>没有可绑定的 StaticSchema</strong>
          <span>先发布一份 StaticSchema；Template 不会绑定可变 Draft。</span>
          <Link className="button ghost-button" to="/static-schemas">查看数据结构资产</Link>
        </section>
      ) : null}
      {schemas.data && schemas.data.items.length > 0 ? (
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
                value={effectiveSchema}
                options={schemaOptions}
                onChange={setSelectedSchema}
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
              {mutation.isPending ? '正在创建' : '创建并打开'}
            </button>
          </footer>
        </form>
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
