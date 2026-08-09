// @vitest-environment happy-dom

import { afterEach, describe, expect, it, vi } from 'vitest';

import { createLiveRunRequest } from './candidate-api';

afterEach(() => vi.unstubAllGlobals());

describe('live inference multipart request', () => {
  it('never sends retained files from an inactive input mode', async () => {
    const bodies: FormData[] = [];
    vi.stubGlobal('fetch', vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
      bodies.push(init?.body as FormData);
      return {
        ok: true,
        json: async () => ({ runId: '44444444-4444-4444-8444-444444444444' }),
      } as Response;
    }));
    const image = new File([new Uint8Array([1])], 'poster.png', { type: 'image/png' });
    const json = new File([new Uint8Array([123, 125])], 'sample.json', { type: 'application/json' });

    await createLiveRunRequest('dashscope-qwen37-flash-v1', 'JSON_ONLY', [image], [json], 'json-only');
    await createLiveRunRequest('dashscope-qwen37-flash-v1', 'IMAGE_ONLY', [image], [json], 'image-only');

    expect(bodies[0]?.getAll('images')).toHaveLength(0);
    expect(bodies[0]?.getAll('jsonSamples')).toHaveLength(1);
    expect(bodies[1]?.getAll('images')).toHaveLength(1);
    expect(bodies[1]?.getAll('jsonSamples')).toHaveLength(0);
  });
});
