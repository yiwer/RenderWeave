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

  it('creates the exact admitted minimal Canvas and returns either disclosure shape', async () => {
    vi.spyOn(globalThis.crypto, 'randomUUID')
      .mockReturnValue('11111111-1111-4111-8111-111111111111');
    generated.createTemplate.mockResolvedValue({
      data: { templateId: 'template-1', disclosure: 'OPAQUE' },
      error: undefined,
    });

    await expect(createTemplateRequest({
      schemaKey: 'price-tag',
      versionTag: 'v1',
      displayName: '门店价签',
      widthMm: 210,
      heightMm: 297,
    })).resolves.toEqual({ templateId: 'template-1', disclosure: 'OPAQUE' });

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
});
