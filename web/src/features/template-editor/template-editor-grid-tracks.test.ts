import { isLosslessNumber } from 'lossless-json';
import { describe, expect, it } from 'vitest';

import {
  formatTemplateGridTracks,
  parseTemplateGridTracks,
} from './template-editor-grid-tracks';

describe('Template Editor Grid track text adapter', () => {
  it('parses the compact authoring syntax into ordered formal tracks and formats it back', () => {
    const parsed = parseTemplateGridTracks('12, auto, *, 1*, 2*');

    expect(parsed.state).toBe('parsed');
    if (parsed.state !== 'parsed') throw new Error(parsed.message);
    expect(parsed.tracks).toEqual([
      { type: 'FIXED', valueMm: expect.anything() },
      { type: 'AUTO' },
      { type: 'FRACTION', weight: expect.anything() },
      { type: 'FRACTION', weight: expect.anything() },
      { type: 'FRACTION', weight: expect.anything() },
    ]);
    expect(isLosslessNumber(parsed.tracks[0]?.type === 'FIXED'
      ? parsed.tracks[0].valueMm : null)).toBe(true);
    expect(formatTemplateGridTracks(parsed.tracks)).toBe('12, auto, 1*, 1*, 2*');
  });

  it('preserves a precise authored decimal without storing the draft string as a track', () => {
    const parsed = parseTemplateGridTracks('0.123456789012345678*');

    expect(parsed.state).toBe('parsed');
    if (parsed.state !== 'parsed') throw new Error(parsed.message);
    expect(formatTemplateGridTracks(parsed.tracks)).toBe('0.123456789012345678*');
    expect(parsed.tracks[0]).not.toEqual(expect.any(String));
  });

  it.each([
    '',
    '12,,auto',
    '0',
    '-1',
    '0*',
    'minmax(1, 2)',
    `${Array.from({ length: 65 }, () => 'auto').join(',')}`,
  ])('rejects an invalid or over-capacity track list: %s', (draft) => {
    expect(parseTemplateGridTracks(draft)).toEqual(expect.objectContaining({ state: 'invalid' }));
  });
});
