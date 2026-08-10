import { lazy, type ComponentType } from 'react';

const reloadMarkerKey = 'renderweave:route-chunk-reload';
const chunkFailurePatterns = [
  /failed to fetch dynamically imported module/i,
  /error loading dynamically imported module/i,
  /importing a module script failed/i,
  /unable to preload css/i,
  /load failed for module/i,
];

export function lazyRoute(loader: () => Promise<{ default: ComponentType }>) {
  return lazy(async () => {
    const entryUrl = currentEntryUrl();
    const route = currentRoute();
    try {
      const module = await loader();
      clearChunkReload(entryUrl, route, window.sessionStorage);
      return module;
    } catch (error) {
      if (isChunkLoadError(error) && claimChunkReload(entryUrl, route, window.sessionStorage)) {
        window.location.reload();
        return new Promise<never>(() => undefined);
      }
      throw error;
    }
  });
}

export function isChunkLoadError(error: unknown) {
  const message = error instanceof Error ? error.message : typeof error === 'string' ? error : '';
  return chunkFailurePatterns.some((pattern) => pattern.test(message));
}

export function claimChunkReload(entryUrl: string, route: string, storage: Pick<Storage, 'getItem' | 'setItem'>) {
  const marker = `${entryUrl}|${route}`;
  try {
    if (storage.getItem(reloadMarkerKey) === marker) return false;
    storage.setItem(reloadMarkerKey, marker);
    return true;
  } catch {
    return false;
  }
}

export function clearChunkReload(entryUrl: string, route: string, storage: Pick<Storage, 'getItem' | 'removeItem'>) {
  try {
    if (storage.getItem(reloadMarkerKey) === `${entryUrl}|${route}`) storage.removeItem(reloadMarkerKey);
  } catch {
    // Storage can be unavailable in hardened browser contexts; the recovery page remains usable.
  }
}

function currentEntryUrl() {
  const scripts = Array.from(document.querySelectorAll<HTMLScriptElement>('script[type="module"][src]'));
  return scripts.find((script) => !script.src.includes('/@vite/client'))?.src ?? 'unknown-entry';
}

function currentRoute() {
  return `${window.location.pathname}${window.location.search}`;
}
