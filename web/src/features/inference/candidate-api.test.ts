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
        text: async () => JSON.stringify({ runId: '44444444-4444-4444-8444-444444444444' }),
      } as Response;
    }));
    const image = new File([new Uint8Array([1])], 'poster.png', { type: 'image/png' });
    const json = new File([new Uint8Array([123, 125])], 'sample.json', { type: 'application/json' });

    await createLiveRunRequest(
      'dashscope-qwen37-plus-product-v41-hybrid-generic', 'JSON_ONLY', [image], [json], 'json-only', 250_000,
    );
    await createLiveRunRequest(
      'dashscope-qwen37-plus-product-v41-hybrid-generic', 'IMAGE_ONLY', [image], [json], 'image-only', null,
    );

    expect(bodies[0]?.getAll('images')).toHaveLength(0);
    expect(bodies[0]?.getAll('jsonSamples')).toHaveLength(1);
    expect(bodies[1]?.getAll('images')).toHaveLength(1);
    expect(bodies[1]?.getAll('jsonSamples')).toHaveLength(0);
    const firstMetadata = bodies[0]?.get('metadata') as Blob;
    const secondMetadata = bodies[1]?.get('metadata') as Blob;
    await expect(firstMetadata.text()).resolves.toContain('"costLimitMicrosCny":250000');
    await expect(firstMetadata.text()).resolves.toContain('"inputClassification":"USER_PROVIDED"');
    await expect(secondMetadata.text()).resolves.toContain('"costLimitMicrosCny":null');
  });

  it('turns a non-JSON gateway 413 into a stable readable upload problem', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => ({
      ok: false,
      status: 413,
      statusText: 'Request Entity Too Large',
      text: async () => '<html><h1>413 Request Entity Too Large</h1></html>',
    } as Response)));
    const image = new File([new Uint8Array([1])], 'poster.png', { type: 'image/png' });

    await expect(createLiveRunRequest(
      'dashscope-qwen37-plus-product-v41-hybrid-generic', 'IMAGE_ONLY', [image], [], 'too-large', null,
    )).rejects.toMatchObject({
      name: 'StudioRequestError',
      problem: {
        status: 413,
        code: 'INFERENCE_PAYLOAD_TOO_LARGE',
        title: '上传内容过大',
      },
    });
  });
});
