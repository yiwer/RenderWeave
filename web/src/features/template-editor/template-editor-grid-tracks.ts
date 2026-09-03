import { isLosslessNumber, LosslessNumber } from 'lossless-json';

import { canonicalTemplateDecimal } from './template-canonical-decimal';
import { positiveTemplateNumber, templateNumberDraft } from './template-editor-numbers';

export const TEMPLATE_GRID_TRACK_LIMIT = 64;

export type TemplateGridTrack =
  | Readonly<{ type: 'FIXED'; valueMm: number | LosslessNumber }>
  | Readonly<{ type: 'FRACTION'; weight: number | LosslessNumber }>
  | Readonly<{ type: 'AUTO' }>;

export type ParseTemplateGridTracksResult =
  | Readonly<{ state: 'parsed'; tracks: readonly TemplateGridTrack[] }>
  | Readonly<{ state: 'invalid'; message: string }>;

/**
 * Converts the editor's compact comma-separated syntax into formal ordered
 * DesignDSL tracks. Draft strings never enter the authored document.
 */
export function parseTemplateGridTracks(draft: string): ParseTemplateGridTracksResult {
  const source = draft.trim();
  if (source === '') return invalid('Grid 轨道不能为空。');
  const tokens = source.split(',').map((token) => token.trim());
  if (tokens.length > TEMPLATE_GRID_TRACK_LIMIT) {
    return invalid(`Grid 轨道最多 ${TEMPLATE_GRID_TRACK_LIMIT} 条。`);
  }

  const tracks: TemplateGridTrack[] = [];
  for (const token of tokens) {
    if (token === '') return invalid('Grid 轨道项不能为空。');
    if (token.toLowerCase() === 'auto') {
      tracks.push(Object.freeze({ type: 'AUTO' }));
      continue;
    }
    const fraction = token.endsWith('*');
    const numberToken = fraction ? token.slice(0, -1).trim() || '1' : token;
    const value = positiveDecimal(numberToken);
    if (!value) return invalid(`Grid 轨道“${token}”无效。`);
    tracks.push(Object.freeze(fraction
      ? { type: 'FRACTION', weight: value }
      : { type: 'FIXED', valueMm: value }));
  }
  return Object.freeze({ state: 'parsed', tracks: Object.freeze(tracks) });
}

/** Formats formal ordered tracks for the compact authoring input. */
export function formatTemplateGridTracks(tracks: readonly TemplateGridTrack[]): string {
  if (!isTemplateGridTrackList(tracks)) {
    throw new TypeError('Grid tracks must be a non-empty closed formal track list.');
  }
  return tracks.map((track) => {
    if (track.type === 'AUTO') return 'auto';
    const value = track.type === 'FIXED' ? track.valueMm : track.weight;
    const token = templateNumberDraft(value);
    return track.type === 'FIXED' ? token : `${token}*`;
  }).join(', ');
}

export function isTemplateGridTrackList(value: unknown): value is readonly TemplateGridTrack[] {
  return Array.isArray(value)
    && value.length > 0
    && value.length <= TEMPLATE_GRID_TRACK_LIMIT
    && value.every(isTemplateGridTrack);
}

function isTemplateGridTrack(value: unknown): value is TemplateGridTrack {
  if (!isRecord(value) || typeof value.type !== 'string') return false;
  if (value.type === 'AUTO') return exactKeys(value, 'type');
  if (value.type === 'FIXED') {
    return exactKeys(value, 'type', 'valueMm') && positiveTemplateNumber(value.valueMm) !== null;
  }
  return value.type === 'FRACTION'
    && exactKeys(value, 'type', 'weight')
    && positiveTemplateNumber(value.weight) !== null;
}

function positiveDecimal(token: string): LosslessNumber | null {
  const projected = Number(token);
  if (!Number.isFinite(projected) || projected <= 0) return null;
  try {
    const value = new LosslessNumber(canonicalTemplateDecimal(token));
    return positiveTemplateNumber(value) === null ? null : value;
  } catch {
    return null;
  }
}

function exactKeys(value: Record<string, unknown>, ...keys: string[]): boolean {
  const actual = Object.keys(value);
  return actual.length === keys.length && keys.every((key) => Object.hasOwn(value, key));
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
    && !isLosslessNumber(value);
}

function invalid(message: string): ParseTemplateGridTracksResult {
  return Object.freeze({ state: 'invalid', message });
}
