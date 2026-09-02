// @vitest-environment happy-dom

import { afterEach, describe, expect, it, vi } from 'vitest';

import type {
  AssetCatalogEntry,
  AssetReadableResponse,
  DesignAssetRef,
} from '../../api/generated';
import {
  assetRefFromCatalogEntry,
  defaultTemplateEditorAssetTransport,
  listActiveTemplateAssets,
  resolveTemplateAssetRef,
  TemplateAssetIntegrityError,
  TemplateAssetRequestError,
  type TemplateEditorAssetTransport,
} from './template-editor-assets';

const IMAGE_ID = '00000000-0000-4000-8000-000000000101';
const FONT_ID = '00000000-0000-4000-8000-000000000102';
const DELETED_ID = '00000000-0000-4000-8000-000000000103';

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe('Template Editor Asset seam', () => {
  it('pages the catalog and admits only ACTIVE Assets of the requested kind', async () => {
    const image = catalogEntry({ assetId: IMAGE_ID });
    const deleted = catalogEntry({ assetId: DELETED_ID, lifecycle: 'DELETED' });
    const font = catalogEntry({ assetId: FONT_ID, kind: 'FONT' });
    const listAssets = vi.fn()
      .mockResolvedValueOnce({ items: [image, deleted, font], nextCursor: 'page-2' })
      .mockResolvedValueOnce({ items: [image, catalogEntry({ assetId: DELETED_ID })] });
    const transport = assetTransport({ listAssets });

    await expect(listActiveTemplateAssets('IMAGE', transport)).resolves.toEqual([
      image,
      catalogEntry({ assetId: DELETED_ID }),
    ]);
    expect(listAssets).toHaveBeenNthCalledWith(1, {
      kind: 'IMAGE',
      includeDeleted: false,
      limit: 100,
    }, undefined);
    expect(listAssets).toHaveBeenNthCalledWith(2, {
      kind: 'IMAGE',
      includeDeleted: false,
      cursor: 'page-2',
      limit: 100,
    }, undefined);
  });

  it('fails closed on a repeated stable cursor instead of looping forever', async () => {
    const transport = assetTransport({
      listAssets: vi.fn().mockResolvedValue({ items: [], nextCursor: 'same-page' }),
    });

    await expect(listActiveTemplateAssets('FONT', transport))
      .rejects.toBeInstanceOf(TemplateAssetIntegrityError);
  });

  it('creates a closed AssetRef without copying catalog or browser-only fields', () => {
    const asset = catalogEntry({ assetId: IMAGE_ID });

    expect(assetRefFromCatalogEntry(asset)).toEqual({ assetId: IMAGE_ID });
    expect(Object.keys(assetRefFromCatalogEntry(asset))).toEqual(['assetId']);
  });

  it('resolves an ACTIVE matching Asset while preserving the exact historical ref object', async () => {
    const ref: DesignAssetRef = { assetId: IMAGE_ID };
    const asset = readableAsset({ assetId: IMAGE_ID });
    const transport = assetTransport({ getCurrent: vi.fn().mockResolvedValue(asset) });

    const resolution = await resolveTemplateAssetRef(ref, 'IMAGE', transport);

    expect(resolution).toEqual({ state: 'active', ref, expectedKind: 'IMAGE', asset });
    expect(resolution.ref).toBe(ref);
  });

  it('reports a kind mismatch without rebinding the AssetRef', async () => {
    const ref: DesignAssetRef = { assetId: FONT_ID };
    const asset = readableAsset({ assetId: FONT_ID, kind: 'FONT' });
    const transport = assetTransport({ getCurrent: vi.fn().mockResolvedValue(asset) });

    const resolution = await resolveTemplateAssetRef(ref, 'IMAGE', transport);

    expect(resolution).toEqual({
      state: 'kind-mismatch',
      ref,
      expectedKind: 'IMAGE',
      actualKind: 'FONT',
      asset,
    });
    expect(resolution.ref).toBe(ref);
  });

  it.each([
    [404, 'ASSET_NOT_FOUND', 'missing'],
    [410, 'ASSET_DELETED', 'deleted'],
    [503, 'ASSET_DEPENDENCY_UNAVAILABLE', 'unavailable'],
  ] as const)('maps HTTP %s to the closed %s resolution without clearing the ref', async (
    status,
    code,
    expectedState,
  ) => {
    const ref: DesignAssetRef = { assetId: IMAGE_ID };
    const transport = assetTransport({
      getCurrent: vi.fn().mockRejectedValue(new TemplateAssetRequestError(status, code)),
    });

    const resolution = await resolveTemplateAssetRef(ref, 'IMAGE', transport);

    expect(resolution.state).toBe(expectedState);
    expect(resolution.ref).toBe(ref);
  });

  it('maps an unexpected network failure to unavailable without exposing or changing the ref', async () => {
    const ref: DesignAssetRef = { assetId: IMAGE_ID };
    const transport = assetTransport({
      getCurrent: vi.fn().mockRejectedValue(new TypeError('private endpoint failed')),
    });

    await expect(resolveTemplateAssetRef(ref, 'IMAGE', transport)).resolves.toEqual({
      state: 'unavailable',
      ref,
      expectedKind: 'IMAGE',
      code: 'ASSET_REQUEST_UNAVAILABLE',
    });
  });

  it('wraps a generated HTTP Problem with stable status and code', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({
      type: 'about:blank',
      title: 'Asset service unavailable',
      status: 503,
      detail: 'Try again later.',
      code: 'ASSET_DEPENDENCY_UNAVAILABLE',
      traceId: 'trace-redacted',
    }), {
      status: 503,
      headers: { 'Content-Type': 'application/problem+json' },
    })));

    await expect(defaultTemplateEditorAssetTransport.listAssets({ kind: 'IMAGE' }))
      .rejects.toMatchObject({
        name: 'TemplateAssetRequestError',
        status: 503,
        code: 'ASSET_DEPENDENCY_UNAVAILABLE',
        detail: 'Try again later.',
      });
  });
});

function catalogEntry(
  overrides: Partial<AssetCatalogEntry> = {},
): AssetCatalogEntry {
  return {
    assetId: IMAGE_ID,
    kind: 'IMAGE',
    lifecycle: 'ACTIVE',
    displayName: '商品主图',
    tags: ['catalog'],
    sourceFileName: 'product.webp',
    updatedAt: '2026-09-03T00:00:00Z',
    ...overrides,
  };
}

function readableAsset(
  overrides: Partial<AssetReadableResponse> = {},
): AssetReadableResponse {
  return {
    assetId: IMAGE_ID,
    disclosure: 'READABLE',
    kind: 'IMAGE',
    lifecycle: 'ACTIVE',
    assetRevision: 2,
    currentContentVersion: 1,
    displayName: '商品主图',
    tags: ['catalog'],
    sourceFileName: 'product.webp',
    mediaType: 'image/webp',
    byteLength: 128,
    sha256: '0'.repeat(64),
    descriptor: {
      encodedWidthPx: 800,
      encodedHeightPx: 600,
      orientation: 'IDENTITY',
      logicalWidthPx: 800,
      logicalHeightPx: 600,
      frameCount: 1,
      colorEncoding: 'SRGB_8BIT',
    },
    createdAt: '2026-09-02T00:00:00Z',
    updatedAt: '2026-09-03T00:00:00Z',
    ...overrides,
  };
}

function assetTransport(
  overrides: Partial<TemplateEditorAssetTransport> = {},
): TemplateEditorAssetTransport {
  return {
    listAssets: vi.fn().mockResolvedValue({ items: [] }),
    getCurrent: vi.fn().mockRejectedValue(new Error('unexpected Asset detail request')),
    previewCurrent: vi.fn().mockRejectedValue(new Error('unexpected Asset preview request')),
    ...overrides,
  };
}
