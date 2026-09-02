import {
  getAssetCurrent as getAssetCurrentRequest,
  listAssets as listAssetsRequest,
  previewAssetCurrent as previewAssetCurrentRequest,
  type AssetCatalogEntry,
  type AssetCatalogResponse,
  type AssetKind,
  type AssetReadableResponse,
  type DesignAssetRef,
  type Problem,
} from '../../api/generated';

const ASSET_CATALOG_PAGE_SIZE = 100;
const MAX_ASSET_CATALOG_PAGES = 1_000;

export type TemplateEditorAssetKind = AssetKind;

export interface TemplateEditorAssetCatalogQuery {
  kind?: TemplateEditorAssetKind;
  includeDeleted?: boolean;
  cursor?: string;
  limit?: number;
}

export interface TemplateEditorAssetTransport {
  listAssets(
    query: TemplateEditorAssetCatalogQuery,
    signal?: AbortSignal,
  ): Promise<AssetCatalogResponse>;
  getCurrent(assetId: string, signal?: AbortSignal): Promise<AssetReadableResponse>;
  previewCurrent(assetId: string, signal?: AbortSignal): Promise<Blob>;
}

export class TemplateAssetRequestError extends Error {
  readonly status: number;
  readonly code: string;
  readonly detail?: string;

  constructor(status: number, code: string, detail?: string) {
    super(detail?.trim() || `Asset request failed (${code})`);
    this.name = 'TemplateAssetRequestError';
    this.status = status;
    this.code = code;
    this.detail = detail;
  }
}

export class TemplateAssetIntegrityError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'TemplateAssetIntegrityError';
  }
}

export const defaultTemplateEditorAssetTransport: TemplateEditorAssetTransport = {
  async listAssets(query, signal) {
    const result = await listAssetsRequest({
      query: {
        ...query,
        limit: query.limit ?? ASSET_CATALOG_PAGE_SIZE,
      },
      signal,
    });
    return unwrapAssetResponse(result.data, result.error, result.response?.status);
  },

  async getCurrent(assetId, signal) {
    const result = await getAssetCurrentRequest({ path: { assetId }, signal });
    return unwrapAssetResponse(result.data, result.error, result.response?.status);
  },

  async previewCurrent(assetId, signal) {
    const result = await previewAssetCurrentRequest({ path: { assetId }, signal });
    const bytes = unwrapAssetResponse(result.data, result.error, result.response?.status);
    if (!(bytes instanceof Blob)) {
      throw new TemplateAssetIntegrityError('Asset preview response is not binary content');
    }
    return bytes;
  },
};

export type TemplateAssetResolution =
  | {
      state: 'active';
      ref: DesignAssetRef;
      expectedKind: TemplateEditorAssetKind;
      asset: AssetReadableResponse;
    }
  | {
      state: 'missing';
      ref: DesignAssetRef;
      expectedKind: TemplateEditorAssetKind;
    }
  | {
      state: 'deleted';
      ref: DesignAssetRef;
      expectedKind: TemplateEditorAssetKind;
      asset?: AssetReadableResponse;
    }
  | {
      state: 'kind-mismatch';
      ref: DesignAssetRef;
      expectedKind: TemplateEditorAssetKind;
      actualKind: TemplateEditorAssetKind;
      asset: AssetReadableResponse;
    }
  | {
      state: 'unavailable';
      ref: DesignAssetRef;
      expectedKind: TemplateEditorAssetKind;
      code: string;
    };

export interface TemplateAssetSelection {
  ref: DesignAssetRef;
  asset: AssetCatalogEntry;
}

export function assetRefFromCatalogEntry(asset: AssetCatalogEntry): DesignAssetRef {
  return { assetId: asset.assetId };
}

export async function listActiveTemplateAssets(
  kind: TemplateEditorAssetKind,
  transport: TemplateEditorAssetTransport = defaultTemplateEditorAssetTransport,
  signal?: AbortSignal,
): Promise<AssetCatalogEntry[]> {
  const assets: AssetCatalogEntry[] = [];
  const seenCursors = new Set<string>();
  const seenAssetIds = new Set<string>();
  let cursor: string | undefined;

  for (let pageIndex = 0; pageIndex < MAX_ASSET_CATALOG_PAGES; pageIndex += 1) {
    const page = await transport.listAssets(
      {
        kind,
        includeDeleted: false,
        ...(cursor === undefined ? {} : { cursor }),
        limit: ASSET_CATALOG_PAGE_SIZE,
      },
      signal,
    );

    for (const asset of page.items) {
      if (asset.kind !== kind || asset.lifecycle !== 'ACTIVE') continue;
      if (seenAssetIds.has(asset.assetId)) continue;
      seenAssetIds.add(asset.assetId);
      assets.push(asset);
    }

    if (page.nextCursor === undefined || page.nextCursor === null) return assets;
    if (!page.nextCursor || seenCursors.has(page.nextCursor)) {
      throw new TemplateAssetIntegrityError('Asset catalog returned a repeated cursor');
    }
    seenCursors.add(page.nextCursor);
    cursor = page.nextCursor;
  }

  throw new TemplateAssetIntegrityError('Asset catalog exceeded the bounded page count');
}

export async function resolveTemplateAssetRef(
  ref: DesignAssetRef,
  expectedKind: TemplateEditorAssetKind,
  transport: TemplateEditorAssetTransport = defaultTemplateEditorAssetTransport,
  signal?: AbortSignal,
): Promise<TemplateAssetResolution> {
  try {
    const asset = await transport.getCurrent(ref.assetId, signal);
    if (asset.assetId !== ref.assetId) {
      return { state: 'unavailable', ref, expectedKind, code: 'ASSET_IDENTITY_MISMATCH' };
    }
    if (asset.lifecycle === 'DELETED') {
      return { state: 'deleted', ref, expectedKind, asset };
    }
    if (asset.kind !== expectedKind) {
      return {
        state: 'kind-mismatch',
        ref,
        expectedKind,
        actualKind: asset.kind,
        asset,
      };
    }
    return { state: 'active', ref, expectedKind, asset };
  } catch (error) {
    if (isAbortError(error)) throw error;
    if (error instanceof TemplateAssetRequestError) {
      if (error.status === 404) return { state: 'missing', ref, expectedKind };
      if (error.status === 410) return { state: 'deleted', ref, expectedKind };
      return { state: 'unavailable', ref, expectedKind, code: error.code };
    }
    return { state: 'unavailable', ref, expectedKind, code: 'ASSET_REQUEST_UNAVAILABLE' };
  }
}

function unwrapAssetResponse<T>(
  data: T | undefined,
  error: unknown,
  status: number | undefined,
): T {
  if (error !== undefined) {
    if (isAbortError(error)) throw error;
    const problem = asProblem(error);
    throw new TemplateAssetRequestError(
      status ?? problem?.status ?? 0,
      problem?.code ?? 'ASSET_REQUEST_UNAVAILABLE',
      problem?.detail ?? problem?.title,
    );
  }
  if (data === undefined) {
    throw new TemplateAssetIntegrityError('Asset response did not include data');
  }
  return data;
}

function asProblem(value: unknown): Problem | undefined {
  if (typeof value !== 'object' || value === null) return undefined;
  const candidate = value as Partial<Problem>;
  if (
    typeof candidate.status !== 'number'
    || typeof candidate.code !== 'string'
    || typeof candidate.title !== 'string'
  ) return undefined;
  return candidate as Problem;
}

function isAbortError(error: unknown): boolean {
  return error instanceof DOMException && error.name === 'AbortError';
}
