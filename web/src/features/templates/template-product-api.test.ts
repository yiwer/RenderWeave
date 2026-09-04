import { afterEach, describe, expect, it, vi } from 'vitest';

import {
  createTemplateRequest,
  listTemplatesRequest,
} from './template-product-api';

const generated = vi.hoisted(() => ({
  createTemplate: vi.fn(),
  listTemplates: vi.fn(),
}));

vi.mock('../../api/generated', () => generated);

afterEach(() => {
  vi.restoreAllMocks();
  vi.clearAllMocks();
});

describe('Template product API', () => {
  it('uses the generated stable-cursor catalog contract', async () => {
    generated.listTemplates.mockResolvedValue({
      data: { items: [], nextCursor: 'next-page' },
      error: undefined,
    });

    await expect(listTemplatesRequest('  价签  ', 'cursor-1', 17)).resolves.toEqual({
      items: [],
      nextCursor: 'next-page',
    });
    expect(generated.listTemplates).toHaveBeenCalledWith({
      query: { search: '价签', cursor: 'cursor-1', limit: 17 },
    });
  });

  it('creates the exact admitted minimal Canvas and classifies a readable commit', async () => {
    vi.spyOn(globalThis.crypto, 'randomUUID')
      .mockReturnValue('11111111-1111-4111-8111-111111111111');
    generated.createTemplate.mockResolvedValue({
      data: {
        templateId: 'template-1',
        disclosure: 'READABLE',
        revision: 0,
        staticSchema: { schemaKey: 'price-tag', versionTag: 'v1' },
        contentHash: `sha256:${'a'.repeat(64)}`,
        readiness: 'READY',
        designDsl: {},
      },
      error: undefined,
    });

    await expect(createTemplateRequest({
      schemaKey: 'price-tag',
      versionTag: 'v1',
      displayName: '门店价签',
      widthMm: 210,
      heightMm: 297,
    })).resolves.toMatchObject({
      kind: 'READABLE',
      template: { templateId: 'template-1', disclosure: 'READABLE' },
    });

    expect(generated.createTemplate).toHaveBeenCalledWith({
      query: { schemaKey: 'price-tag', versionTag: 'v1' },
      body: {
        dslVersion: 'renderweave-design/1.0',
        expressionProfile: 'renderweave-expression/1.0',
        displayName: '门店价签',
        definitions: [],
        designRoot: {
          nodeId: '11111111-1111-4111-8111-111111111111',
          kind: 'canvas',
          widthMm: 210,
          heightMm: 297,
          bindings: [],
          children: [],
        },
      },
    });
  });

  it('keeps an opaque commit as a receipt instead of an editor baseline', async () => {
    generated.createTemplate.mockResolvedValue({
      data: { templateId: 'template-opaque', disclosure: 'OPAQUE' },
      error: undefined,
    });

    await expect(createTemplateRequest(createInput())).resolves.toEqual({
      kind: 'OPAQUE',
      receipt: { templateId: 'template-opaque', disclosure: 'OPAQUE' },
    });
  });

  it('classifies a lost transport response as unknown without retrying the create', async () => {
    generated.createTemplate.mockRejectedValue(new TypeError('Failed to fetch'));

    await expect(createTemplateRequest(createInput())).resolves.toEqual({
      kind: 'TRANSPORT_UNKNOWN',
    });
    expect(generated.createTemplate).toHaveBeenCalledOnce();
  });

  it('classifies a client-wrapped fetch failure as unknown', async () => {
    generated.createTemplate.mockResolvedValue({
      data: undefined,
      error: new TypeError('Failed to fetch'),
    });

    await expect(createTemplateRequest(createInput())).resolves.toEqual({
      kind: 'TRANSPORT_UNKNOWN',
    });
  });

  it('keeps an explicit HTTP problem as a known failure', async () => {
    generated.createTemplate.mockResolvedValue({
      data: undefined,
      error: {
        type: 'about:blank',
        title: 'Template invalid',
        status: 422,
        code: 'STATIC_SCHEMA_NOT_VISIBLE',
        traceId: 'trace-1',
        detail: 'StaticSchema is not visible.',
      },
    });

    await expect(createTemplateRequest(createInput())).rejects.toThrow('StaticSchema is not visible.');
  });
});

function createInput() {
  return {
    schemaKey: 'price-tag',
    versionTag: 'v1',
    displayName: '门店价签',
    widthMm: 210,
    heightMm: 297,
  };
}
