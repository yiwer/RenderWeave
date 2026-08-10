import type { CandidateSchema, CandidateValue } from '../../api/generated';

export const candidateTypeLabels: Record<CandidateValue['kind'], string> = {
  TEXT: '文本',
  DECIMAL: '数值',
  DATE: '日期',
  TIME: '时间',
  BOOLEAN: '布尔',
  REFERENCE: '引用',
  ARRAY: '数组',
  UNRESOLVED: '待判定',
  CONFLICT: '类型冲突',
};

export const resolutionLabels = {
  NOT_REQUIRED: '无需确认',
  UNRESOLVED: '待确认',
  CONFIRMED: '已确认',
  RESOLVED_BY_EDIT: '编辑解决',
  REMOVED: '已移除',
} as const;

export function summarizeValue(value: CandidateValue, schemas: CandidateSchema[]): string {
  if (value.kind === 'ARRAY') return `Array<${value.items ? candidateTypeLabels[value.items.kind] : '待判定'}>${value.items ? ` · ${summarizeValue(value.items, schemas)}` : ''}`;
  if (value.kind !== 'REFERENCE') {
    const constraintCount = Object.keys(value.constraints).length;
    return constraintCount > 0 ? `${constraintCount} 个约束` : '无约束';
  }
  const reference = value.reference;
  if (!reference?.kind) return '引用目标待填写';
  if (reference.kind === 'CANDIDATE_SCHEMA') {
    const target = schemas.find((item) => item.candidateSchemaId === reference.candidateSchemaId);
    return `CandidateRef · ${target?.proposedSchemaKey || '目标待选择'}`;
  }
  if (reference.kind === 'STATIC') return `StaticSchemaRef · ${reference.schemaKey || '?'}@${reference.versionTag || '?'}`;
  return `SchemaRef · ${reference.schemaKey || '目标待填写'}`;
}

export function problemLabel(code: string) {
  const labels: Record<string, string> = {
    LOW_CONFIDENCE_UNRESOLVED: '低置信度项需要逐项决定',
    LOW_CONFIDENCE_STATE_INVALID: '低置信度断言不能标记为无需确认',
    CANDIDATE_ITEM_UNRESOLVED: '候选项仍需逐项确认',
    CANDIDATE_TYPE_UNRESOLVED: '字段类型尚未判定',
    CANDIDATE_TYPE_CONFLICT: '多个样本的字段类型冲突',
    AI_REQUIRED_UNCONFIRMED: 'AI 推断的必填关系尚未确认',
    AI_CONSTRAINT_UNCONFIRMED: 'AI 推断约束尚未确认',
    CANDIDATE_SCHEMA_KEY_UNRESOLVED: 'schemaKey 尚未填写',
    CANDIDATE_FIELD_KEY_UNRESOLVED: 'fieldKey 尚未填写',
    CANDIDATE_SCHEMA_KEY_INVALID: 'schemaKey 不符合规则',
    CANDIDATE_SCHEMA_KEY_NORMALIZED: '已生成安全的候选 schemaKey',
    IMAGE_EVIDENCE_PIXEL_COORDINATES_NORMALIZED: '已将像素证据框换算为规范坐标',
    CANDIDATE_FIELD_KEY_INVALID: 'fieldKey 不符合规则',
    CANDIDATE_SCALAR_SHAPE_INVALID: '标量类型包含互斥的结构成员',
    CANDIDATE_SCALAR_OBSERVED_KINDS_NORMALIZED: '已移除标量类型中的冗余观察值',
    INFERENCE_SOURCE_INVALID: '模型不能创建用户来源项',
    INFERENCE_PROVENANCE_INVALID: '模型来源标记不可信',
    INFERENCE_RESOLUTION_INVALID: '模型不能代替用户确认或移除',
    JSON_EVIDENCE_LOCATION_UNKNOWN: 'JSON 证据位置不存在',
    JSON_EVIDENCE_ITEM_MISMATCH: 'JSON 证据与当前字段不匹配',
    JSON_EVIDENCE_ITEM_MISSING: 'JSON 派生项缺少对应的 JSON 证据',
    CANDIDATE_REFERENCE_TARGET_MISSING: '引用目标不在有效 Candidate 包中',
    CANDIDATE_SCHEMA_ORPHAN: 'Schema 无法从 Root 到达',
    CANDIDATE_REFERENCE_CYCLE: 'Candidate 引用形成循环',
    NESTED_ARRAY_UNSUPPORTED: '不支持嵌套数组',
  };
  return labels[code] ?? '需要审核的确定性问题';
}
