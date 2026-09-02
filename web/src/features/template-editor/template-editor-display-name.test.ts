import { describe, expect, it } from 'vitest';

import {
  normalizeTemplateEditorDisplayName,
  TEMPLATE_EDITOR_DISPLAY_NAME_MAX_CODE_POINTS,
} from './template-editor-display-name';

describe('Template Editor display-name contract', () => {
  it('matches Java String.trim without trimming non-breaking spaces', () => {
    expect(normalizeTemplateEditorDisplayName('\u0000\t  门店 \r\n')).toEqual({
      state: 'valid',
      value: '门店',
    });
    expect(normalizeTemplateEditorDisplayName('\u00a0门店\u00a0')).toEqual({
      state: 'valid',
      value: '\u00a0门店\u00a0',
    });
    expect(normalizeTemplateEditorDisplayName('门\u0000店')).toEqual({
      state: 'valid',
      value: '门\u0000店',
    });
  });

  it('reports required and too-long values as distinct structured failures', () => {
    expect(normalizeTemplateEditorDisplayName('\u0000\t\r\n ')).toEqual({
      state: 'invalid',
      value: null,
      reason: 'DISPLAY_NAME_REQUIRED',
    });
    expect(normalizeTemplateEditorDisplayName(
      '😀'.repeat(TEMPLATE_EDITOR_DISPLAY_NAME_MAX_CODE_POINTS + 1),
    )).toEqual({
      state: 'invalid',
      value: null,
      reason: 'DISPLAY_NAME_TOO_LONG',
    });
  });

  it('counts Unicode code points and rejects every lone-surrogate shape', () => {
    expect(normalizeTemplateEditorDisplayName(
      '😀'.repeat(TEMPLATE_EDITOR_DISPLAY_NAME_MAX_CODE_POINTS),
    )).toEqual({
      state: 'valid',
      value: '😀'.repeat(TEMPLATE_EDITOR_DISPLAY_NAME_MAX_CODE_POINTS),
    });

    for (const value of ['\ud800', '\udc00', 'A\ud800B', '\ud800\ud800', '\udc00\udc00']) {
      expect(normalizeTemplateEditorDisplayName(value)).toEqual({
        state: 'invalid',
        value: null,
        reason: 'DISPLAY_NAME_INVALID_UNICODE',
      });
    }
  });

  it('preserves authored scalar sequences without Unicode normalization', () => {
    expect(normalizeTemplateEditorDisplayName(' e\u0301 ')).toEqual({
      state: 'valid',
      value: 'e\u0301',
    });
  });
});
