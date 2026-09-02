// @vitest-environment happy-dom

import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { useRef, useState } from 'react';

import type { AssetCatalogEntry, AssetCatalogResponse } from '../../api/generated';
import {
  TemplateAssetRequestError,
  type TemplateEditorAssetTransport,
} from './template-editor-assets';
import { TemplateEditorAssetPicker } from './TemplateEditorAssetPicker';

const IMAGE_ID = '00000000-0000-4000-8000-000000000201';
const OTHER_IMAGE_ID = '00000000-0000-4000-8000-000000000202';
const FONT_ID = '00000000-0000-4000-8000-000000000203';
const DELETED_ID = '00000000-0000-4000-8000-000000000204';

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe('TemplateEditorAssetPicker', () => {
  it('shows only ACTIVE Assets of the expected kind and never selects one implicitly', async () => {
    const onSelect = vi.fn();
    const transport = assetTransport({
      listAssets: vi.fn().mockResolvedValue({
        items: [
          catalogEntry({ assetId: IMAGE_ID, displayName: '商品主图' }),
          catalogEntry({ assetId: FONT_ID, kind: 'FONT', displayName: '品牌字体' }),
          catalogEntry({ assetId: DELETED_ID, lifecycle: 'DELETED', displayName: '已删除图片' }),
        ],
      }),
    });

    render(<TemplateEditorAssetPicker
      open
      expectedKind="IMAGE"
      transport={transport}
      onOpenChange={vi.fn()}
      onSelect={onSelect}
    />);

    expect(onSelect).not.toHaveBeenCalled();
    expect(await screen.findByRole('button', { name: /商品主图/ })).toBeTruthy();
    expect(screen.queryByRole('button', { name: /品牌字体/ })).toBeNull();
    expect(screen.queryByRole('button', { name: /已删除图片/ })).toBeNull();
    expect(onSelect).not.toHaveBeenCalled();
  });

  it('returns an exact AssetRef only after an explicit choice and requests close', async () => {
    const onSelect = vi.fn();
    const onOpenChange = vi.fn();
    const asset = catalogEntry({ assetId: IMAGE_ID, displayName: '商品主图' });
    const transport = assetTransport({
      listAssets: vi.fn().mockResolvedValue({ items: [asset] }),
    });

    render(<TemplateEditorAssetPicker
      open
      expectedKind="IMAGE"
      transport={transport}
      onOpenChange={onOpenChange}
      onSelect={onSelect}
    />);
    fireEvent.click(await screen.findByRole('button', { name: /商品主图/ }));

    expect(onSelect).toHaveBeenCalledWith({ ref: { assetId: IMAGE_ID }, asset });
    expect(Object.keys(onSelect.mock.calls[0]?.[0].ref ?? {})).toEqual(['assetId']);
    expect(onOpenChange).toHaveBeenCalledWith(false);
  });

  it('marks the current ref without auto-confirming it', async () => {
    const onSelect = vi.fn();
    const transport = assetTransport({
      listAssets: vi.fn().mockResolvedValue({ items: [
        catalogEntry({ assetId: IMAGE_ID, displayName: '当前图片' }),
        catalogEntry({ assetId: OTHER_IMAGE_ID, displayName: '其他图片' }),
      ] }),
    });

    render(<TemplateEditorAssetPicker
      open
      expectedKind="IMAGE"
      selectedAssetId={IMAGE_ID}
      transport={transport}
      onOpenChange={vi.fn()}
      onSelect={onSelect}
    />);

    const current = await screen.findByRole('button', { name: /当前图片/ });
    expect(current.getAttribute('aria-pressed')).toBe('true');
    expect(screen.getByRole('button', { name: /其他图片/ }).getAttribute('aria-pressed')).toBe('false');
    expect(onSelect).not.toHaveBeenCalled();
  });

  it('announces a visible request error and supports an explicit retry', async () => {
    const listAssets = vi.fn()
      .mockRejectedValueOnce(new TemplateAssetRequestError(
        503,
        'ASSET_DEPENDENCY_UNAVAILABLE',
      ))
      .mockResolvedValueOnce({ items: [catalogEntry({ displayName: '重试后的图片' })] });
    const transport = assetTransport({ listAssets });

    render(<TemplateEditorAssetPicker
      open
      expectedKind="IMAGE"
      transport={transport}
      onOpenChange={vi.fn()}
      onSelect={vi.fn()}
    />);

    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toContain('Asset 服务暂不可用');
    fireEvent.click(screen.getByRole('button', { name: '重试' }));

    expect(await screen.findByRole('button', { name: /重试后的图片/ })).toBeTruthy();
    expect(listAssets).toHaveBeenCalledTimes(2);
  });

  it('closes with Escape and restores focus to the opener', async () => {
    const transport = assetTransport({
      listAssets: vi.fn().mockResolvedValue({ items: [] }),
    });
    render(<AssetPickerHarness transport={transport} />);

    const opener = screen.getByRole('button', { name: '打开 Asset 选择器' });
    opener.focus();
    fireEvent.click(opener);
    expect(await screen.findByRole('dialog')).toBeTruthy();
    expect(screen.getByRole('button', { name: '关闭 Asset 选择器' })).toBe(document.activeElement);
    fireEvent.keyDown(document.activeElement ?? document, { key: 'Escape' });

    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    await waitFor(() => expect(opener).toBe(document.activeElement));
  });

  it('offers a visible cancel action without committing a selection', async () => {
    const onOpenChange = vi.fn();
    const onSelect = vi.fn();
    render(<TemplateEditorAssetPicker
      open
      expectedKind="FONT"
      transport={assetTransport()}
      onOpenChange={onOpenChange}
      onSelect={onSelect}
    />);

    fireEvent.click(screen.getByRole('button', { name: '取消' }));

    expect(onOpenChange).toHaveBeenCalledWith(false);
    expect(onSelect).not.toHaveBeenCalled();
  });

  it('aborts a pending catalog request when the dialog closes', async () => {
    let observedSignal: AbortSignal | undefined;
    const transport = assetTransport({
      listAssets: vi.fn((_query, signal) => {
        observedSignal = signal;
        return new Promise<AssetCatalogResponse>(() => undefined);
      }),
    });
    const { rerender } = render(<TemplateEditorAssetPicker
      open
      expectedKind="IMAGE"
      transport={transport}
      onOpenChange={vi.fn()}
      onSelect={vi.fn()}
    />);
    await waitFor(() => expect(observedSignal).toBeDefined());
    expect((await screen.findByRole('status')).textContent).toContain('正在读取');

    rerender(<TemplateEditorAssetPicker
      open={false}
      expectedKind="IMAGE"
      transport={transport}
      onOpenChange={vi.fn()}
      onSelect={vi.fn()}
    />);

    expect(observedSignal?.aborted).toBe(true);
  });
});

function AssetPickerHarness({ transport }: { transport: TemplateEditorAssetTransport }) {
  const [open, setOpen] = useState(false);
  const openerRef = useRef<HTMLButtonElement>(null);
  return (
    <>
      <button ref={openerRef} type="button" onClick={() => setOpen(true)}>
        打开 Asset 选择器
      </button>
      <TemplateEditorAssetPicker
        open={open}
        expectedKind="IMAGE"
        transport={transport}
        onOpenChange={setOpen}
        onSelect={vi.fn()}
      />
    </>
  );
}

function catalogEntry(
  overrides: Partial<AssetCatalogEntry> = {},
): AssetCatalogEntry {
  return {
    assetId: IMAGE_ID,
    kind: 'IMAGE',
    lifecycle: 'ACTIVE',
    displayName: '商品主图',
    tags: [],
    sourceFileName: 'product.webp',
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
