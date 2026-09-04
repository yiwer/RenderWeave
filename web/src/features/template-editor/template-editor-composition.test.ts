import { beforeEach, describe, expect, it, vi } from 'vitest';

import * as generated from '../../api/generated';
import {
  defaultTemplateEditorCompositionTransport,
  loadTemplateCompositionCatalog,
  loadTemplateCompositionCurrent,
  TemplateCompositionIntegrityError,
} from './template-editor-composition';

vi.mock('../../api/generated', () => ({
  listTemplates: vi.fn(),
  getTemplateCurrent: vi.fn(),
}));

const TEMPLATE_ID = '11111111-1111-4111-8111-111111111111';

describe('Template Editor composition transport', () => {
  beforeEach(() => vi.clearAllMocks());

  it('loads the real catalog and only the explicitly selected current', async () => {
    vi.mocked(generated.listTemplates).mockResolvedValue({
      data: { items: [{
        templateId: TEMPLATE_ID,
        displayName: '商品单项',
        staticSchema: { schemaKey: 'product', versionTag: 'v1' },
        revision: 4,
        readiness: 'READY',
        updatedAt: '2026-09-03T00:00:00Z',
      }] },
    } as never);
    vi.mocked(generated.getTemplateCurrent).mockResolvedValue({
      data: {
        templateId: TEMPLATE_ID,
        disclosure: 'READABLE',
        revision: 4,
        staticSchema: { schemaKey: 'product', versionTag: 'v1' },
        contentHash: 'a'.repeat(64),
        readiness: 'READY',
        designDsl: {
          dslVersion: 'renderweave-design/1.0',
          expressionProfile: 'renderweave-expression/1.0',
          displayName: '商品单项',
          definitions: [],
          designRoot: {
            nodeId: TEMPLATE_ID, kind: 'canvas', widthMm: 10, heightMm: 10,
            bindings: [], children: [],
          },
        },
      },
    } as never);

    const entries = await loadTemplateCompositionCatalog(
      defaultTemplateEditorCompositionTransport,
    );
    expect(entries).toHaveLength(1);
    expect(generated.getTemplateCurrent).not.toHaveBeenCalled();

    await expect(loadTemplateCompositionCurrent(
      entries[0]!,
      defaultTemplateEditorCompositionTransport,
    )).resolves.toEqual(expect.objectContaining({ templateId: TEMPLATE_ID, revision: 4 }));
    expect(generated.listTemplates).toHaveBeenCalledWith({ query: { limit: 50 } });
    expect(generated.getTemplateCurrent).toHaveBeenCalledWith({ path: { templateId: TEMPLATE_ID } });
  });

  it('fails closed when selected current drifts from its catalog identity', async () => {
    const entry = {
      templateId: TEMPLATE_ID,
      displayName: '商品单项',
      staticSchema: { schemaKey: 'product', versionTag: 'v1' },
      revision: 4,
      readiness: 'READY' as const,
      updatedAt: '2026-09-03T00:00:00Z',
    };
    const transport = {
      listCatalog: vi.fn(),
      getCurrent: vi.fn(async () => ({
        templateId: TEMPLATE_ID,
        disclosure: 'READABLE' as const,
        revision: 5,
        staticSchema: entry.staticSchema,
        contentHash: 'b'.repeat(64),
        readiness: 'READY' as const,
        designDsl: {} as never,
      })),
    };
    await expect(loadTemplateCompositionCurrent(entry, transport))
      .rejects.toBeInstanceOf(TemplateCompositionIntegrityError);
  });

  it('fails closed when readiness changes without a revision change', async () => {
    const entry = {
      templateId: TEMPLATE_ID,
      displayName: '商品单项',
      staticSchema: { schemaKey: 'product', versionTag: 'v1' },
      revision: 4,
      readiness: 'READY' as const,
      updatedAt: '2026-09-03T00:00:00Z',
    };
    const transport = {
      listCatalog: vi.fn(),
      getCurrent: vi.fn(async () => ({
        templateId: TEMPLATE_ID,
        disclosure: 'READABLE' as const,
        revision: 4,
        staticSchema: entry.staticSchema,
        contentHash: 'c'.repeat(64),
        readiness: 'STALE' as const,
        designDsl: {} as never,
      })),
    };

    await expect(loadTemplateCompositionCurrent(entry, transport))
      .rejects.toBeInstanceOf(TemplateCompositionIntegrityError);
  });
});
