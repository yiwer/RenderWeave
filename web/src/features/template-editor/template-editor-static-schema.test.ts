import { afterEach, describe, expect, it, vi } from 'vitest';

import type { StaticSnapshot } from '../schema-studio/lossless-api';
import {
  loadTemplateStaticSchemaBranches,
  loadTemplateStaticSchemaClosure,
  loadTemplateStaticSchema,
  projectPendingTemplateStaticSchemaReferences,
  TemplateStaticSchemaIntegrityError,
} from './template-editor-static-schema';

describe('Template editor StaticSchema boundary', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('loads the permanent baseline identity exactly and preserves lossless decimal constraints', async () => {
    const huge = '123456789012345678901234567890123456789012345678901234567890123456789';
    const fetchMock = vi.fn().mockResolvedValue(new Response(`{
      "schemaKey":"price/card","versionTag":"release 7","origin":"SYSTEM",
      "sourceDraftRevision":null,
      "definition":{"dslVersion":"renderweave-schema/1.0","displayName":"价签","fields":[
        {"fieldKey":"amount","required":true,"value":{"type":"decimal","constraints":{"const":${huge}}}}
      ]},
      "compilerVersion":"schema-1","releaseNote":null,"referenceDepth":0,
      "publishedAt":"2026-09-03T00:00:00Z"
    }`, {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    }));
    vi.stubGlobal('fetch', fetchMock);
    const signal = new AbortController().signal;

    const snapshot = await loadTemplateStaticSchema({
      baseline: {
        staticSchema: { schemaKey: 'price/card', versionTag: 'release 7' },
      },
    }, undefined, signal);

    expect(fetchMock).toHaveBeenCalledOnce();
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/static-schemas/price%2Fcard/release%207',
      expect.objectContaining({ signal }),
    );
    expect(snapshot.definition.fields[0]?.value).toEqual({
      type: 'decimal',
      constraints: { const: huge },
    });
  });

  it('fails closed when the response identity differs from the permanent baseline identity', async () => {
    const signal = new AbortController().signal;
    const transport = {
      getStaticSchema: vi.fn().mockResolvedValue(snapshot({
        schemaKey: 'other-schema',
        versionTag: 'v7',
      })),
    };

    await expect(loadTemplateStaticSchema({
      baseline: {
        staticSchema: { schemaKey: 'price-card', versionTag: 'v7' },
      },
    }, transport, signal)).rejects.toEqual(expect.objectContaining({
      name: TemplateStaticSchemaIntegrityError.name,
      message: 'StaticSchema response identity does not match the permanent Template baseline',
    }));
    expect(transport.getStaticSchema).toHaveBeenCalledWith(
      { schemaKey: 'price-card', versionTag: 'v7' },
      signal,
    );
  });

  it('fails closed before transport when the permanent baseline reference is malformed', async () => {
    const transport = { getStaticSchema: vi.fn() };

    await expect(loadTemplateStaticSchema({
      baseline: {
        staticSchema: { schemaKey: 'price-card', versionTag: '   ' },
      },
    }, transport)).rejects.toEqual(expect.objectContaining({
      name: 'TemplateStaticSchemaIntegrityError',
      message: 'Permanent Template StaticSchema identity is malformed',
    }));
    expect(transport.getStaticSchema).not.toHaveBeenCalled();
  });

  it('fails closed when an immutable reference field lacks an exact version tag', async () => {
    const malformed = snapshot({ schemaKey: 'price-card', versionTag: 'v7' });
    malformed.definition.fields.push({
      fieldKey: 'product',
      required: true,
      value: { type: 'reference', ref: { schemaKey: 'catalog-item' } },
    });

    await expect(loadTemplateStaticSchema({
      baseline: {
        staticSchema: { schemaKey: 'price-card', versionTag: 'v7' },
      },
    }, {
      getStaticSchema: vi.fn().mockResolvedValue(malformed),
    })).rejects.toEqual(expect.objectContaining({
      name: 'TemplateStaticSchemaIntegrityError',
      message: 'StaticSchema reference at /fields/0/value is not an exact immutable identity',
    }));
  });

  it('loads the exact transitive reference closure once in deterministic breadth-first order', async () => {
    const root = snapshot({ schemaKey: 'price-card', versionTag: 'v7' });
    root.definition.fields.push(
      {
        fieldKey: 'featured', required: true,
        value: { type: 'reference', ref: { schemaKey: 'catalog-item', versionTag: 'v3' } },
      },
      {
        fieldKey: 'items', required: true,
        value: {
          type: 'array',
          items: { type: 'reference', ref: { schemaKey: 'catalog-item', versionTag: 'v3' } },
        },
      },
    );
    const item = snapshot({ schemaKey: 'catalog-item', versionTag: 'v3' });
    item.definition.fields.push({
      fieldKey: 'meta', required: true,
      value: { type: 'reference', ref: { schemaKey: 'catalog-meta', versionTag: 'v1' } },
    });
    const meta = snapshot({ schemaKey: 'catalog-meta', versionTag: 'v1' });
    meta.definition.fields.push({
      fieldKey: 'owner', required: false,
      value: { type: 'reference', ref: { schemaKey: 'price-card', versionTag: 'v7' } },
    });
    const transport = {
      getStaticSchema: vi.fn(async (identity: { schemaKey: string }) => {
        if (identity.schemaKey === 'catalog-item') return item;
        if (identity.schemaKey === 'catalog-meta') return meta;
        throw new Error('unexpected identity');
      }),
    };

    const closure = await loadTemplateStaticSchemaClosure(root, transport);

    expect(closure.map(({ schemaKey, versionTag }) => `${schemaKey}@${versionTag}`)).toEqual([
      'price-card@v7', 'catalog-item@v3', 'catalog-meta@v1',
    ]);
    expect(transport.getStaticSchema).toHaveBeenCalledTimes(2);
    expect(transport.getStaticSchema).toHaveBeenNthCalledWith(
      1, { schemaKey: 'catalog-item', versionTag: 'v3' }, undefined,
    );
    expect(transport.getStaticSchema).toHaveBeenNthCalledWith(
      2, { schemaKey: 'catalog-meta', versionTag: 'v1' }, undefined,
    );
  });

  it('loads only exact reference branches traversed by selected context pointers', async () => {
    const root = snapshot({ schemaKey: 'price-card', versionTag: 'v7' });
    root.definition.fields.push(
      { fieldKey: 'title', required: true, value: { type: 'text' } },
      {
        fieldKey: 'brand', required: true,
        value: { type: 'reference', ref: { schemaKey: 'brand', versionTag: 'v2' } },
      },
      {
        fieldKey: 'unused', required: true,
        value: { type: 'reference', ref: { schemaKey: 'broken', versionTag: 'v1' } },
      },
    );
    const brand = snapshot({ schemaKey: 'brand', versionTag: 'v2' });
    brand.definition.fields.push(
      { fieldKey: 'name', required: true, value: { type: 'text' } },
      {
        fieldKey: 'unusedMeta', required: true,
        value: { type: 'reference', ref: { schemaKey: 'broken-meta', versionTag: 'v1' } },
      },
    );
    const transport = {
      getStaticSchema: vi.fn(async (identity: { schemaKey: string }) => {
        if (identity.schemaKey === 'brand') return brand;
        throw new Error(`unrelated reference loaded: ${identity.schemaKey}`);
      }),
    };
    const signal = new AbortController().signal;

    const closure = await loadTemplateStaticSchemaBranches(
      root,
      ['/title', '/brand/name'],
      transport,
      signal,
    );

    expect(closure.map(({ schemaKey, versionTag }) => `${schemaKey}@${versionTag}`)).toEqual([
      'price-card@v7', 'brand@v2',
    ]);
    expect(transport.getStaticSchema).toHaveBeenCalledOnce();
    expect(transport.getStaticSchema).toHaveBeenCalledWith(
      { schemaKey: 'brand', versionTag: 'v2' },
      signal,
    );
  });

  it('checks root reference integrity before selectively loading a branch', async () => {
    const root = snapshot({ schemaKey: 'price-card', versionTag: 'v7' });
    root.definition.fields.push(
      { fieldKey: 'title', required: true, value: { type: 'text' } },
      {
        fieldKey: 'malformed', required: true,
        value: { type: 'reference', ref: { schemaKey: 'broken' } },
      },
    );
    const transport = { getStaticSchema: vi.fn() };

    await expect(loadTemplateStaticSchemaBranches(
      root,
      ['/title'],
      transport,
    )).rejects.toEqual(expect.objectContaining({
      name: 'TemplateStaticSchemaIntegrityError',
      message: 'StaticSchema reference at /fields/1/value is not an exact immutable identity',
    }));
    expect(transport.getStaticSchema).not.toHaveBeenCalled();
  });

  it('does not admit a selectively loaded snapshot after its signal is aborted', async () => {
    const root = snapshot({ schemaKey: 'price-card', versionTag: 'v7' });
    root.definition.fields.push({
      fieldKey: 'brand', required: true,
      value: { type: 'reference', ref: { schemaKey: 'brand', versionTag: 'v2' } },
    });
    const brand = snapshot({ schemaKey: 'brand', versionTag: 'v2' });
    brand.definition.fields.push({ fieldKey: 'name', required: true, value: { type: 'text' } });
    let resolve!: (value: StaticSnapshot) => void;
    const transport = {
      getStaticSchema: vi.fn(() => new Promise<StaticSnapshot>((done) => { resolve = done; })),
    };
    const controller = new AbortController();

    const loading = loadTemplateStaticSchemaBranches(
      root,
      ['/brand/name'],
      transport,
      controller.signal,
    );
    controller.abort(new Error('cancelled'));
    resolve(brand);

    await expect(loading).rejects.toThrow('cancelled');
  });

  it('traverses an Array<StaticSchema> item only when a selected loop branch uses it', async () => {
    const root = snapshot({ schemaKey: 'price-card', versionTag: 'v7' });
    root.definition.fields.push({
      fieldKey: 'products', required: true,
      value: {
        type: 'array',
        items: { type: 'reference', ref: { schemaKey: 'product', versionTag: 'v3' } },
      },
    });
    const product = snapshot({ schemaKey: 'product', versionTag: 'v3' });
    product.definition.fields.push({
      fieldKey: 'price', required: true, value: { type: 'decimal' },
    });
    const transport = { getStaticSchema: vi.fn().mockResolvedValue(product) };

    const closure = await loadTemplateStaticSchemaBranches(
      root,
      ['/products/price'],
      transport,
    );

    expect(closure.map(({ schemaKey }) => schemaKey)).toEqual(['price-card', 'product']);
    expect(transport.getStaticSchema).toHaveBeenCalledOnce();
  });

  it('projects only the next unloaded exact identities with duplicate and cycle safety', () => {
    const root = snapshot({ schemaKey: 'price-card', versionTag: 'v7' });
    root.definition.fields.push(
      {
        fieldKey: 'featured', displayName: '主商品', required: true,
        value: { type: 'reference', ref: { schemaKey: 'catalog-item', versionTag: 'v3' } },
      },
      {
        fieldKey: 'backup', displayName: '备用商品', required: true,
        value: { type: 'reference', ref: { schemaKey: 'catalog-item', versionTag: 'v3' } },
      },
    );
    const item = snapshot({ schemaKey: 'catalog-item', versionTag: 'v3' });
    item.definition.fields.push(
      {
        fieldKey: 'root', required: true,
        value: { type: 'reference', ref: { schemaKey: 'price-card', versionTag: 'v7' } },
      },
      {
        fieldKey: 'meta', displayName: '元数据', required: true,
        value: { type: 'reference', ref: { schemaKey: 'catalog-meta', versionTag: 'v1' } },
      },
      {
        fieldKey: 'otherMeta', required: true,
        value: { type: 'reference', ref: { schemaKey: 'catalog-meta', versionTag: 'v1' } },
      },
    );
    const meta = snapshot({ schemaKey: 'catalog-meta', versionTag: 'v1' });
    meta.definition.fields.push({
      fieldKey: 'item', required: true,
      value: { type: 'reference', ref: { schemaKey: 'catalog-item', versionTag: 'v3' } },
    });

    expect(projectPendingTemplateStaticSchemaReferences([root])).toEqual([{
      identity: { schemaKey: 'catalog-item', versionTag: 'v3' },
      label: '主商品',
    }]);
    expect(projectPendingTemplateStaticSchemaReferences([root, item])).toEqual([{
      identity: { schemaKey: 'catalog-meta', versionTag: 'v1' },
      label: '元数据',
    }]);
    expect(projectPendingTemplateStaticSchemaReferences([root, item, meta])).toEqual([]);
  });
});

function snapshot(identity: { schemaKey: string; versionTag: string }): StaticSnapshot {
  return {
    ...identity,
    origin: 'SYSTEM',
    sourceDraftRevision: null,
    definition: {
      dslVersion: 'renderweave-schema/1.0',
      displayName: '价签',
      fields: [],
    },
    compilerVersion: 'schema-1',
    releaseNote: null,
    referenceDepth: 0,
    publishedAt: '2026-09-03T00:00:00Z',
  };
}
