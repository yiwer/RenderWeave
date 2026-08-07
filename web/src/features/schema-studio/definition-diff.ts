import { editorValueFromPersisted, summarizeEditorValue, type DraftSnapshot, type EditorField } from './editor-types';
import type { EditorSession } from './editor-session';

export interface DefinitionDiff {
  kind: 'added' | 'removed' | 'changed' | 'reordered';
  path: string;
  label: string;
  local: string;
  server: string;
}

export function diffDraftDefinitions(local: EditorSession, server: DraftSnapshot): DefinitionDiff[] {
  const diffs: DefinitionDiff[] = [];
  compare(diffs, '/definition/displayName', '显示名称', local.displayName.trim(), server.definition.displayName);
  compare(diffs, '/definition/description', '用途说明', local.description.trim(), server.definition.description ?? '');

  const localByKey = new Map(local.fields.map((field) => [field.fieldKey, field]));
  const serverFields: EditorField[] = server.definition.fields.map((field, index) => ({
    rowKey: `server-${index}`,
    fieldKey: field.fieldKey,
    displayName: field.displayName ?? '',
    description: field.description ?? '',
    required: field.required,
    value: editorValueFromPersisted(field.value),
  }));
  const serverByKey = new Map(serverFields.map((field) => [field.fieldKey, field]));

  for (const field of local.fields) {
    const serverField = serverByKey.get(field.fieldKey);
    if (!serverField) {
      diffs.push({ kind: 'added', path: `/definition/fields/${field.fieldKey}`, label: `新增字段 ${field.fieldKey}`, local: fieldSummary(field), server: '不存在' });
    } else if (fingerprint(field) !== fingerprint(serverField)) {
      diffs.push({ kind: 'changed', path: `/definition/fields/${field.fieldKey}`, label: `字段已变化 ${field.fieldKey}`, local: fieldSummary(field), server: fieldSummary(serverField) });
    }
  }
  for (const field of serverFields) {
    if (!localByKey.has(field.fieldKey)) {
      diffs.push({ kind: 'removed', path: `/definition/fields/${field.fieldKey}`, label: `本地已删除 ${field.fieldKey}`, local: '不存在', server: fieldSummary(field) });
    }
  }
  const localOrder = local.fields.map((field) => field.fieldKey).filter((key) => serverByKey.has(key));
  const serverOrder = serverFields.map((field) => field.fieldKey).filter((key) => localByKey.has(key));
  if (JSON.stringify(localOrder) !== JSON.stringify(serverOrder)) {
    diffs.push({ kind: 'reordered', path: '/definition/fields', label: '字段顺序已变化', local: localOrder.join(' → '), server: serverOrder.join(' → ') });
  }
  return diffs;
}

function compare(
  diffs: DefinitionDiff[],
  path: string,
  label: string,
  local: string,
  server: string,
) {
  if (local !== server) diffs.push({ kind: 'changed', path, label, local: local || '（空）', server: server || '（空）' });
}

function fingerprint(field: EditorField): string {
  return JSON.stringify({
    displayName: field.displayName.trim(),
    description: field.description.trim(),
    required: field.required,
    value: field.value,
  });
}

function fieldSummary(field: EditorField): string {
  return `${field.required ? '必填' : '可选'} · ${summarizeEditorValue(field.value)}${field.displayName.trim() ? ` · ${field.displayName.trim()}` : ''}`;
}
