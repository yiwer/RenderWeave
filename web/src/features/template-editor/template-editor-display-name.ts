export const TEMPLATE_EDITOR_DISPLAY_NAME_MAX_CODE_POINTS = 128;

export type TemplateEditorDisplayNameInvalidReason =
  | 'DISPLAY_NAME_REQUIRED'
  | 'DISPLAY_NAME_TOO_LONG'
  | 'DISPLAY_NAME_INVALID_UNICODE';

export type TemplateEditorDisplayNameNormalization =
  | { readonly state: 'valid'; readonly value: string }
  | {
    readonly state: 'invalid';
    readonly value: null;
    readonly reason: TemplateEditorDisplayNameInvalidReason;
  };

/**
 * Applies the DesignDSL display-name contract shared by Template and node names.
 * Java String.trim removes only leading/trailing UTF-16 code units <= U+0020;
 * the authored value must then contain 1..128 Unicode scalar values.
 */
export function normalizeTemplateEditorDisplayName(
  rawValue: string,
): TemplateEditorDisplayNameNormalization {
  const value = javaStringTrim(rawValue);
  if (!hasOnlyUnicodeScalars(value)) {
    return { state: 'invalid', value: null, reason: 'DISPLAY_NAME_INVALID_UNICODE' };
  }
  const codePointLength = Array.from(value).length;
  if (codePointLength === 0) {
    return { state: 'invalid', value: null, reason: 'DISPLAY_NAME_REQUIRED' };
  }
  if (codePointLength > TEMPLATE_EDITOR_DISPLAY_NAME_MAX_CODE_POINTS) {
    return { state: 'invalid', value: null, reason: 'DISPLAY_NAME_TOO_LONG' };
  }
  return { state: 'valid', value };
}

function javaStringTrim(value: string): string {
  let start = 0;
  let end = value.length;
  while (start < end && value.charCodeAt(start) <= 0x20) start += 1;
  while (end > start && value.charCodeAt(end - 1) <= 0x20) end -= 1;
  return value.slice(start, end);
}

function hasOnlyUnicodeScalars(value: string): boolean {
  for (let index = 0; index < value.length; index += 1) {
    const current = value.charCodeAt(index);
    if (current >= 0xd800 && current <= 0xdbff) {
      if (index + 1 >= value.length) return false;
      const next = value.charCodeAt(index + 1);
      if (next < 0xdc00 || next > 0xdfff) return false;
      index += 1;
    } else if (current >= 0xdc00 && current <= 0xdfff) {
      return false;
    }
  }
  return true;
}
