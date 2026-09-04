// @vitest-environment happy-dom

import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  TemplateCreatePage,
  TemplateEditorPage,
  TemplateListPage,
} from './TemplateProductPages';

const api = vi.hoisted(() => ({
  createTemplateRequest: vi.fn(),
  listTemplatesRequest: vi.fn(),
}));
const resources = vi.hoisted(() => ({
  listStaticSchemasRequest: vi.fn(),
}));
const schemaStudio = vi.hoisted(() => ({
  getStaticSnapshotRequest: vi.fn(),
}));

vi.mock('./template-product-api', () => api);
vi.mock('../resources/resource-api', () => resources);
vi.mock('../schema-studio/lossless-api', () => schemaStudio);
vi.mock('../template-editor/TemplateEditorShell', () => ({
  TemplateEditorSurface: ({
    templateId,
    previewTransport,
  }: {
    templateId: string;
    previewTransport?: { assurance?: string };
  }) => (
    <main
      aria-label="Template editor bridge"
      data-preview-assurance={previewTransport?.assurance ?? 'default'}
    >{templateId}</main>
  ),
}));

afterEach(cleanup);

beforeEach(() => {
  vi.clearAllMocks();
});

describe('Template final-product page substrate', () => {
  it('searches the safe catalog and continues with the server cursor', async () => {
    api.listTemplatesRequest
      .mockResolvedValueOnce({
        items: [catalogEntry('template-alpha', 'Alpha 价签', '2026-08-25T07:00:00Z')],
        nextCursor: 'cursor-1',
      })
      .mockResolvedValueOnce({
        items: [catalogEntry('template-beta', 'Beta 海报', '2026-08-25T06:00:00Z')],
      })
      .mockResolvedValue({ items: [] });

    renderRoute('/templates', '/templates', <TemplateListPage />);

    expect(await screen.findByRole('heading', { name: '模板' })).toBeTruthy();
    expect((await screen.findByRole('link', { name: /Alpha 价签/ })).getAttribute('href'))
      .toBe('/templates/template-alpha');
    expect(screen.getByRole('searchbox', { name: '搜索模板' }).getAttribute('maxlength'))
      .toBe('200');
    fireEvent.click(screen.getByRole('button', { name: '继续加载' }));
    expect(await screen.findByRole('link', { name: /Beta 海报/ })).toBeTruthy();
    expect(api.listTemplatesRequest).toHaveBeenNthCalledWith(2, '', 'cursor-1', 20);

    fireEvent.change(screen.getByRole('searchbox', { name: '搜索模板' }), {
      target: { value: ' beta ' },
    });
    await waitFor(() => {
      expect(api.listTemplatesRequest).toHaveBeenCalledWith('beta', undefined, 20);
    });
  });

  it('searches and pages the complete visible StaticSchema catalog', async () => {
    resources.listStaticSchemasRequest.mockImplementation((page: number, size: number, search: string) =>
      Promise.resolve({
        items: [staticSchemaSummary(
          page === 1 ? 'archive-alpha' : 'archive-beta',
          page === 1 ? 'Archive Alpha' : 'Archive Beta',
        )],
        page,
        size,
        total: search === 'archive' ? 18 : 1,
      }));

    renderCreateRoute();

    const search = await screen.findByRole('searchbox', { name: '搜索 StaticSchema' });
    fireEvent.change(search, { target: { value: ' archive ' } });
    await waitFor(() => expect(resources.listStaticSchemasRequest).toHaveBeenCalledWith(
      1,
      9,
      'archive',
      'PUBLISHED_DESC',
      'ALL',
    ));
    fireEvent.click(await screen.findByRole('button', { name: '下一页' }));
    await waitFor(() => expect(resources.listStaticSchemasRequest).toHaveBeenCalledWith(
      2,
      9,
      'archive',
      'PUBLISHED_DESC',
      'ALL',
    ));
    expect(await screen.findByText(/Archive Beta/)).toBeTruthy();
  });

  it('preselects the exact StaticSchema handed off by product navigation', async () => {
    resources.listStaticSchemasRequest.mockResolvedValue({
      items: [staticSchemaSummary('newest', 'Newest schema')],
      page: 1,
      size: 9,
      total: 1,
    });
    schemaStudio.getStaticSnapshotRequest.mockResolvedValue({
      definition: { displayName: 'Exact archived schema' },
    });

    renderCreateRoute('/templates/new?schemaKey=archived-price&versionTag=v7');

    expect((await screen.findByRole('button', { name: 'StaticSchema' })).textContent)
      .toContain('Exact archived schema · archived-price@v7');
    expect(schemaStudio.getStaticSnapshotRequest).toHaveBeenCalledWith('archived-price', 'v7');
  });

  it('keeps the visible catalog selectable when an exact handoff is no longer readable', async () => {
    resources.listStaticSchemasRequest.mockResolvedValue({
      items: [staticSchemaSummary('visible-fallback', 'Visible fallback')],
      page: 1,
      size: 9,
      total: 1,
    });
    schemaStudio.getStaticSnapshotRequest.mockRejectedValue(new Error('not visible'));

    renderCreateRoute('/templates/new?schemaKey=hidden&versionTag=v1');

    expect((await screen.findByRole('alert')).textContent)
      .toContain('无法读取导航指定的精确 StaticSchema');
    expect((await screen.findByRole('button', { name: 'StaticSchema' })).textContent)
      .toContain('Visible fallback · visible-fallback@v1');
  });

  it('opens the exact editor only for a readable create commit', async () => {
    resources.listStaticSchemasRequest.mockResolvedValue({
      items: [staticSchemaSummary('price-tag', '门店价签结构')],
      page: 1,
      size: 9,
      total: 1,
    });
    api.createTemplateRequest.mockResolvedValue({
      kind: 'READABLE',
      template: { templateId: 'template-new', disclosure: 'READABLE' },
    });

    renderCreateRoute();

    expect(await screen.findByRole('heading', { name: '新建模板' })).toBeTruthy();
    expect((await screen.findByRole('button', { name: 'StaticSchema' })).textContent)
      .toContain('门店价签结构');
    fireEvent.change(screen.getByRole('textbox', { name: 'Template 名称' }), {
      target: { value: '夏季价签' },
    });
    fireEvent.change(screen.getByRole('spinbutton', { name: '画布宽度（毫米）' }), {
      target: { value: '148' },
    });
    fireEvent.change(screen.getByRole('spinbutton', { name: '画布高度（毫米）' }), {
      target: { value: '210' },
    });
    fireEvent.click(screen.getByRole('button', { name: '创建并打开' }));

    await waitFor(() => expect(api.createTemplateRequest).toHaveBeenCalledWith({
      schemaKey: 'price-tag',
      versionTag: 'v1',
      displayName: '夏季价签',
      widthMm: 148,
      heightMm: 210,
    }));
    expect(await screen.findByRole('main', { name: 'created Template route' })).toBeTruthy();
    expect(screen.getByText('template-new')).toBeTruthy();
  });

  it('shows only an opaque receipt and never opens an unreadable editor baseline', async () => {
    resources.listStaticSchemasRequest.mockResolvedValue({
      items: [staticSchemaSummary('price-tag', '门店价签结构')],
      page: 1,
      size: 9,
      total: 1,
    });
    api.createTemplateRequest.mockResolvedValue({
      kind: 'OPAQUE',
      receipt: { templateId: 'template-opaque', disclosure: 'OPAQUE' },
    });

    renderCreateRoute();
    fireEvent.click(await screen.findByRole('button', { name: '创建并打开' }));

    const receipt = (await screen.findByText('Template 已提交')).closest('section');
    expect(receipt).not.toBeNull();
    expect(receipt?.textContent).toContain('template-opaque');
    expect(receipt?.textContent).toContain('不具备读取权限');
    expect(screen.queryByRole('main', { name: 'created Template route' })).toBeNull();
    expect(screen.queryByRole('textbox', { name: 'Template 名称' })).toBeNull();
  });

  it('preserves intent and refreshes the Template catalog after a transport-unknown create', async () => {
    resources.listStaticSchemasRequest.mockResolvedValue({
      items: [staticSchemaSummary('price-tag', '门店价签结构')],
      page: 1,
      size: 9,
      total: 1,
    });
    api.createTemplateRequest.mockResolvedValue({ kind: 'TRANSPORT_UNKNOWN' });
    api.listTemplatesRequest.mockResolvedValue({ items: [] });

    renderCreateRoute();
    const name = await screen.findByRole('textbox', { name: 'Template 名称' });
    fireEvent.change(name, { target: { value: '保留这个创建意图' } });
    fireEvent.click(screen.getByRole('button', { name: '创建并打开' }));

    const warning = await screen.findByRole('alert');
    expect(warning.textContent).toContain('结果未知');
    expect(warning.textContent).toContain('可能创建重复 Template');
    expect((screen.getByRole('textbox', { name: 'Template 名称' }) as HTMLInputElement).value)
      .toBe('保留这个创建意图');
    expect(screen.getByRole('button', { name: '我已检查目录，仍要再次创建' })).toBeTruthy();
    await waitFor(() => expect(api.listTemplatesRequest).toHaveBeenCalledWith('', undefined, 20));
    expect(api.createTemplateRequest).toHaveBeenCalledOnce();
  });

  it('passes the opaque route identity directly to the real editor surface', () => {
    renderRoute(
      '/templates/template-42',
      '/templates/:templateId',
      <TemplateEditorPage />,
    );

    expect(screen.getByRole('main', { name: 'Template editor bridge' }).textContent)
      .toBe('template-42');
    expect(screen.getByRole('main', { name: 'Template editor bridge' })
      .getAttribute('data-preview-assurance')).toBe('default');
  });

  it('selects the non-certified candidate transport only through the exact local opt-in', () => {
    renderRoute(
      '/templates/template-42?candidatePreview=local',
      '/templates/:templateId',
      <TemplateEditorPage />,
    );

    expect(screen.getByRole('main', { name: 'Template editor bridge' })
      .getAttribute('data-preview-assurance')).toBe('candidate');
  });
});

function renderRoute(path: string, pattern: string, element: React.ReactNode) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <Routes><Route path={pattern} element={element} /></Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

function renderCreateRoute(path = '/templates/new') {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/templates/new" element={<TemplateCreatePage />} />
          <Route
            path="/templates/:templateId"
            element={<main aria-label="created Template route">template-new</main>}
          />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

function staticSchemaSummary(schemaKey: string, displayName: string) {
  return {
    schemaKey,
    versionTag: 'v1',
    origin: 'DRAFT',
    displayName,
    fieldCount: 5,
    referenceDepth: 1,
    publishedAt: '2026-08-25T05:00:00Z',
  };
}

function catalogEntry(templateId: string, displayName: string, updatedAt: string) {
  return {
    templateId,
    displayName,
    staticSchema: { schemaKey: 'price-tag', versionTag: 'v1' },
    revision: 0,
    readiness: 'READY' as const,
    updatedAt,
  };
}
