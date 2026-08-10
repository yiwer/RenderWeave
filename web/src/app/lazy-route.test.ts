// @vitest-environment happy-dom

import { beforeEach, describe, expect, it } from 'vitest';

import { claimChunkReload, clearChunkReload, isChunkLoadError } from './lazy-route';

describe('lazy route deployment recovery', () => {
  beforeEach(() => window.sessionStorage.clear());

  it('recognizes browser dynamic-import failures without treating ordinary errors as stale chunks', () => {
    expect(isChunkLoadError(new TypeError('Failed to fetch dynamically imported module: /assets/page-old.js'))).toBe(true);
    expect(isChunkLoadError(new Error('Importing a module script failed.'))).toBe(true);
    expect(isChunkLoadError(new Error('Candidate request returned 422'))).toBe(false);
  });

  it('permits one reload per entry and route, then clears the marker after a successful load', () => {
    expect(claimChunkReload('/assets/index-old.js', '/validator', window.sessionStorage)).toBe(true);
    expect(claimChunkReload('/assets/index-old.js', '/validator', window.sessionStorage)).toBe(false);

    clearChunkReload('/assets/index-old.js', '/validator', window.sessionStorage);
    expect(claimChunkReload('/assets/index-old.js', '/validator', window.sessionStorage)).toBe(true);
    expect(claimChunkReload('/assets/index-new.js', '/validator', window.sessionStorage)).toBe(true);
  });
});
