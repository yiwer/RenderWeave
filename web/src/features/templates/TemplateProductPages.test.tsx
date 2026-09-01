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

vi.mock('./template-product-api', () => api);
vi.mock('../resources/resource-api', () => resources);
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

  it('creates from a real StaticSchema selection and navigates to the exact editor URL', async () => {
    resources.listStaticSchemasRequest.mockResolvedValue({
      items: [{
        schemaKey: 'price-tag',
        versionTag: 'v1',
        origin: 'DRAFT',
        displayName: '门店价签结构',
        fieldCount: 5,
        referenceDepth: 0,
        publishedAt: '2026-08-25T05:00:00Z',
      }],
      page: 1,
      size: 50,
      total: 1,
    });
    api.createTemplateRequest.mockResolvedValue({
      templateId: 'template-new',
      disclosure: 'OPAQUE',
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

function renderCreateRoute() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/templates/new']}>
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
