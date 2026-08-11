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
    VISUAL_ELEMENTS_CONTRACT_INVALID: '图片元素盘点未通过结构合同',
    VISUAL_HIERARCHY_CONTRACT_INVALID: '图片层级关系未通过结构合同',
    VISUAL_BINDINGS_CONTRACT_INVALID: '元素归属计划未通过结构合同',
    VISUAL_GROUNDING_JSON_ENUM_INVALID_REGION_KIND: '区域类型不在受控枚举内',
    VISUAL_GROUNDING_JSON_ENUM_INVALID_REGION_MULTIPLICITY: '区域基数不在受控枚举内',
    VISUAL_GROUNDING_JSON_ENUM_INVALID_ELEMENT_KIND: '元素类型不在受控枚举内',
    VISUAL_GROUNDING_JSON_ENUM_INVALID_ELEMENT_MULTIPLICITY: '元素基数不在受控枚举内',
    VISUAL_GROUNDING_JSON_ENUM_INVALID_ELEMENT_VALUE_HINT: '字段值提示不在受控枚举内',
    VISUAL_GROUNDING_READING_ORDER_GAP: '同级区域阅读顺序不连续',
    VISUAL_GROUNDING_READING_ORDER_POSITION_INVALID: '区域阅读顺序与空间位置不一致',
    VISUAL_GROUNDING_PARENT_KIND_INVALID: '区域父子类型关系无效',
    VISUAL_GROUNDING_PARENT_CONTAINMENT_INVALID: '子区域超出父区域范围',
    VISUAL_GROUNDING_NON_REPEATED_CARDINALITY_INVALID: '非重复区域必须使用单值基数',
    VISUAL_GROUNDING_REPEAT_CHILD_INVALID: '重复区域缺少匹配的 item 子区域',
    VISUAL_GROUNDING_REPEAT_ITEM_INVALID: '重复 item 的分组标识或基数无效',
    VISUAL_GROUNDING_ELEMENT_REGION_COVERAGE_INVALID: '部分视觉元素缺少区域归属',
    VISUAL_GROUNDING_ELEMENT_REGION_UNKNOWN: '视觉元素引用了未知区域',
    VISUAL_GROUNDING_ELEMENT_EVIDENCE_OUTSIDE_REGION: '元素证据不在所属区域内',
    VISUAL_GROUNDING_ELEMENT_OWNERSHIP_INVALID: '视觉元素区域归属无效',
    VISUAL_GROUNDING_ELEMENT_REGION_NORMALIZED: '已按唯一最具体证据区域归一化元素归属',
    VISUAL_GROUNDING_REGION_KIND_NORMALIZED: '已按受控别名或唯一结构事实归一化区域类型',
    VISUAL_GROUNDING_REPEATED_ITEM_SLOT_OWNER_NORMALIZED: '已按唯一可见 ITEM 证据归一化重复字段归属',
    VISUAL_GROUNDING_REGION_PARENT_NORMALIZED: '已按唯一最具体既有容器归一化区域父级',
    VISUAL_SEMANTIC_REPEATED_GROUP_ELEMENT_MISSING: '重复区域缺少可复用 GROUP 元素',
    VISUAL_SEMANTIC_REPEATED_GROUP_CARDINALITY_INVALID: 'MANY GROUP 与重复区域的双向归属不一致',
    VISUAL_SEMANTIC_REPEATED_ITEM_FIELD_MISSING: '重复 item 中缺少可见字段',
    VISUAL_SEMANTIC_GROUP_REGION_INVALID: 'GROUP 元素未归属到容器区域',
    VISUAL_SEMANTIC_OBSERVE_RELATIONSHIP_GROUP_MISSING: '层级关系缺少上游可验证的 GROUP 观察',
    VISUAL_SEMANTIC_OBSERVE_RELATIONSHIP_REGION_GROUP_MISSING: '层级关系区域缺少对应的 GROUP 元素 owner',
    VISUAL_SEMANTIC_SLOT_EVIDENCE_CONTAINS_ELEMENT: '字段证据包住了其他元素，应恢复为叶子字段或 GROUP 容器',
    VISUAL_HIERARCHY_V2_ENTITY_REGION_IDS_INVALID: '实体区域归属列表格式无效',
    VISUAL_HIERARCHY_V2_RELATIONSHIP_REGION_ID_INVALID: '层级关系区域标识格式无效',
    VISUAL_HIERARCHY_V2_REGION_REFERENCE_UNKNOWN: '层级计划引用了未知区域',
    VISUAL_HIERARCHY_V2_ROOT_REGION_OWNERSHIP_INVALID: '根实体未覆盖全部根区域',
    VISUAL_HIERARCHY_V2_RELATIONSHIP_REGION_CONNECTION_INVALID: '层级关系区域未连接父子实体区域',
    VISUAL_HIERARCHY_V2_RELATIONSHIP_REGION_CARDINALITY_INVALID: '层级关系区域与证据基数不一致',
    VISUAL_HIERARCHY_V2_RELATIONSHIP_SUPPORT_IDS_INVALID: '层级关系支撑 ID 列表无效',
    VISUAL_HIERARCHY_V2_RELATIONSHIP_SUPPORT_IDS_MISSING: '层级关系缺少支撑 ID 列表',
    VISUAL_HIERARCHY_V2_RELATIONSHIP_SUPPORT_IDS_EMPTY: '层级关系支撑 ID 列表不能为空',
    VISUAL_HIERARCHY_V2_RELATIONSHIP_SUPPORT_IDS_LIMIT_EXCEEDED: '层级关系支撑 ID 数量超限',
    VISUAL_HIERARCHY_V2_RELATIONSHIP_SUPPORT_ID_INVALID: '层级关系支撑 ID 格式无效',
    VISUAL_HIERARCHY_V2_RELATIONSHIP_SUPPORT_COUNT_INVALID: '层级关系必须恰有一个支撑 GROUP',
    VISUAL_HIERARCHY_RELATIONSHIP_SUPPORT_IDS_NORMALIZED: '已移除层级关系支撑 ID 的精确重复引用',
    VISUAL_HIERARCHY_RELATIONSHIP_SUPPORT_OWNER_NORMALIZED: '已按唯一容器区域 GROUP 归属归一化层级关系支撑',
    VISUAL_HIERARCHY_RELATIONSHIP_ENCLOSING_SUPPORT_OWNER_NORMALIZED: '已按唯一包围且连通的 GROUP 证据归一化层级关系支撑',
    VISUAL_HIERARCHY_RELATIONSHIP_SOURCE_ANCESTOR_SUPPORT_OWNER_NORMALIZED: '已按关系源区域唯一且连通的祖先 GROUP 证据归一化层级关系支撑',
    VISUAL_HIERARCHY_RELATIONSHIP_EMPTY_SUPPORT_OWNER_NORMALIZED: '已按关系区域唯一且连通的 GROUP 归属补全层级关系支撑',
    VISUAL_HIERARCHY_RELATIONSHIP_EMPTY_SOURCE_ANCESTOR_SUPPORT_OWNER_NORMALIZED: '已按关系子区域唯一且连通的祖先 GROUP 归属补全层级关系支撑',
    VISUAL_HIERARCHY_RELATIONSHIP_UNKNOWN_SUPPORT_OWNER_NORMALIZED: '已将未知层级关系支撑引用归一化为关系区域唯一且连通的 GROUP 归属',
    VISUAL_HIERARCHY_RELATIONSHIP_REGION_NORMALIZED: '已按唯一 GROUP 证据归一化层级关系区域',
    VISUAL_HIERARCHY_V2_SUPPORT_NOT_GROUP: '层级关系必须由 GROUP 元素支撑',
    VISUAL_HIERARCHY_V2_SUPPORT_GROUP_REUSED: '同一 GROUP 元素不能支撑多条层级关系',
    VISUAL_SEMANTIC_HIERARCHY_GROUP_EDGE_MISSING: 'GROUP 元素未形成层级关系',
    VISUAL_SEMANTIC_HIERARCHY_GROUP_EDGE_COUNT_INVALID: 'GROUP 元素对应了多个层级关系',
    VISUAL_SEMANTIC_HIERARCHY_EDGE_GROUP_COUNT_INVALID: '层级关系没有且仅有一个 GROUP 支撑',
    VISUAL_SEMANTIC_HIERARCHY_EDGE_REGION_INVALID: '层级关系与 GROUP 区域不一致',
    VISUAL_SEMANTIC_HIERARCHY_ENTITY_REGION_REDUNDANT: '同一实体不能同时拥有祖先区域和后代区域',
    VISUAL_SEMANTIC_HIERARCHY_NON_ROOT_OWNS_ROOT_REGION: '非根实体不能拥有图片根区域',
    VISUAL_SEMANTIC_HIERARCHY_BINDING_OWNER_AMBIGUOUS: '字段存在多个同等最小的空间实体 owner',
    VISUAL_HIERARCHY_RELATIONSHIP_CARDINALITY_DERIVED: '已按唯一 GROUP 证据确定层级关系基数',
    VISUAL_SEMANTIC_BINDING_NOT_NEAREST_ENTITY: '字段未绑定到最近的空间实体',
    VISUAL_PLAN_ROOT_SCHEMA_MISMATCH: '根数据结构与视觉层级计划不一致',
    VISUAL_PLAN_SCHEMA_MISSING: '视觉计划中的数据结构缺失',
    VISUAL_PLAN_SCHEMA_EVIDENCE_MISSING: '数据结构缺少计划中的直接证据',
    VISUAL_PLAN_SCHEMA_UNEXPECTED: '生成了视觉计划外的数据结构',
    VISUAL_PLAN_RELATION_MISSING: '视觉计划中的嵌套关系缺失',
    VISUAL_PLAN_RELATION_SHAPE_INVALID: '嵌套关系的引用或数组基数不正确',
    VISUAL_PLAN_RELATION_EVIDENCE_MISSING: '嵌套关系缺少计划中的直接证据',
    VISUAL_PLAN_FIELD_MISSING: '视觉计划中的字段缺失',
    VISUAL_PLAN_FIELD_SHAPE_INVALID: '字段类型或数组基数与视觉计划不一致',
    VISUAL_PLAN_FIELD_EVIDENCE_MISSING: '字段缺少计划中的直接证据',
    VISUAL_PLAN_FIELD_UNEXPECTED: '生成了视觉计划外的字段',
  };
  return labels[code] ?? '需要审核的确定性问题';
}
