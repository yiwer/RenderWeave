// @vitest-environment happy-dom

import { describe, expect, it } from 'vitest';

import { filesForLiveMode, formatFileSize, mergeLiveFiles, validateLiveFiles } from './live-input';

describe('live inference local file checks', () => {
  it('rejects unsupported and over-limit files before any request is created', () => {
    const files = [
      file('poster.gif', 10, 'image/gif'),
      file('large.png', 10 * 1024 * 1024 + 1, 'image/png'),
    ];
    expect(validateLiveFiles('IMAGE', files).map((issue) => issue.code)).toEqual([
      'TYPE_UNSUPPORTED', 'FILE_TOO_LARGE',
    ]);
  });

  it('reports count and aggregate limits without silently truncating the queue', () => {
    const images = Array.from({ length: 11 }, (_, index) => file(`image-${index}.png`, 3 * 1024 * 1024, 'image/png', index));
    expect(validateLiveFiles('IMAGE', images).map((issue) => issue.code)).toEqual([
      'COUNT_EXCEEDED', 'TOTAL_TOO_LARGE',
    ]);
  });

  it('appends unique selections and keeps exact file sizes human-readable', () => {
    const first = file('sample.json', 1536, 'application/json', 1);
    const duplicate = file('sample.json', 1536, 'application/json', 1);
    const second = file('sample-2.json', 256 * 1024, 'application/json', 2);
    expect(mergeLiveFiles([first], [duplicate, second])).toEqual([first, second]);
    expect(formatFileSize(first.size)).toBe('1.5 KiB');
    expect(formatFileSize(second.size)).toBe('256 KiB');
  });

  it('matches the server media-type boundary', () => {
    expect(validateLiveFiles('IMAGE', [file('poster.png', 10, '')]).map((issue) => issue.code)).toEqual(['TYPE_UNSUPPORTED']);
    expect(validateLiveFiles('JSON', [file('sample.json', 10, 'text/json')]).map((issue) => issue.code)).toEqual(['TYPE_UNSUPPORTED']);
    expect(validateLiveFiles('JSON', [file('sample.json', 10, '')])).toEqual([]);
  });

  it('excludes retained files that do not belong to the selected mode', () => {
    const image = file('poster.png', 10, 'image/png');
    const json = file('sample.json', 10, 'application/json');

    expect(filesForLiveMode('JSON_ONLY', [image], [json])).toEqual({ images: [], jsonSamples: [json] });
    expect(filesForLiveMode('IMAGE_ONLY', [image], [json])).toEqual({ images: [image], jsonSamples: [] });
    expect(filesForLiveMode('COMBINED', [image], [json])).toEqual({ images: [image], jsonSamples: [json] });
  });
});

function file(name: string, size: number, type: string, lastModified = 0) {
  return new File([new Uint8Array(size)], name, { type, lastModified });
}
