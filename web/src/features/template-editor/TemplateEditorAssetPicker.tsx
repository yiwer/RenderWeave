import * as Dialog from '@radix-ui/react-dialog';
import { Image as ImageIcon, LoaderCircle, RefreshCw, Type, X } from 'lucide-react';
import { useEffect, useId, useRef, useState } from 'react';

import type { AssetCatalogEntry } from '../../api/generated';
import {
  assetRefFromCatalogEntry,
  defaultTemplateEditorAssetTransport,
  listActiveTemplateAssets,
  TemplateAssetRequestError,
  type TemplateAssetSelection,
  type TemplateEditorAssetKind,
  type TemplateEditorAssetTransport,
} from './template-editor-assets';
import styles from './TemplateEditorAssetPicker.module.css';

interface AssetPickerIdleState {
  state: 'idle';
}

interface AssetPickerLoadingState {
  state: 'loading';
}

interface AssetPickerReadyState {
  state: 'ready';
  assets: AssetCatalogEntry[];
}

interface AssetPickerErrorState {
  state: 'error';
  message: string;
}

type AssetPickerLoadState =
  | AssetPickerIdleState
  | AssetPickerLoadingState
  | AssetPickerReadyState
  | AssetPickerErrorState;

export interface TemplateEditorAssetPickerProps {
  open: boolean;
  expectedKind: TemplateEditorAssetKind;
  selectedAssetId?: string;
  transport?: TemplateEditorAssetTransport;
  onOpenChange(open: boolean): void;
  onSelect(selection: TemplateAssetSelection): void;
}

export function TemplateEditorAssetPicker({
  open,
  expectedKind,
  selectedAssetId,
  transport = defaultTemplateEditorAssetTransport,
  onOpenChange,
  onSelect,
}: TemplateEditorAssetPickerProps) {
  const [loadState, setLoadState] = useState<AssetPickerLoadState>({ state: 'idle' });
  const [retryGeneration, setRetryGeneration] = useState(0);
  const closeButtonRef = useRef<HTMLButtonElement>(null);
  const returnFocusRef = useRef<HTMLElement | null>(null);
  const descriptionId = useId();
  const kindName = expectedKind === 'IMAGE' ? '图片' : '字体';

  useEffect(() => {
    if (!open) return;

    const controller = new AbortController();
    queueMicrotask(() => {
      if (!controller.signal.aborted) setLoadState({ state: 'loading' });
    });
    void listActiveTemplateAssets(expectedKind, transport, controller.signal).then(
      (assets) => {
        if (!controller.signal.aborted) setLoadState({ state: 'ready', assets });
      },
      (error: unknown) => {
        if (controller.signal.aborted) return;
        setLoadState({ state: 'error', message: assetPickerErrorMessage(error) });
      },
    );
    return () => controller.abort();
  }, [expectedKind, open, retryGeneration, transport]);

  const choose = (asset: AssetCatalogEntry) => {
    onSelect({ ref: assetRefFromCatalogEntry(asset), asset });
    onOpenChange(false);
  };

  return (
    <Dialog.Root open={open} onOpenChange={onOpenChange}>
      <Dialog.Portal>
        <Dialog.Overlay className="dialog-overlay" />
        <Dialog.Content
          className={`dialog-content ${styles.content}`}
          aria-describedby={descriptionId}
          onOpenAutoFocus={(event) => {
            const activeElement = document.activeElement;
            const dialogElement = closeButtonRef.current?.closest('[role="dialog"]');
            if (
              activeElement instanceof HTMLElement
              && activeElement !== document.body
              && !(dialogElement?.contains(activeElement) ?? false)
            ) {
              returnFocusRef.current = activeElement;
            }
            event.preventDefault();
            closeButtonRef.current?.focus();
          }}
          onCloseAutoFocus={(event) => {
            const returnTarget = returnFocusRef.current;
            if (!returnTarget?.isConnected) return;
            event.preventDefault();
            returnTarget.focus({ preventScroll: true });
            returnFocusRef.current = null;
          }}
        >
          <div className={styles.heading}>
            <div>
              <Dialog.Title>选择{kindName} Asset</Dialog.Title>
              <Dialog.Description id={descriptionId}>
                仅列出当前可用且类型匹配的 Asset；选择后只保存 AssetRef。
              </Dialog.Description>
            </div>
            <Dialog.Close asChild>
              <button
                ref={closeButtonRef}
                type="button"
                className={styles.closeButton}
                aria-label="关闭 Asset 选择器"
              >
                <X aria-hidden="true" size={18} />
              </button>
            </Dialog.Close>
          </div>

          <AssetPickerBody
            loadState={loadState}
            kind={expectedKind}
            selectedAssetId={selectedAssetId}
            onChoose={choose}
            onRetry={() => setRetryGeneration((value) => value + 1)}
          />

          <div className="dialog-actions">
            <Dialog.Close asChild>
              <button type="button" className="button ghost-button">取消</button>
            </Dialog.Close>
          </div>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}

function AssetPickerBody({
  loadState,
  kind,
  selectedAssetId,
  onChoose,
  onRetry,
}: {
  loadState: AssetPickerLoadState;
  kind: TemplateEditorAssetKind;
  selectedAssetId?: string;
  onChoose(asset: AssetCatalogEntry): void;
  onRetry(): void;
}) {
  if (loadState.state === 'idle') return null;
  if (loadState.state === 'loading') {
    return (
      <div className={styles.state} role="status" aria-live="polite">
        <LoaderCircle className="spin" aria-hidden="true" size={18} />
        正在读取 Asset 目录…
      </div>
    );
  }
  if (loadState.state === 'error') {
    return (
      <div className={`${styles.state} ${styles.error}`} role="alert">
        <strong>无法读取 Asset 目录</strong>
        <span>{loadState.message}</span>
        <button type="button" className="button ghost-button" onClick={onRetry}>
          <RefreshCw aria-hidden="true" size={15} />
          重试
        </button>
      </div>
    );
  }
  if (loadState.assets.length === 0) {
    return (
      <div className={styles.state} role="status">
        当前没有可选择的{kind === 'IMAGE' ? '图片' : '字体'} Asset。
      </div>
    );
  }
  return (
    <ul className={styles.assetList} aria-label={`可用${kind === 'IMAGE' ? '图片' : '字体'} Asset`}>
      {loadState.assets.map((asset) => (
        <li key={asset.assetId}>
          <button
            type="button"
            className={styles.assetButton}
            aria-pressed={asset.assetId === selectedAssetId}
            onClick={() => onChoose(asset)}
          >
            <span className={styles.assetIcon} aria-hidden="true">
              {kind === 'IMAGE' ? <ImageIcon size={18} /> : <Type size={18} />}
            </span>
            <span className={styles.assetCopy}>
              <strong>{asset.displayName}</strong>
              <span>{asset.sourceFileName ?? '未记录源文件名'}</span>
              <code>{asset.assetId}</code>
            </span>
            {asset.assetId === selectedAssetId
              ? <span className={styles.current}>当前</span>
              : <span className={styles.chooseLabel}>选择</span>}
          </button>
        </li>
      ))}
    </ul>
  );
}

function assetPickerErrorMessage(error: unknown): string {
  if (error instanceof TemplateAssetRequestError) {
    if (error.status === 403) return '当前身份没有读取 Asset 的权限。';
    if (error.status === 503 || error.status === 0) return 'Asset 服务暂不可用，请稍后重试。';
    return error.detail?.trim() || `Asset 请求失败（${error.code}）。`;
  }
  return 'Asset 服务暂不可用，请稍后重试。';
}
