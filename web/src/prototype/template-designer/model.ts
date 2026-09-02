/**
 * PROTOTYPE — throwaway. 三个在线 Template 设计器变体共享的内存模型。
 * 问题:作者能否通过组件库、结构动作与分组属性快速搭出并修改模板?
 * 数据来源:hbads-template-v2 源码、用户截图与 RenderWeave DesignDSL 既有约束。
 * 不进入产品路由以外的任何模块;状态全在内存,刷新即失。
 */

export type PrototypeVariant = 'A' | 'B' | 'C';

export type Scenario =
  | 'clean'
  | 'asset-deleted'
  | 'binding-absent'
  | 'child-fill-invalid'
  | 'conflict'
  | 'layout-error';

export type AuthoringTab = 'elements' | 'containers' | 'images' | 'sources' | 'structure';

/** Legacy prototype tabs stay in the union so the archived panels in SharedParts remain type-safe. */
export type LeftTab = AuthoringTab | 'library' | 'tree' | 'assets' | 'definitions' | 'data' | 'exchange';

export type CanvasTool = 'select' | 'pan';

export type CanvasLayoutMode = 'FREE' | 'STACK' | 'GRID';

export type TreeDropPlacement = 'before' | 'into' | 'after';

export type SavePhase = 'idle' | 'confirm-invalid' | 'saved' | 'rejected-hard' | 'rejected-conflict';

export type PreviewPhase = 'idle' | 'loading' | 'ok' | 'failed' | 'blocked' | 'cancelled';

export type TemplateStatus = 'READY' | 'INVALID' | 'STALE';

export interface ScenarioMeta {
  key: Scenario;
  label: string;
  hint: string;
}

export const scenarios: ScenarioMeta[] = [
  { key: 'clean', label: '干净', hint: '校验通过 · 可保存可权威预览' },
  { key: 'asset-deleted', label: 'Asset 已删除', hint: '依赖 ERROR · 二次确认可存 INVALID' },
  { key: 'binding-absent', label: 'Binding ABSENT', hint: '样本缺字段 · 权威预览失败' },
  { key: 'child-fill-invalid', label: '子模板 Fill 失效', hint: 'authored fill 指向失效 · 父模板依赖 ERROR' },
  { key: 'conflict', label: '版本冲突', hint: 'expectedRevision 落后 · 保存被拒' },
  { key: 'layout-error', label: '布局硬错误', hint: 'fillWeight 非法 · 不可确认零写' },
];

export interface DesignerProblem {
  code: string;
  severity: 'hard' | 'dependency' | 'runtime';
  message: string;
  pointer: string;
  nodeId?: string;
  bindingId?: string;
  definitionId?: string;
  span?: string;
}

export interface NodeBinding {
  id: string;
  ref: string;
  source: string;
  note: string;
}

export interface InspectorProp {
  label: string;
  value: string;
  bindable: boolean;
  binding?: NodeBinding;
  options?: string[];
}

export type NodeKind =
  | 'canvas' | 'group' | 'frame' | 'stack' | 'grid'
  | 'text' | 'image' | 'rect' | 'ellipse' | 'line'
  | 'polygon' | 'polyline' | 'path' | 'qrCode' | 'barcode'
  | 'repeat' | 'conditional' | 'templateUse';

export interface DesignerNode {
  id: string;
  loopId?: string;
  useId?: string;
  kind: NodeKind;
  name: string;
  detail: string;
  flags: string[];
  props: InspectorProp[];
  children: DesignerNode[];
}

export interface DesignerAsset {
  id: string;
  name: string;
  kind: 'IMAGE' | 'FONT';
  status: 'ACTIVE' | 'DELETED';
  detail: string;
  tags: string[];
  /** Browser-only typography projection for this in-memory FONT fixture. */
  previewFamily?: string;
  usedBy?: string;
  misuse?: string;
}

export type DesignerValueType =
  | 'text' | 'decimal' | 'boolean' | 'date' | 'time' | 'color' | 'imageRef' | 'fontRef'
  | `list<${'text' | 'decimal' | 'boolean' | 'date' | 'time' | 'imageRef' | 'fontRef'}>`;

/** Exact DesignDSL lexical-domain shape: Custom definitions never carry this member. */
export type DesignerDefinitionDomain = 'invocation' | { kind: 'loop'; loopId: string };

/** Browser prototype projection of the closed DesignDSL ValueSource union. */
export type DesignerValueSource =
  | { kind: 'literal'; valueType: DesignerValueType; value: string }
  | { kind: 'context'; domain: DesignerDefinitionDomain; pointer: string }
  | { kind: 'loopIndex'; loopId: string }
  | { kind: 'definition'; definitionId: string }
  | { kind: 'capability'; capability: 'CLOCK' | 'RANDOM'; operation: 'UTC_DATE' | 'UTC_TIME' | 'UNIFORM_DECIMAL_0_1' };

interface DesignerDefinitionBase {
  id: string;
  name: string;
  valueType: DesignerValueType;
  detail: string;
}

export interface DesignerCustomDefinition extends DesignerDefinitionBase {
  kind: 'CUSTOM';
  exposure: 'PUBLIC' | 'PRIVATE';
  defaultValue: string;
}

export interface DesignerExpressionDefinition extends DesignerDefinitionBase {
  kind: 'EXPRESSION';
  domain: DesignerDefinitionDomain;
  inputs: Array<{ alias: string; source: DesignerValueSource }>;
  source: string;
}

export interface DesignerMappingCase {
  id: string;
  operator: 'EQ' | 'PATTERN_MATCH';
  operand: string;
  then: string;
}

export interface DesignerMappingDefinition extends DesignerDefinitionBase {
  kind: 'MAPPING';
  domain: DesignerDefinitionDomain;
  output: DesignerValueType;
  input: DesignerValueSource;
  cases: DesignerMappingCase[];
  otherwise: string;
}

export type DesignerDefinition = DesignerCustomDefinition | DesignerExpressionDefinition | DesignerMappingDefinition;

export function definitionValueType(definition: DesignerDefinition): DesignerValueType {
  return definition.kind === 'MAPPING' ? definition.output : definition.valueType;
}

export function definitionDomain(definition: DesignerDefinition): DesignerDefinitionDomain {
  return definition.kind === 'CUSTOM' ? 'invocation' : definition.domain;
}

export function definitionDomainLabel(definition: DesignerDefinition): string {
  const domain = definitionDomain(definition);
  return domain === 'invocation' ? '模板范围' : `循环 · ${domain.loopId.slice(0, 8)}`;
}

export function valueSourceSummary(source: DesignerValueSource): string {
  switch (source.kind) {
    case 'literal': return `literal(${source.valueType})`;
    case 'context': return `context(${source.domain === 'invocation' ? 'invocation' : `loop:${source.domain.loopId.slice(0, 8)}`}, ${source.pointer})`;
    case 'loopIndex': return `loopIndex(${source.loopId.slice(0, 8)})`;
    case 'definition': return `definition(${source.definitionId})`;
    case 'capability': return `${source.capability}.${source.operation}`;
  }
}

export interface DraftBox {
  nodeId: string;
  x: number;
  y: number;
  w: number;
  h: number;
  tone: 'text' | 'frame' | 'image' | 'qr' | 'chip' | 'ghost';
  label: string;
}

export interface ElementCatalogEntry {
  adapter: boolean;
  description: string;
  group: 'basic' | 'domain';
  kind: Extract<NodeKind, 'text' | 'image' | 'rect' | 'ellipse' | 'line' | 'polyline' | 'path' | 'polygon' | 'qrCode' | 'barcode'>;
  label: string;
  searchTerms: string[];
  slotSummary: string;
}

export type ContainerPreset = 'group' | 'frame' | 'stack' | 'grid' | 'repeat' | 'conditional' | 'templateUse';

export type StackDemoPreset = 'vertical-start' | 'horizontal-center' | 'horizontal-between' | 'horizontal-fill';
export type GridDemoPreset = 'fraction-cards' | 'auto-span' | 'alignment-fill';
export type AbsoluteDemoPreset = 'group-hug' | 'frame-content';
export type RepeatDemoPreset = 'scalar-tags' | 'reference-offers' | 'repair-state';
export type RepeatPreviewSample = 'values' | 'empty' | 'absent';
export type RepeatPreviewMode = 'instances' | 'item';
export type ConditionalDemoPreset = 'condition-true' | 'condition-false' | 'condition-absent';
export type ConditionalPreviewSample = 'true' | 'false' | 'absent';

export interface ContainerCatalogEntry {
  description: string;
  kind: Extract<NodeKind, 'group' | 'frame' | 'stack' | 'grid' | 'repeat' | 'conditional' | 'templateUse'>;
  label: string;
  preset: ContainerPreset;
}

export interface NestedTemplateEntry {
  id: string;
  name: string;
  compatibilityKey: string;
  contextLabel: string;
  detail: string;
  lifecycle: 'ACTIVE' | 'DELETED';
  readiness: 'READY' | 'INVALID' | 'STALE';
  proposal: boolean;
}

export interface PrototypeTemplateUseContextSource {
  id: string;
  label: string;
  pointer: string;
  selector: string;
  valueType: 'reference' | 'text' | 'decimal' | 'boolean' | 'date' | 'time';
  typeLabel: '引用' | '文本' | '数值' | '布尔' | '日期' | '时间';
  presence: 'required' | 'optional';
  kind: 'REFERENCE' | 'SCALAR_PROPOSAL';
  compatibilityKey: string;
  contextLabel: string;
}

export interface PrototypeRepeatSource {
  id: string;
  sourceGroup: 'SYSTEM' | 'CUSTOM' | 'DERIVED' | 'LOOP';
  expression: string;
  path?: string;
  definitionId?: string;
  label: string;
  optional: boolean;
  sourceType: 'SCALAR_LIST' | 'REFERENCE_LIST';
  itemStaticSchemaRef: string;
  itemValueType?: 'text' | 'decimal' | 'boolean' | 'date' | 'time';
  sampleValues: Array<string | number | boolean | { name: string; price: string; badge: string }>;
}

export interface PrototypeRepeatTemplateCandidate {
  templateId: string;
  name: string;
  ownerScope: 'workspace:campaign';
  staticSchemaRef: string;
  lifecycle: 'ACTIVE' | 'DELETED';
  readiness: 'READY' | 'INVALID' | 'STALE';
}

export const templateMeta = {
  name: '活动价签 · 90×54mm',
  templateId: '7c9e2f41-9d3b-4c6a-8f2e-5a1b0c3d4e5f',
  schemaRef: 'campaign-card@v3',
  dslVersion: 'renderweave-design/1.0',
  expressionProfile: 'renderweave-expression/1.0',
  canvasMm: { width: 90, height: 54 },
};

export const nodeIds = {
  canvas: '0a000000-0000-4000-8000-000000000001',
  rootStack: '0a000000-0000-4000-8000-000000000002',
  titleText: '0a000000-0000-4000-8000-000000000003',
  priceBand: '0a000000-0000-4000-8000-000000000004',
  priceText: '0a000000-0000-4000-8000-000000000005',
  currencyNote: '0a000000-0000-4000-8000-000000000006',
  brandLogo: '0a000000-0000-4000-8000-000000000007',
  dateLine: '0a000000-0000-4000-8000-000000000008',
  tagLoop: '0a000000-0000-4000-8000-000000000009',
  tagChip: '0a000000-0000-4000-8000-00000000000a',
  tagTemplateUse: '0a000000-0000-4000-8000-00000000000b',
  linkCode: '0a000000-0000-4000-8000-00000000000c',
  brandBadge: '0a000000-0000-4000-8000-00000000000d',
  promoCorner: '0a000000-0000-4000-8000-00000000000e',
  promoBlock: '0a000000-0000-4000-8000-00000000000f',
  watermark: '0a000000-0000-4000-8000-000000000010',
} as const;

export const loopIds = {
  tags: '1a000000-0000-4000-8000-000000000001',
} as const;

export const useIds = {
  tagPill: '2a000000-0000-4000-8000-000000000001',
  brandBadge: '2a000000-0000-4000-8000-000000000002',
} as const;

export const bindingIds = {
  title: '3a000000-0000-4000-8000-000000000001',
  price: '3a000000-0000-4000-8000-000000000002',
  date: '3a000000-0000-4000-8000-000000000003',
  tag: '3a000000-0000-4000-8000-000000000004',
  qr: '3a000000-0000-4000-8000-000000000005',
} as const;

export const definitionIds = {
  priceText: '4a000000-0000-4000-8000-000000000001',
  brandName: '4a000000-0000-4000-8000-000000000002',
  tagLabel: '4a000000-0000-4000-8000-000000000003',
  detailUrl: '4a000000-0000-4000-8000-000000000004',
  renderDate: '4a000000-0000-4000-8000-000000000005',
  badgeVariant: '4a000000-0000-4000-8000-000000000006',
  brandIcon: '4a000000-0000-4000-8000-000000000007',
  featuredTags: '4a000000-0000-4000-8000-000000000008',
} as const;

export const assetIds = {
  logoBadge: '5a000000-0000-4000-8000-000000000001',
  logoLegacy: '5a000000-0000-4000-8000-000000000002',
  brandSans: '5a000000-0000-4000-8000-000000000003',
  wrongKind: '5a000000-0000-4000-8000-000000000004',
  editorialSerif: '5a000000-0000-4000-8000-000000000005',
  dataMono: '5a000000-0000-4000-8000-000000000006',
} as const;

export const childTemplateIds = {
  tagPill: '6a000000-0000-4000-8000-000000000001',
  brandBadge: '6a000000-0000-4000-8000-000000000002',
  offerCard: '6a000000-0000-4000-8000-000000000004',
} as const;

export const schemaRepeatSources: PrototypeRepeatSource[] = [
  {
    id: 'tags',
    sourceGroup: 'SYSTEM',
    expression: 'context(invocation, /tags)',
    path: '/tags',
    label: '标签列表',
    optional: true,
    sourceType: 'SCALAR_LIST',
    itemStaticSchemaRef: 'system-basic-text@v1',
    itemValueType: 'text',
    sampleValues: ['新品', '限时', '会员专享', '包邮'],
  },
  {
    id: 'offers',
    sourceGroup: 'SYSTEM',
    expression: 'context(invocation, /offers)',
    path: '/offers',
    label: '优惠卡列表',
    optional: false,
    sourceType: 'REFERENCE_LIST',
    itemStaticSchemaRef: 'offer-card@v2',
    sampleValues: [
      { name: '新品首发', price: '¥199', badge: 'NEW' },
      { name: '会员专享', price: '¥169', badge: 'VIP' },
      { name: '组合优惠', price: '¥299', badge: 'BUNDLE' },
    ],
  },
];

export const repeatTemplateCandidates: PrototypeRepeatTemplateCandidate[] = [
  { templateId: childTemplateIds.tagPill, name: '标签胶囊', ownerScope: 'workspace:campaign', staticSchemaRef: 'system-basic-text@v1', lifecycle: 'ACTIVE', readiness: 'READY' },
  { templateId: childTemplateIds.offerCard, name: '优惠信息卡', ownerScope: 'workspace:campaign', staticSchemaRef: 'offer-card@v2', lifecycle: 'ACTIVE', readiness: 'READY' },
  { templateId: childTemplateIds.brandBadge, name: '品牌角标', ownerScope: 'workspace:campaign', staticSchemaRef: 'brand@v1', lifecycle: 'ACTIVE', readiness: 'READY' },
  { templateId: '6a000000-0000-4000-8000-000000000005', name: '旧优惠卡', ownerScope: 'workspace:campaign', staticSchemaRef: 'offer-card@v1', lifecycle: 'DELETED', readiness: 'STALE' },
];

export type NodeGroup = 'container' | 'element' | 'compose';

export const nodeCatalog: { kind: NodeKind; label: string; group: NodeGroup }[] = [
  { kind: 'group', label: 'Group 组', group: 'container' },
  { kind: 'frame', label: 'Frame 框', group: 'container' },
  { kind: 'stack', label: 'Stack 堆叠', group: 'container' },
  { kind: 'grid', label: 'Grid 网格', group: 'container' },
  { kind: 'repeat', label: 'Repeat 循环', group: 'container' },
  { kind: 'conditional', label: 'Conditional 条件', group: 'container' },
  { kind: 'text', label: 'Text 文本', group: 'element' },
  { kind: 'image', label: 'Image 图片', group: 'element' },
  { kind: 'rect', label: 'Rect 矩形', group: 'element' },
  { kind: 'ellipse', label: 'Ellipse 椭圆', group: 'element' },
  { kind: 'line', label: 'Line 直线', group: 'element' },
  { kind: 'polygon', label: 'Polygon 多边形', group: 'element' },
  { kind: 'polyline', label: 'Polyline 折线', group: 'element' },
  { kind: 'path', label: 'Path 路径', group: 'element' },
  { kind: 'qrCode', label: 'QRCode 二维码', group: 'element' },
  { kind: 'barcode', label: 'Barcode 条码', group: 'element' },
  { kind: 'templateUse', label: 'TemplateUse 嵌套模板容器', group: 'compose' },
];

export const elementCatalog: ElementCatalogEntry[] = [
  { adapter: false, description: '单值文本、字体资产与排版', group: 'basic', kind: 'text', label: '文本', searchTerms: ['text', '文字', '字体'], slotSummary: '文本值 · FONT · 颜色' },
  { adapter: false, description: '图片资源与裁切适配', group: 'basic', kind: 'image', label: '图片', searchTerms: ['image', 'asset', '资源', '照片'], slotSummary: '资源 · 适配 · 圆角' },
  { adapter: false, description: '填充、描边与四角圆角', group: 'basic', kind: 'rect', label: '矩形', searchTerms: ['rect', 'rectangle', '方形', '圆角'], slotSummary: '填充 · 描边 · 圆角' },
  { adapter: false, description: '椭圆、圆形与环形', group: 'basic', kind: 'ellipse', label: '椭圆', searchTerms: ['ellipse', 'circle', '圆形', '环形'], slotSummary: '填充 · 描边 · 环孔' },
  { adapter: false, description: '线帽与两端箭头', group: 'basic', kind: 'line', label: '直线', searchTerms: ['line', 'divider', '分隔线', '箭头'], slotSummary: '线条 · 箭头 · 线帽' },
  { adapter: false, description: '点集、折线与闭合轮廓', group: 'basic', kind: 'polyline', label: '折线 / 多边形', searchTerms: ['polyline', 'polygon', '点', '折线', '多边形'], slotSummary: '点集 · 闭合 · 转角' },
  { adapter: false, description: '导入并呈现矢量 Path', group: 'basic', kind: 'path', label: '路径', searchTerms: ['path', 'svg', 'vector', '贝塞尔', '矢量'], slotSummary: '路径 · 填充规则' },
  { adapter: false, description: '星形、三角、箭头与多边形', group: 'basic', kind: 'polygon', label: '其他形状', searchTerms: ['shape', 'star', 'triangle', 'arrow', '星形', '三角'], slotSummary: '预设 · 边数 · 内半径' },
  { adapter: true, description: '内容、纠错级别与静区', group: 'domain', kind: 'qrCode', label: '二维码', searchTerms: ['qr', 'qrcode', '二维码', '链接'], slotSummary: '内容 · 容错 · 颜色' },
  { adapter: true, description: 'EAN / UPC / Code 系列', group: 'domain', kind: 'barcode', label: '条形码', searchTerms: ['barcode', 'ean', 'upc', 'code128', '条码'], slotSummary: '码值 · 制式 · 码文' },
];

export const containerCatalog: ContainerCatalogEntry[] = [
  { description: '子项保留各自位置，可整体选择和移动', kind: 'group', label: '自由分组', preset: 'group' },
  { description: '可裁剪、带底色和描边的自由容器', kind: 'frame', label: '框架', preset: 'frame' },
  { description: '按排列方向横向或纵向布局子项', kind: 'stack', label: '堆叠容器', preset: 'stack' },
  { description: '按行列轨道自动排布', kind: 'grid', label: '网格', preset: 'grid' },
  { description: '绑定列表并重复同一子结构', kind: 'repeat', label: '循环容器', preset: 'repeat' },
  { description: '按条件保留或省略整个子树', kind: 'conditional', label: '条件容器', preset: 'conditional' },
  { description: '选择属性并调用模板', kind: 'templateUse', label: '嵌套模板容器', preset: 'templateUse' },
];

/**
 * Standalone TemplateUse only accepts one directly referenced object or one primitive property.
 * Array properties deliberately stay in Repeat's separate source model.
 */
export const templateUseContextSources: PrototypeTemplateUseContextSource[] = [
  {
    id: 'brand',
    label: '品牌对象',
    pointer: '/brand',
    selector: 'context(invocation, /brand)',
    valueType: 'reference',
    typeLabel: '引用',
    presence: 'required',
    kind: 'REFERENCE',
    compatibilityKey: 'schema:brand@v1',
    contextLabel: 'brand@v1',
  },
  {
    id: 'title',
    label: '标题',
    pointer: '/title',
    selector: 'context(invocation, /title)',
    valueType: 'text',
    typeLabel: '文本',
    presence: 'required',
    kind: 'SCALAR_PROPOSAL',
    compatibilityKey: 'proposal:scalar:text',
    contextLabel: '文本值 · 无 index',
  },
  {
    id: 'sku',
    label: '商品编码',
    pointer: '/sku',
    selector: 'context(invocation, /sku)',
    valueType: 'text',
    typeLabel: '文本',
    presence: 'required',
    kind: 'SCALAR_PROPOSAL',
    compatibilityKey: 'proposal:scalar:text',
    contextLabel: '文本值 · 无 index',
  },
  {
    id: 'price',
    label: '价格',
    pointer: '/price',
    selector: 'context(invocation, /price)',
    valueType: 'decimal',
    typeLabel: '数值',
    presence: 'required',
    kind: 'SCALAR_PROPOSAL',
    compatibilityKey: 'proposal:scalar:decimal',
    contextLabel: '数值 · 无 index',
  },
  {
    id: 'promotion-enabled',
    label: '促销开关',
    pointer: '/promotionEnabled',
    selector: 'context(invocation, /promotionEnabled)',
    valueType: 'boolean',
    typeLabel: '布尔',
    presence: 'required',
    kind: 'SCALAR_PROPOSAL',
    compatibilityKey: 'proposal:scalar:boolean',
    contextLabel: '布尔值 · 无 index',
  },
  {
    id: 'member-eligible',
    label: '会员资格',
    pointer: '/memberEligible',
    selector: 'context(invocation, /memberEligible)',
    valueType: 'boolean',
    typeLabel: '布尔',
    presence: 'optional',
    kind: 'SCALAR_PROPOSAL',
    compatibilityKey: 'proposal:scalar:boolean',
    contextLabel: '布尔值 · 无 index',
  },
  {
    id: 'launch-date',
    label: '发布日期',
    pointer: '/launchDate',
    selector: 'context(invocation, /launchDate)',
    valueType: 'date',
    typeLabel: '日期',
    presence: 'optional',
    kind: 'SCALAR_PROPOSAL',
    compatibilityKey: 'proposal:scalar:date',
    contextLabel: '日期值 · 无 index',
  },
];

export const nestedTemplates: NestedTemplateEntry[] = [
  {
    id: childTemplateIds.brandBadge,
    name: '品牌角标',
    compatibilityKey: 'schema:brand@v1',
    contextLabel: 'brand@v1',
    detail: '34 × 6 mm · 3 个公开参数',
    lifecycle: 'ACTIVE',
    readiness: 'READY',
    proposal: false,
  },
  {
    id: '6a000000-0000-4000-8000-000000000006',
    name: '基础文本模板',
    compatibilityKey: 'proposal:scalar:text',
    contextLabel: '文本值 · 无 index',
    detail: '自适应 · 公开 value',
    lifecycle: 'ACTIVE',
    readiness: 'READY',
    proposal: true,
  },
  {
    id: '6a000000-0000-4000-8000-000000000007',
    name: '基础数值模板',
    compatibilityKey: 'proposal:scalar:decimal',
    contextLabel: '数值 · 无 index',
    detail: '自适应 · 公开 value',
    lifecycle: 'ACTIVE',
    readiness: 'READY',
    proposal: true,
  },
  {
    id: '6a000000-0000-4000-8000-000000000008',
    name: '基础布尔模板',
    compatibilityKey: 'proposal:scalar:boolean',
    contextLabel: '布尔值 · 无 index',
    detail: '自适应 · 公开 value',
    lifecycle: 'ACTIVE',
    readiness: 'READY',
    proposal: true,
  },
  {
    id: '6a000000-0000-4000-8000-000000000009',
    name: '基础日期模板',
    compatibilityKey: 'proposal:scalar:date',
    contextLabel: '日期值 · 无 index',
    detail: '自适应 · 公开 value',
    lifecycle: 'ACTIVE',
    readiness: 'READY',
    proposal: true,
  },
];

export const nodeGroupLabels: Record<NodeGroup, string> = {
  container: '容器 · 拥有 children',
  element: '元素 · 叶子',
  compose: '组合 · 嵌套模板宿主',
};

export const designTree: DesignerNode = {
  id: nodeIds.canvas,
  kind: 'canvas',
  name: '画板 Canvas',
  detail: '90×54mm · 自由布局 · 内边距 0mm',
  flags: [],
  props: [
    { label: 'widthMm', value: '90', bindable: false },
    { label: 'heightMm', value: '54', bindable: false },
    { label: 'layoutMode', value: 'FREE', bindable: false, options: ['FREE', 'STACK', 'GRID'] },
    { label: 'direction', value: 'VERTICAL', bindable: false, options: ['VERTICAL', 'HORIZONTAL'] },
    { label: 'gapMm', value: '0', bindable: false },
    { label: 'mainAlign', value: 'START', bindable: false, options: ['START', 'CENTER', 'END', 'SPACE_BETWEEN'] },
    { label: 'crossAlign', value: 'START', bindable: false, options: ['START', 'CENTER', 'END', 'STRETCH'] },
    { label: 'columns', value: '1*, 1*', bindable: false },
    { label: 'rows', value: '1*', bindable: false },
    { label: 'columnGapMm', value: '0', bindable: false },
    { label: 'rowGapMm', value: '0', bindable: false },
    { label: 'padding.topMm', value: '0', bindable: false },
    { label: 'padding.rightMm', value: '0', bindable: false },
    { label: 'padding.bottomMm', value: '0', bindable: false },
    { label: 'padding.leftMm', value: '0', bindable: false },
    { label: 'backgroundColor', value: '#FFFFFF', bindable: true },
  ],
  children: [
    {
      id: nodeIds.rootStack,
      kind: 'stack',
      name: 'rootColumn 根分栏',
      detail: 'VERTICAL · gap 2mm · padding 4mm',
      flags: [],
      props: [
        { label: 'direction', value: 'VERTICAL', bindable: true, options: ['VERTICAL', 'HORIZONTAL'] },
        { label: 'gapMm', value: '2', bindable: true },
        { label: 'padding.topMm', value: '4', bindable: true },
        { label: 'fill.color', value: 'surface', bindable: true },
      ],
      children: [
        {
          id: nodeIds.titleText,
          kind: 'text',
          name: 'titleText 标题',
          detail: '单值文本 · 10.5pt · brand-sans',
          flags: [],
          props: [
            {
              label: 'runs[0].text',
              value: '春季新品发布会',
              bindable: true,
              binding: { id: bindingIds.title, ref: 'runs[0].text', source: '/title', note: 'Schema field · text' },
            },
            { label: 'runs[0].fontRef', value: `{assetId:${assetIds.brandSans}}`, bindable: true },
            { label: 'runs[0].fontSizePt', value: '10.5', bindable: true },
            { label: 'writingMode', value: 'HORIZONTAL_TB', bindable: true, options: ['HORIZONTAL_TB', 'VERTICAL_RL'] },
            { label: 'lineBreak', value: 'WORD', bindable: true, options: ['NONE', 'WORD', 'CHAR'] },
            { label: 'overflow', value: 'ELLIPSIS', bindable: true, options: ['VISIBLE', 'CLIP', 'ELLIPSIS', 'FAIL'] },
            { label: 'horizontalAlign', value: 'LEFT', bindable: true, options: ['LEFT', 'CENTER', 'RIGHT', 'JUSTIFY', 'SPACE_EVENLY'] },
            { label: 'verticalAlign', value: 'TOP', bindable: true, options: ['TOP', 'CENTER', 'BOTTOM', 'JUSTIFY', 'SPACE_EVENLY'] },
            { label: 'padding.topMm', value: '0.8', bindable: true },
            { label: 'stroke.widthMm', value: '0', bindable: true },
            { label: 'shrinkToFit', value: 'true', bindable: true, options: ['true', 'false'] },
          ],
          children: [],
        },
        {
          id: nodeIds.priceBand,
          kind: 'frame',
          name: 'priceBand 价格带',
          detail: 'fill accent-wash · corner 2mm',
          flags: [],
          props: [
            { label: 'fill.color', value: 'accent-wash', bindable: true },
            { label: 'cornerRadii.topLeftMm', value: '2', bindable: true },
            {
              label: 'placement.fillWeight',
              value: '1',
              bindable: true,
            },
          ],
          children: [
            {
              id: nodeIds.priceText,
              kind: 'text',
              name: 'priceText 价格',
              detail: '单值文本 · 16pt · brand-sans',
              flags: [],
              props: [
                {
                  label: 'runs[0].text',
                  value: '¥ 199.00',
                  bindable: true,
                  binding: { id: bindingIds.price, ref: 'runs[0].text', source: `definition(${definitionIds.priceText})`, note: 'Expression definition · PUBLIC' },
                },
                { label: 'runs[0].fontRef', value: `{assetId:${assetIds.brandSans}}`, bindable: true },
                { label: 'runs[0].fontSizePt', value: '16', bindable: true },
                { label: 'runs[0].color', value: 'ink', bindable: true },
              ],
              children: [],
            },
            {
              id: nodeIds.currencyNote,
              kind: 'text',
              name: 'currencyNote 单位',
              detail: '单值文本 · 7pt · brand-sans',
              flags: [],
              props: [
                { label: 'runs[0].text', value: '起', bindable: true },
                { label: 'runs[0].fontRef', value: `{assetId:${assetIds.brandSans}}`, bindable: true },
                { label: 'runs[0].fontSizePt', value: '7', bindable: true },
              ],
              children: [],
            },
          ],
        },
        {
          id: nodeIds.brandLogo,
          kind: 'image',
          name: 'brandLogo 品牌徽标',
          detail: 'imageRef logo-badge · fit CONTAIN',
          flags: [],
          props: [
            {
              label: 'imageRef',
              value: `{assetId:${assetIds.logoBadge}}`,
              bindable: true,
            },
            { label: 'fit', value: 'CONTAIN', bindable: true, options: ['CONTAIN', 'COVER', 'FILL'] },
            { label: 'sampling', value: 'LINEAR', bindable: true, options: ['LINEAR', 'NEAREST'] },
          ],
          children: [],
        },
        {
          id: nodeIds.dateLine,
          kind: 'text',
          name: 'dateLine 日期行',
          detail: '绑定 /launchDate · optional',
          flags: [],
          props: [
            {
              label: 'runs[0].text',
              value: '2026-08-01',
              bindable: true,
              binding: { id: bindingIds.date, ref: 'runs[0].text', source: '/launchDate', note: 'optional field · 运行时可能 typed ABSENT' },
            },
            { label: 'runs[0].fontRef', value: `{assetId:${assetIds.brandSans}}`, bindable: true },
            { label: 'runs[0].fontSizePt', value: '7', bindable: true },
            { label: 'maxLines', value: '1', bindable: true },
          ],
          children: [],
        },
        {
          id: nodeIds.tagLoop,
          loopId: loopIds.tags,
          kind: 'repeat',
          name: 'tagLoop 标签循环',
          detail: 'items /tags : list<text> · system-basic-text@v1',
          flags: [],
          props: [
            { label: 'items', value: 'context(invocation, /tags)', bindable: false },
            { label: 'absentPolicy', value: 'EMPTY', bindable: false, options: ['ERROR', 'EMPTY'] },
            { label: 'itemLayout.kind', value: 'STACK', bindable: false },
            { label: 'itemLayout.direction', value: 'ROW', bindable: true, options: ['ROW', 'COLUMN'] },
            { label: 'itemLayout.gapMm', value: '1.5', bindable: true },
            { label: 'instanceLayout.kind', value: 'GRID', bindable: false },
            { label: 'instanceLayout.columns', value: '3', bindable: true },
            { label: 'instanceLayout.columnGapMm', value: '1.5', bindable: true },
            { label: 'instanceLayout.rowGapMm', value: '1.5', bindable: true },
          ],
          children: [
            {
              id: nodeIds.tagChip,
              kind: 'text',
              name: 'tagChip 标签项',
              detail: '绑定 loop item · 显式 loopId domain',
              flags: [],
              props: [
                { label: 'placement.type', value: 'PACK', bindable: false },
                {
                  label: 'runs[0].text',
                  value: '新品',
                  bindable: true,
                  binding: { id: bindingIds.tag, ref: 'runs[0].text', source: `context(loop ${loopIds.tags}, /value)`, note: '显式 loopId domain · /value · 无 $parent 动态作用域' },
                },
                { label: 'runs[0].fontRef', value: `{assetId:${assetIds.brandSans}}`, bindable: true },
                { label: 'runs[0].fontSizePt', value: '6.5', bindable: true },
              ],
              children: [],
            },
            {
              id: nodeIds.tagTemplateUse,
              useId: useIds.tagPill,
              kind: 'templateUse',
              name: 'tagPillUse 标签子模板',
              detail: 'tag-pill@current · ContextSelector = whole scalar item',
              flags: [],
              props: [
                { label: 'placement.type', value: 'PACK', bindable: false },
                { label: 'templateRef.templateId', value: childTemplateIds.tagPill, bindable: false },
                { label: 'contextSelector', value: `loop(${loopIds.tags}), pointer ""`, bindable: false },
                { label: 'contextAbsentPolicy', value: 'ERROR', bindable: false, options: ['ERROR', 'SKIP'] },
                { label: 'fills', value: '[]', bindable: false },
              ],
              children: [],
            },
          ],
        },
        {
          id: nodeIds.linkCode,
          kind: 'qrCode',
          name: 'linkCode 链接码',
          detail: '20×20mm · ECL M',
          flags: [],
          props: [
            {
              label: 'content',
              value: '(静态 URL)',
              bindable: true,
              binding: { id: bindingIds.qr, ref: 'content', source: `definition(${definitionIds.detailUrl})`, note: 'Expression definition · PUBLIC' },
            },
            { label: 'errorCorrectionLevel', value: 'M', bindable: true, options: ['L', 'M', 'Q', 'H'] },
            { label: 'foregroundColor', value: 'ink', bindable: true },
          ],
          children: [],
        },
        {
          id: nodeIds.brandBadge,
          useId: useIds.brandBadge,
          kind: 'templateUse',
          name: 'brandBadge 品牌角标',
          detail: '子模板 brand-badge@current · 显式 fill',
          flags: [],
          props: [
            { label: 'placement.type', value: 'STACK', bindable: false },
            { label: 'templateRef.templateId', value: childTemplateIds.brandBadge, bindable: false },
            { label: 'contextSelector', value: 'context(invocation, /brand)', bindable: false },
            { label: 'contextAbsentPolicy', value: 'ERROR', bindable: false, options: ['ERROR', 'SKIP'] },
            {
              label: 'fill: brandName',
              value: '← /brand.name',
              bindable: false,
            },
          ],
          children: [],
        },
        {
          id: nodeIds.promoCorner,
          kind: 'conditional',
          name: 'promoCorner 促销角',
          detail: 'render:false · 不占布局不解析资源',
          flags: ['render:false'],
          props: [
            { label: 'render', value: 'false', bindable: true, options: ['true', 'false'] },
            { label: 'condition', value: 'literal(true)', bindable: false },
            { label: 'absentPolicy', value: 'FALSE', bindable: false, options: ['ERROR', 'FALSE'] },
          ],
          children: [
            {
              id: nodeIds.promoBlock,
              kind: 'rect',
              name: 'promoBlock 促销块',
              detail: '随 conditional 整体跳过',
              flags: [],
              props: [{ label: 'fill.color', value: 'coral', bindable: true }],
              children: [],
            },
          ],
        },
        {
          id: nodeIds.watermark,
          kind: 'rect',
          name: 'watermark 水印底',
          detail: 'visible:false · 仍占布局 · 资源错误仍失败',
          flags: ['visible:false'],
          props: [
            { label: 'visible', value: 'false', bindable: true, options: ['true', 'false'] },
            { label: 'fill.color', value: 'hairline', bindable: true },
          ],
          children: [],
        },
      ],
    },
  ],
};

export const assets: DesignerAsset[] = [
  { id: assetIds.logoBadge, name: 'logo-badge', kind: 'IMAGE', status: 'ACTIVE', detail: 'PNG · sRGB · 24 KB · contentVersion 3', tags: ['品牌', '徽标'], usedBy: '/designRoot/children/0/children/2/imageRef' },
  { id: assetIds.logoLegacy, name: 'logo-legacy', kind: 'IMAGE', status: 'DELETED', detail: 'PNG · 41 KB · 删除于 2026-06-30', tags: ['品牌'], usedBy: '(历史导入残留引用)' },
  { id: assetIds.brandSans, name: 'brand-sans', kind: 'FONT', status: 'ACTIVE', detail: 'TTF · single face · 388 KB · contentVersion 1', tags: ['品牌', '无衬线'], previewFamily: '"Microsoft YaHei UI", "Segoe UI", sans-serif', usedBy: '标题等文本元素' },
  { id: assetIds.wrongKind, name: 'legacy-display', kind: 'IMAGE', status: 'ACTIVE', detail: 'PNG · 12 KB · contentVersion 1', tags: ['迁移'], misuse: 'dateLine 的字体资产引用了 IMAGE → kind mismatch' },
  { id: assetIds.editorialSerif, name: 'editorial-serif', kind: 'FONT', status: 'ACTIVE', detail: 'OTF · single face · 472 KB · contentVersion 1', tags: ['标题', '衬线'], previewFamily: 'Georgia, "Songti SC", SimSun, serif' },
  { id: assetIds.dataMono, name: 'data-mono', kind: 'FONT', status: 'ACTIVE', detail: 'TTF · single face · 244 KB · contentVersion 2', tags: ['数字', '等宽'], previewFamily: 'Consolas, "SFMono-Regular", monospace' },
];

export const definitions: DesignerDefinition[] = [
  {
    id: definitionIds.priceText,
    name: 'priceText',
    kind: 'EXPRESSION',
    valueType: 'text',
    domain: 'invocation',
    detail: "concat('¥ ', formatDecimal(input.price, 2, 2, HALF_UP))",
    inputs: [{ alias: 'price', source: { kind: 'context', domain: 'invocation', pointer: '/price' } }],
    source: "concat('¥ ', formatDecimal(input.price, 2, 2, HALF_UP))",
  },
  {
    id: definitionIds.brandName,
    name: 'brandName',
    kind: 'CUSTOM',
    valueType: 'text',
    exposure: 'PRIVATE',
    defaultValue: '海博优选',
    detail: '默认「海博优选」· PRIVATE 不接收外部 override',
  },
  {
    id: definitionIds.tagLabel,
    name: 'tagLabel',
    kind: 'MAPPING',
    valueType: 'text',
    output: 'text',
    domain: { kind: 'loop', loopId: loopIds.tags },
    detail: '有序 Mapping · 2 cases · required otherwise',
    input: { kind: 'context', domain: { kind: 'loop', loopId: loopIds.tags }, pointer: '/value' },
    cases: [
      { id: 'mapping-tag-new', operator: 'PATTERN_MATCH', operand: '^新', then: 'NEW' },
      { id: 'mapping-tag-member', operator: 'EQ', operand: '会员专享', then: 'VIP' },
    ],
    otherwise: 'DEFAULT',
  },
  {
    id: definitionIds.detailUrl,
    name: 'detailUrl',
    kind: 'EXPRESSION',
    valueType: 'text',
    domain: 'invocation',
    detail: "concat('https://example.cn/p/', input.sku)",
    inputs: [{ alias: 'sku', source: { kind: 'context', domain: 'invocation', pointer: '/sku' } }],
    source: "concat('https://example.cn/p/', input.sku)",
  },
  {
    id: definitionIds.renderDate,
    name: 'renderDate',
    kind: 'EXPRESSION',
    valueType: 'date',
    domain: 'invocation',
    detail: 'input.today',
    inputs: [{ alias: 'today', source: { kind: 'capability', capability: 'CLOCK', operation: 'UTC_DATE' } }],
    source: 'input.today',
  },
  {
    id: definitionIds.badgeVariant,
    name: 'badgeVariant',
    kind: 'EXPRESSION',
    valueType: 'text',
    domain: { kind: 'loop', loopId: loopIds.tags },
    detail: "if(input.draw < 0.5, 'A', 'B')",
    inputs: [{ alias: 'draw', source: { kind: 'capability', capability: 'RANDOM', operation: 'UNIFORM_DECIMAL_0_1' } }],
    source: "if(input.draw < 0.5, 'A', 'B')",
  },
  {
    id: definitionIds.brandIcon,
    name: 'brandIcon',
    kind: 'CUSTOM',
    valueType: 'imageRef',
    exposure: 'PUBLIC',
    defaultValue: `{"assetId":"${assetIds.logoBadge}"}`,
    detail: '公开图片输入 · 默认 logo-badge',
  },
  {
    id: definitionIds.featuredTags,
    name: 'featuredTags',
    kind: 'CUSTOM',
    valueType: 'list<text>',
    exposure: 'PUBLIC',
    defaultValue: '["精选","当季","限量"]',
    detail: '公开标签列表 · authored literal default',
  },
];

const scalarItemSchemaRefs: Record<'text' | 'decimal' | 'boolean' | 'date' | 'time', string> = {
  text: 'system-basic-text@v1',
  decimal: 'system-basic-decimal@v1',
  boolean: 'system-basic-boolean@v1',
  date: 'system-basic-date@v1',
  time: 'system-basic-time@v1',
};

function prototypeListSamples(definition: DesignerDefinition, itemType: keyof typeof scalarItemSchemaRefs): PrototypeRepeatSource['sampleValues'] {
  if (definition.kind === 'CUSTOM') {
    try {
      const parsed = JSON.parse(definition.defaultValue) as unknown;
      if (Array.isArray(parsed) && parsed.every((value) => ['string', 'number', 'boolean'].includes(typeof value))) {
        return parsed as PrototypeRepeatSource['sampleValues'];
      }
    } catch {
      // The editor keeps an invalid authored draft visible; projection uses safe demo values.
    }
  }
  switch (itemType) {
    case 'decimal': return [19.9, 29.9, 39.9];
    case 'boolean': return [true, false, true];
    case 'date': return ['2026-09-01', '2026-09-02', '2026-09-03'];
    case 'time': return ['09:00:00', '12:30:00', '18:00:00'];
    case 'text': return ['示例 A', '示例 B', '示例 C'];
  }
}

export function repeatSourcesForDefinitions(authoredDefinitions: readonly DesignerDefinition[]): PrototypeRepeatSource[] {
  const authored = authoredDefinitions.flatMap((definition): PrototypeRepeatSource[] => {
    const match = /^list<(text|decimal|boolean|date|time)>$/.exec(definitionValueType(definition));
    if (!match) return [];
    const itemValueType = match[1] as keyof typeof scalarItemSchemaRefs;
    return [{
      id: `definition:${definition.id}`,
      sourceGroup: definition.kind === 'CUSTOM' ? 'CUSTOM' : 'DERIVED',
      expression: `definition(${definition.id})`,
      definitionId: definition.id,
      label: definition.name,
      optional: false,
      sourceType: 'SCALAR_LIST',
      itemStaticSchemaRef: scalarItemSchemaRefs[itemValueType],
      itemValueType,
      sampleValues: prototypeListSamples(definition, itemValueType),
    }];
  });
  return [...schemaRepeatSources.map((source) => structuredClone(source)), ...authored];
}

/** Fixture projection kept for archived prototype panels and tests. */
export const repeatSources: PrototypeRepeatSource[] = repeatSourcesForDefinitions(definitions);

export const draftBoxes: DraftBox[] = [
  { nodeId: nodeIds.titleText, x: 4, y: 4, w: 58, h: 6, tone: 'text', label: 'titleText' },
  { nodeId: nodeIds.priceBand, x: 4, y: 11.5, w: 50, h: 15, tone: 'frame', label: 'priceBand' },
  { nodeId: nodeIds.priceText, x: 2.3, y: 1.8, w: 32, h: 10, tone: 'text', label: 'priceText' },
  { nodeId: nodeIds.currencyNote, x: 35.8, y: 4.8, w: 11, h: 5, tone: 'text', label: 'currencyNote' },
  { nodeId: nodeIds.brandLogo, x: 66, y: 4, w: 20, h: 20, tone: 'image', label: 'brandLogo' },
  { nodeId: nodeIds.dateLine, x: 4, y: 29, w: 44, h: 5, tone: 'text', label: 'dateLine' },
  { nodeId: nodeIds.tagLoop, x: 4, y: 36, w: 52, h: 8, tone: 'chip', label: 'tagLoop ×N' },
  { nodeId: nodeIds.linkCode, x: 66, y: 26, w: 20, h: 20, tone: 'qr', label: 'linkCode' },
  { nodeId: nodeIds.brandBadge, x: 4, y: 47, w: 34, h: 5.5, tone: 'chip', label: 'brandBadge' },
  { nodeId: nodeIds.watermark, x: 0, y: 0, w: 90, h: 54, tone: 'ghost', label: 'watermark visible:false' },
];

export const schemaFields = [
  { path: '/title', type: 'text', presence: 'required' },
  { path: '/price', type: 'decimal', presence: 'required' },
  { path: '/promotionEnabled', type: 'boolean', presence: 'required', prototype: true },
  { path: '/memberEligible', type: 'boolean', presence: 'optional', prototype: true },
  { path: '/launchDate', type: 'date', presence: 'optional' },
  { path: '/tags', type: 'array[text]', presence: 'optional' },
  { path: '/offers', type: 'array[reference offer-card@v2]', presence: 'required', prototype: true },
  { path: '/brand', type: 'reference', presence: 'required' },
  { path: '/sku', type: 'text', presence: 'required' },
];

export function problemsFor(scenario: Scenario): DesignerProblem[] {
  switch (scenario) {
    case 'asset-deleted':
      return [{
        code: 'TEMPLATE_ASSET_DELETED',
        severity: 'dependency',
        message: 'Asset logo-badge 已 DELETED,imageRef 引用仍存在;资源解析失败',
        pointer: '/designRoot/children/0/children/2/imageRef',
        nodeId: nodeIds.brandLogo,
      }];
    case 'binding-absent':
      return [{
        code: 'BINDING_VALUE_ABSENT',
        severity: 'runtime',
        message: 'Binding 求值为 typed ABSENT:样本缺少 /launchDate;不回退 baseline',
        pointer: '/designRoot/children/0/children/3/bindings/0',
        nodeId: nodeIds.dateLine,
        bindingId: bindingIds.date,
        span: 'UTF-16 [12, 28)',
      }];
    case 'child-fill-invalid':
      return [{
        code: 'TEMPLATE_USE_FILL_TARGET_MISSING',
        severity: 'dependency',
        message: 'child Template current 已不再公开目标 definitionId;authored fill 仍存在,父 Template 失效',
        pointer: '/designRoot/children/0/children/7/fills/0/targetDefinitionId',
        nodeId: nodeIds.brandBadge,
        definitionId: definitionIds.brandName,
      }];
    case 'layout-error':
      return [{
        code: 'LAYOUT_FILL_WEIGHT_INVALID',
        severity: 'hard',
        message: 'Stack main-axis 为 HUG_CONTENT 时 fillWeight 不可求值;hard error 不可确认',
        pointer: '/designRoot/children/0/children/1/placement/fillWeight',
        nodeId: nodeIds.priceBand,
      }];
    default:
      return [];
  }
}

export function rootDocumentSample(scenario: Scenario): string {
  const lines = [
    '{',
    '  "title": "春季新品发布会",',
    '  "price": 199.00,',
    '  "promotionEnabled": true,',
    '  "memberEligible": true,',
  ];
  if (scenario !== 'binding-absent') {
    lines.push('  "launchDate": "2026-08-01",');
  }
  lines.push(
    '  "tags": ["新品", "限量", "会员"],',
    '  "offers": [',
    '    { "name": "新品首发", "price": 199.00, "badge": "NEW" },',
    '    { "name": "会员专享", "price": 169.00, "badge": "VIP" }',
    '  ],',
    '  "brand": { "name": "海博优选" },',
    '  "sku": "SKU-1042"',
    '}',
  );
  return lines.join('\n');
}

export const customValuesSample = [
  '[',
  `  { "definitionId": "${definitionIds.brandIcon}", "value": { "assetId": "${assetIds.logoBadge}" } },`,
  `  { "definitionId": "${definitionIds.featuredTags}", "value": ["精选", "限量"] },`,
  `  { "definitionId": "${definitionIds.brandName}", "value": "外部覆盖值" },`,
  '  { "definitionId": "4a000000-0000-4000-8000-000000000099", "value": 1 }',
  ']',
].join('\n');

export interface BindingEditorTarget {
  nodeId: string;
  label: string;
}

export interface DesignerState {
  tree: DesignerNode;
  /** Top-level DesignDSL definitions[] working draft; no backend data-source catalog exists. */
  definitions: DesignerDefinition[];
  scenario: Scenario;
  selectedNodeId: string;
  selectedNodeIds: string[];
  leftTab: LeftTab;
  activeTool: CanvasTool;
  spacePanActive: boolean;
  showElementOutlines: boolean;
  canvasOffset: { x: number; y: number };
  repeatPreviewSample: RepeatPreviewSample;
  repeatPreviewMode: RepeatPreviewMode;
  repeatActiveIndex: number;
  conditionalPreviewSample: ConditionalPreviewSample;
  boxes: DraftBox[];
  nextNodeOrdinal: number;
  zoom: number;
  dpi: number;
  outputFormat: 'PNG' | 'JPEG';
  jpegQuality: number;
  layoutTrace: boolean;
  previewGeneration: number;
  revision: number;
  expectedRevision: number;
  currentRevision: number;
  dirty: boolean;
  templateStatus: TemplateStatus;
  savePhase: SavePhase;
  previewPhase: PreviewPhase;
  notice: string | null;
  bindingEditor: BindingEditorTarget | null;
}

export const initialDesignerState: DesignerState = {
  tree: addAuthoringProps(structuredClone(designTree)),
  definitions: structuredClone(definitions),
  scenario: 'clean',
  selectedNodeId: nodeIds.titleText,
  selectedNodeIds: [nodeIds.titleText],
  leftTab: 'elements',
  activeTool: 'select',
  spacePanActive: false,
  showElementOutlines: false,
  canvasOffset: { x: 0, y: 0 },
  repeatPreviewSample: 'values',
  repeatPreviewMode: 'instances',
  repeatActiveIndex: 0,
  conditionalPreviewSample: 'true',
  boxes: structuredClone(draftBoxes),
  nextNodeOrdinal: 17,
  zoom: 175,
  dpi: 96,
  outputFormat: 'PNG',
  jpegQuality: 90,
  layoutTrace: false,
  previewGeneration: 0,
  revision: 12,
  expectedRevision: 12,
  currentRevision: 12,
  dirty: true,
  templateStatus: 'READY',
  savePhase: 'idle',
  previewPhase: 'idle',
  notice: null,
  bindingEditor: null,
};

export type LayerOrderOperation = 'front' | 'forward' | 'backward' | 'back';

export interface LayerOrderCapabilities {
  front: boolean;
  forward: boolean;
  backward: boolean;
  back: boolean;
}

export type DesignerAction =
  | { type: 'select-node'; nodeId: string; additive?: boolean }
  | { type: 'select-all' }
  | { type: 'rename-node'; nodeId: string; name: string }
  | { type: 'save-definition'; definition: DesignerDefinition }
  | { type: 'set-tab'; tab: LeftTab }
  | { type: 'set-tool'; tool: CanvasTool }
  | { type: 'set-space-pan'; active: boolean }
  | { type: 'toggle-element-outlines' }
  | { type: 'pan-by'; dx: number; dy: number }
  | { type: 'reset-view' }
  | {
      type: 'insert-node';
      kind: Exclude<NodeKind, 'canvas'>;
      preset?: ContainerPreset;
      assetId?: string;
      templateId?: string;
      parentId?: string;
      positionMm?: { x: number; y: number };
    }
  | { type: 'load-stack-demo'; preset: StackDemoPreset }
  | { type: 'load-grid-demo'; preset: GridDemoPreset }
  | { type: 'load-absolute-demo'; preset: AbsoluteDemoPreset }
  | { type: 'load-repeat-demo'; preset: RepeatDemoPreset }
  | { type: 'set-repeat-preview-sample'; sample: RepeatPreviewSample }
  | { type: 'set-repeat-preview-mode'; mode: RepeatPreviewMode }
  | { type: 'set-repeat-active-index'; index: number }
  | { type: 'set-repeat-template'; nodeId: string; templateId: string }
  | { type: 'set-template-use-context'; nodeId: string; sourceId: string }
  | { type: 'set-template-use-template'; nodeId: string; templateId: string }
  | { type: 'load-conditional-demo'; preset: ConditionalDemoPreset }
  | { type: 'set-conditional-preview-sample'; sample: ConditionalPreviewSample }
  | { type: 'bind-image'; assetId: string }
  | { type: 'wrap-selection'; preset: Extract<ContainerPreset, 'group' | 'frame' | 'stack' | 'grid'> }
  | { type: 'flatten-selected' }
  | { type: 'move-selection'; targetId: string }
  | { type: 'drop-node'; draggedId: string; targetId: string; placement: TreeDropPlacement }
  | { type: 'reorder-selection'; operation: LayerOrderOperation }
  | { type: 'transform-box'; nodeId: string; box: Pick<DraftBox, 'x' | 'y' | 'w' | 'h'>; mode: 'move' | 'resize' }
  | { type: 'delete-selection' }
  | { type: 'set-zoom'; zoom: number }
  | { type: 'set-zoom-at'; zoom: number; offset: { x: number; y: number } }
  | { type: 'set-dpi'; dpi: number }
  | { type: 'set-output-format'; format: 'PNG' | 'JPEG' }
  | { type: 'set-jpeg-quality'; quality: number }
  | { type: 'set-layout-trace'; enabled: boolean }
  | { type: 'set-scenario'; scenario: Scenario }
  | { type: 'save' }
  | { type: 'confirm-invalid-save' }
  | { type: 'cancel-save' }
  | { type: 'reload-latest' }
  | { type: 'preview-start' }
  | { type: 'preview-finish' }
  | { type: 'preview-cancel' }
  | { type: 'dismiss-notice' }
  | { type: 'set-notice'; notice: string }
  | { type: 'mark-dirty' }
  | { type: 'update-prop'; nodeId: string; label: string; value: string }
  | { type: 'open-binding'; nodeId: string; label: string }
  | { type: 'close-binding' }
  | { type: 'save-binding'; nodeId: string; label: string; source: string; bindingId: string }
  | { type: 'remove-binding'; nodeId: string; label: string };

function mapNode(tree: DesignerNode, nodeId: string, fn: (node: DesignerNode) => DesignerNode): DesignerNode {
  if (tree.id === nodeId) {
    return fn(tree);
  }
  return { ...tree, children: tree.children.map((child) => mapNode(child, nodeId, fn)) };
}

function mapProp(node: DesignerNode, label: string, fn: (prop: InspectorProp) => InspectorProp): DesignerNode {
  return { ...node, props: node.props.map((prop) => (prop.label === label ? fn(prop) : prop)) };
}

export function isContainerNodeKind(kind: NodeKind): boolean {
  return kind === 'canvas'
    || kind === 'group'
    || kind === 'frame'
    || kind === 'stack'
    || kind === 'grid'
    || kind === 'repeat'
    || kind === 'conditional';
}

export type ManagedLayoutKind = Extract<NodeKind, 'stack' | 'grid' | 'repeat'>;

export interface CanvasProjection {
  widthMm: number;
  heightMm: number;
  layoutMode: CanvasLayoutMode;
  direction: 'VERTICAL' | 'HORIZONTAL';
  padding: { topMm: number; rightMm: number; bottomMm: number; leftMm: number };
}

function finiteNodeProp(node: DesignerNode, label: string, fallback: number): number {
  const raw = node.props.find((prop) => prop.label === label)?.value;
  if (raw === undefined || raw.trim() === '') return fallback;
  const value = Number(raw);
  return Number.isFinite(value) ? value : fallback;
}

export function canvasProjection(tree: DesignerNode): CanvasProjection {
  const canvas = tree.kind === 'canvas' ? tree : findNode(tree, nodeIds.canvas) ?? tree;
  const widthMm = clampNumber(finiteNodeProp(canvas, 'widthMm', templateMeta.canvasMm.width), 1, 1000);
  const heightMm = clampNumber(finiteNodeProp(canvas, 'heightMm', templateMeta.canvasMm.height), 1, 1000);
  const layoutValue = canvas.props.find((prop) => prop.label === 'layoutMode')?.value;
  const layoutMode: CanvasLayoutMode = layoutValue === 'STACK' || layoutValue === 'GRID' ? layoutValue : 'FREE';
  const direction = canvas.props.find((prop) => prop.label === 'direction')?.value === 'HORIZONTAL' ? 'HORIZONTAL' : 'VERTICAL';
  const topMm = clampNumber(finiteNodeProp(canvas, 'padding.topMm', 0), 0, heightMm);
  const rightMm = clampNumber(finiteNodeProp(canvas, 'padding.rightMm', 0), 0, widthMm);
  const bottomMm = clampNumber(finiteNodeProp(canvas, 'padding.bottomMm', 0), 0, Math.max(0, heightMm - topMm));
  const leftMm = clampNumber(finiteNodeProp(canvas, 'padding.leftMm', 0), 0, Math.max(0, widthMm - rightMm));
  return {
    widthMm,
    heightMm,
    layoutMode,
    direction,
    padding: { topMm, rightMm, bottomMm, leftMm },
  };
}

/** Production managed containers plus the explicit T220 Canvas-layout prototype own direct-child positions. */
export function isManagedLayoutKind(kind: NodeKind): kind is ManagedLayoutKind {
  return kind === 'stack' || kind === 'grid' || kind === 'repeat';
}

export function isLayoutManagingNode(node: DesignerNode): boolean {
  return isManagedLayoutKind(node.kind)
    || (node.kind === 'canvas' && canvasProjection(node).layoutMode !== 'FREE');
}

export function flattenDesignerTree(tree: DesignerNode): DesignerNode[] {
  return [tree, ...tree.children.flatMap(flattenDesignerTree)];
}

export function findParentNode(tree: DesignerNode, nodeId: string): DesignerNode | null {
  for (const child of tree.children) {
    if (child.id === nodeId) return tree;
    const parent = findParentNode(child, nodeId);
    if (parent) return parent;
  }
  return null;
}

function addAuthoringProps(node: DesignerNode): DesignerNode {
  const children = node.children.map(addAuthoringProps);
  if (node.kind === 'canvas') return { ...node, children };
  const labels = new Set(node.props.map((prop) => prop.label));
  const box = draftBoxes.find((candidate) => candidate.nodeId === node.id);
  const boxDefaults = node.kind === 'frame' || node.kind === 'stack' || node.kind === 'grid'
    ? containerBoxProps('surface')
    : node.kind === 'text'
      ? paddingProps()
      : [];
  const repeatDefaults: InspectorProp[] = node.kind === 'repeat' ? [
    { label: 'itemLayout.kind', value: 'STACK', bindable: false, options: ['STACK', 'GRID'] },
    { label: 'itemLayout.direction', value: 'ROW', bindable: false, options: ['ROW', 'COLUMN'] },
    { label: 'itemLayout.gapMm', value: '1.5', bindable: false },
    { label: 'itemLayout.columns', value: '2', bindable: false },
    { label: 'itemLayout.columnGapMm', value: '1', bindable: false },
    { label: 'itemLayout.rowGapMm', value: '1', bindable: false },
    { label: 'instanceLayout.kind', value: 'STACK', bindable: false, options: ['STACK', 'GRID'] },
    { label: 'instanceLayout.direction', value: 'ROW', bindable: false, options: ['ROW', 'COLUMN'] },
    { label: 'instanceLayout.gapMm', value: '1.5', bindable: false },
    { label: 'instanceLayout.columns', value: '3', bindable: false },
    { label: 'instanceLayout.columnGapMm', value: '1.5', bindable: false },
    { label: 'instanceLayout.rowGapMm', value: '1.5', bindable: false },
  ] : [];
  const common: InspectorProp[] = [
    { label: 'xMm', value: String(box?.x ?? 0), bindable: true },
    { label: 'yMm', value: String(box?.y ?? 0), bindable: true },
    { label: 'widthMm', value: String(box?.w ?? 24), bindable: true },
    { label: 'heightMm', value: String(box?.h ?? 12), bindable: true },
    { label: 'rotationDeg', value: '0', bindable: true },
    { label: 'opacity', value: '100', bindable: true },
    { label: 'visible', value: node.flags.includes('visible:false') ? 'false' : 'true', bindable: true, options: ['true', 'false'] },
    { label: 'render', value: node.flags.includes('render:false') ? 'false' : 'true', bindable: true, options: ['true', 'false'] },
    { label: 'locked', value: 'false', bindable: false, options: ['true', 'false'] },
    ...managedLayoutPlacementProps(),
  ];
  return {
    ...node,
    children,
    props: [
      ...node.props,
      ...boxDefaults.filter((prop) => !labels.has(prop.label)),
      ...repeatDefaults.filter((prop) => !labels.has(prop.label)),
      ...common.filter((prop) => !labels.has(prop.label)),
    ],
  };
}

function prototypeBox(kind: Exclude<NodeKind, 'canvas'>, ordinal: number): DraftBox {
  const x = 8 + ((ordinal * 7) % 48);
  const y = 7 + ((ordinal * 5) % 34);
  const dimensions: Partial<Record<NodeKind, Pick<DraftBox, 'w' | 'h' | 'tone'>>> = {
    text: { w: 34, h: 7, tone: 'text' },
    image: { w: 22, h: 16, tone: 'image' },
    rect: { w: 25, h: 14, tone: 'frame' },
    ellipse: { w: 16, h: 16, tone: 'frame' },
    line: { w: 28, h: 2, tone: 'ghost' },
    polygon: { w: 18, h: 18, tone: 'frame' },
    polyline: { w: 24, h: 12, tone: 'ghost' },
    path: { w: 20, h: 16, tone: 'frame' },
    qrCode: { w: 18, h: 18, tone: 'qr' },
    barcode: { w: 30, h: 13, tone: 'qr' },
    templateUse: { w: 30, h: 8, tone: 'chip' },
    group: { w: 34, h: 22, tone: 'frame' },
    frame: { w: 34, h: 22, tone: 'frame' },
    stack: { w: 34, h: 22, tone: 'frame' },
    grid: { w: 34, h: 22, tone: 'frame' },
    repeat: { w: 34, h: 16, tone: 'chip' },
    conditional: { w: 34, h: 16, tone: 'ghost' },
  };
  const shape = dimensions[kind] ?? { w: 24, h: 12, tone: 'frame' as const };
  return { nodeId: `prototype-${ordinal}`, x, y, w: shape.w, h: shape.h, tone: shape.tone, label: `${kind}-${ordinal}` };
}

function commonProps(box: DraftBox): InspectorProp[] {
  return [
    { label: 'xMm', value: String(box.x), bindable: true },
    { label: 'yMm', value: String(box.y), bindable: true },
    { label: 'widthMm', value: String(box.w), bindable: true },
    { label: 'heightMm', value: String(box.h), bindable: true },
    { label: 'rotationDeg', value: '0', bindable: true },
    { label: 'opacity', value: '100', bindable: true },
    { label: 'visible', value: 'true', bindable: true, options: ['true', 'false'] },
    { label: 'render', value: 'true', bindable: true, options: ['true', 'false'] },
    { label: 'locked', value: 'false', bindable: false, options: ['true', 'false'] },
    ...managedLayoutPlacementProps(),
  ];
}

function managedLayoutPlacementProps(): InspectorProp[] {
  return [
    { label: 'placement.widthMode', value: 'FIXED', bindable: false, options: ['FIXED', 'FILL'] },
    { label: 'placement.heightMode', value: 'FIXED', bindable: false, options: ['FIXED', 'FILL'] },
    { label: 'placement.minWidthMm', value: '', bindable: true },
    { label: 'placement.maxWidthMm', value: '', bindable: true },
    { label: 'placement.minHeightMm', value: '', bindable: true },
    { label: 'placement.maxHeightMm', value: '', bindable: true },
    { label: 'placement.marginTopMm', value: '0', bindable: true },
    { label: 'placement.marginRightMm', value: '0', bindable: true },
    { label: 'placement.marginBottomMm', value: '0', bindable: true },
    { label: 'placement.marginLeftMm', value: '0', bindable: true },
    { label: 'placement.alignSelf', value: 'INHERIT', bindable: true, options: ['INHERIT', 'START', 'CENTER', 'END'] },
    { label: 'placement.fillWeight', value: '1', bindable: true },
    { label: 'placement.row', value: '0', bindable: true },
    { label: 'placement.column', value: '0', bindable: true },
    { label: 'placement.rowSpan', value: '1', bindable: true },
    { label: 'placement.columnSpan', value: '1', bindable: true },
    { label: 'placement.horizontalAlignSelf', value: 'START', bindable: true, options: ['START', 'CENTER', 'END'] },
    { label: 'placement.verticalAlignSelf', value: 'START', bindable: true, options: ['START', 'CENTER', 'END'] },
  ];
}

function paddingProps(value = '0'): InspectorProp[] {
  return [
    { label: 'padding.topMm', value, bindable: true },
    { label: 'padding.rightMm', value, bindable: true },
    { label: 'padding.bottomMm', value, bindable: true },
    { label: 'padding.leftMm', value, bindable: true },
  ];
}

function containerBoxProps(fillColor = 'surface', paddingValue = '0'): InspectorProp[] {
  return [
    { label: 'fill.color', value: fillColor, bindable: true },
    { label: 'stroke.color', value: 'hairline', bindable: true },
    { label: 'stroke.widthMm', value: '0.2', bindable: true },
    { label: 'cornerRadii.topLeftMm', value: '1', bindable: true },
    { label: 'cornerRadii.topRightMm', value: '1', bindable: true },
    { label: 'cornerRadii.bottomRightMm', value: '1', bindable: true },
    { label: 'cornerRadii.bottomLeftMm', value: '1', bindable: true },
    ...paddingProps(paddingValue),
    { label: 'clipContent', value: 'false', bindable: true, options: ['true', 'false'] },
  ];
}

function createPrototypeDesignerNode(
  kind: Exclude<NodeKind, 'canvas'>,
  ordinal: number,
  options: {
    preset?: ContainerPreset;
    assetId?: string;
    templateId?: string;
    positionMm?: { x: number; y: number };
    canvas?: Pick<CanvasProjection, 'widthMm' | 'heightMm'>;
  } = {},
): { node: DesignerNode; box: DraftBox } {
  const box = prototypeBox(kind, ordinal);
  if (options.positionMm) {
    Object.assign(
      box,
      constrainDraftGeometry(
        {
          x: options.positionMm.x - box.w / 2,
          y: options.positionMm.y - box.h / 2,
          w: box.w,
          h: box.h,
        },
        options.canvas ?? { widthMm: templateMeta.canvasMm.width, heightMm: templateMeta.canvasMm.height },
      ),
    );
  }
  const id = crypto.randomUUID().toLowerCase();
  box.nodeId = id;
  const props = commonProps(box);
  const node: DesignerNode = {
    id,
    kind,
    name: `${kindLabel(kind)} ${ordinal}`,
    detail: '原型内存节点 · 刷新即失',
    flags: [],
    props,
    children: [],
  };

  switch (kind) {
    case 'text':
      node.name = `文本 ${ordinal}`;
      node.detail = '单值文本 · 12pt · brand-sans';
      node.props.push(
        { label: 'runs[0].text', value: '双击编辑文本', bindable: true },
        { label: 'runs[0].fontRef', value: `{assetId:${assetIds.brandSans}}`, bindable: true },
        { label: 'runs[0].fontSizePt', value: '12', bindable: true },
        { label: 'runs[0].color', value: 'ink', bindable: true },
        { label: 'writingMode', value: 'HORIZONTAL_TB', bindable: true, options: ['HORIZONTAL_TB', 'VERTICAL_RL'] },
        { label: 'lineBreak', value: 'WORD', bindable: true, options: ['NONE', 'WORD', 'CHAR'] },
        { label: 'overflow', value: 'ELLIPSIS', bindable: true, options: ['VISIBLE', 'CLIP', 'ELLIPSIS', 'FAIL'] },
        { label: 'horizontalAlign', value: 'LEFT', bindable: true, options: ['LEFT', 'CENTER', 'RIGHT', 'JUSTIFY'] },
        { label: 'verticalAlign', value: 'TOP', bindable: true, options: ['TOP', 'CENTER', 'BOTTOM'] },
        ...paddingProps(),
        { label: 'shrinkToFit', value: 'false', bindable: true, options: ['true', 'false'] },
      );
      break;
    case 'image':
      node.name = `图片 ${ordinal}`;
      node.detail = options.assetId ? '已引用图片目录项 · COVER' : '尚未选择图片资源';
      node.props.push(
        { label: 'imageRef', value: options.assetId ? `{assetId:${options.assetId}}` : '未选择', bindable: true },
        { label: 'fit', value: 'COVER', bindable: true, options: ['CONTAIN', 'COVER', 'FILL', 'NONE'] },
        { label: 'sampling', value: 'LINEAR', bindable: true, options: ['LINEAR', 'NEAREST'] },
        { label: 'cornerRadiusMm', value: '2', bindable: true },
      );
      break;
    case 'rect':
      node.name = `矩形 ${ordinal}`;
      node.detail = '强调浅色填充 · 0.3mm 描边 · 2mm 圆角';
      node.props.push(
        { label: 'fill.color', value: 'accent-wash', bindable: true },
        { label: 'stroke.color', value: 'ink', bindable: true },
        { label: 'stroke.widthMm', value: '0.3', bindable: true },
        { label: 'cornerRadii.topLeftMm', value: '2', bindable: true },
        { label: 'cornerRadii.topRightMm', value: '2', bindable: true },
        { label: 'cornerRadii.bottomRightMm', value: '2', bindable: true },
        { label: 'cornerRadii.bottomLeftMm', value: '2', bindable: true },
      );
      break;
    case 'ellipse':
      node.name = `椭圆 ${ordinal}`;
      node.detail = '珊瑚色填充 · 可切换为环形';
      node.props.push(
        { label: 'fill.color', value: 'coral', bindable: true },
        { label: 'stroke.color', value: 'ink', bindable: true },
        { label: 'stroke.widthMm', value: '0.3', bindable: true },
        { label: 'innerRadiusMm', value: '0', bindable: true },
      );
      break;
    case 'line':
      node.name = `直线 ${ordinal}`;
      node.detail = '0.6mm 圆头线 · 末端箭头';
      node.props.push(
        { label: 'stroke.color', value: 'ink', bindable: true },
        { label: 'stroke.widthMm', value: '0.6', bindable: true },
        { label: 'lineCap', value: 'ROUND', bindable: true, options: ['BUTT', 'ROUND', 'SQUARE'] },
        { label: 'startArrow', value: 'NONE', bindable: true, options: ['NONE', 'ARROW', 'CIRCLE'] },
        { label: 'endArrow', value: 'ARROW', bindable: true, options: ['NONE', 'ARROW', 'CIRCLE'] },
      );
      break;
    case 'polyline':
      node.name = `折线 / 多边形 ${ordinal}`;
      node.detail = '4 个点 · 开放折线 · 圆角连接';
      node.props.push(
        { label: 'points', value: '0,12 · 9,0 · 18,10 · 24,2', bindable: false },
        { label: 'closed', value: 'false', bindable: true, options: ['true', 'false'] },
        { label: 'lineJoin', value: 'ROUND', bindable: true, options: ['MITER', 'ROUND', 'BEVEL'] },
        { label: 'fill.color', value: 'transparent', bindable: true },
        { label: 'stroke.color', value: 'ink', bindable: true },
        { label: 'stroke.widthMm', value: '0.7', bindable: true },
      );
      break;
    case 'path':
      node.name = `路径 ${ordinal}`;
      node.detail = '只读 Path Data · 可调整填充与描边';
      node.props.push(
        { label: 'pathData', value: 'M8 56 C28 4 52 4 62 34 C72 62 88 58 94 20 L94 62 L8 62 Z', bindable: false },
        { label: 'fillRule', value: 'NON_ZERO', bindable: true, options: ['NON_ZERO', 'EVEN_ODD'] },
        { label: 'fill.color', value: 'coral', bindable: true },
        { label: 'stroke.color', value: 'ink', bindable: true },
        { label: 'stroke.widthMm', value: '0.35', bindable: true },
      );
      break;
    case 'polygon':
      node.name = `其他形状 ${ordinal}`;
      node.detail = '五角星 · 参数化形状';
      node.props.push(
        { label: 'preset', value: 'STAR', bindable: true, options: ['STAR', 'TRIANGLE', 'ARROW', 'POLYGON'] },
        { label: 'pointsCount', value: '5', bindable: true },
        { label: 'innerRadiusMm', value: '5', bindable: true },
        { label: 'fill.color', value: 'accent-wash', bindable: true },
        { label: 'stroke.color', value: 'ink', bindable: true },
        { label: 'stroke.widthMm', value: '0.3', bindable: true },
      );
      break;
    case 'qrCode':
      node.name = `二维码 ${ordinal}`;
      node.detail = '链接内容 · M 级纠错 · 1mm 静区';
      node.props.push(
        { label: 'content', value: 'https://example.cn/p/sku-2026', bindable: true },
        { label: 'errorCorrectionLevel', value: 'M', bindable: true, options: ['L', 'M', 'Q', 'H'] },
        { label: 'quietZoneMm', value: '1', bindable: true },
        { label: 'foregroundColor', value: 'ink', bindable: true },
        { label: 'backgroundColor', value: '#FFFFFF', bindable: true },
      );
      break;
    case 'barcode':
      node.name = `条形码 ${ordinal}`;
      node.detail = 'EAN-13 · 显示码文 · 0.35mm 条宽';
      node.props.push(
        { label: 'code', value: '6901234567892', bindable: true },
        { label: 'symbology', value: 'EAN13', bindable: true, options: ['EAN13', 'CODE128', 'UPC_A'] },
        { label: 'showText', value: 'true', bindable: true, options: ['true', 'false'] },
        { label: 'barWidthMm', value: '0.35', bindable: true },
        { label: 'foregroundColor', value: 'ink', bindable: true },
        { label: 'backgroundColor', value: '#FFFFFF', bindable: true },
      );
      break;
    case 'group':
      node.props = node.props.map((prop) => prop.label === 'placement.widthMode' || prop.label === 'placement.heightMode'
        ? { ...prop, value: 'HUG_CONTENT', options: ['HUG_CONTENT'] }
        : prop);
      node.props.push(
        { label: 'layoutMode', value: 'FREE', bindable: false, options: ['FREE'] },
      );
      break;
    case 'frame':
      node.props.push(
        { label: 'layoutMode', value: 'FREE', bindable: false, options: ['FREE'] },
        ...containerBoxProps('surface'),
      );
      break;
    case 'stack':
      node.props.push(
        { label: 'direction', value: 'VERTICAL', bindable: true, options: ['HORIZONTAL', 'VERTICAL'] },
        { label: 'gapMm', value: '2', bindable: true },
        { label: 'mainAlign', value: 'START', bindable: true, options: ['START', 'CENTER', 'END', 'SPACE_BETWEEN'] },
        { label: 'crossAlign', value: 'STRETCH', bindable: true, options: ['START', 'CENTER', 'END', 'STRETCH'] },
        ...containerBoxProps('surface'),
      );
      break;
    case 'grid':
      node.props.push(
        { label: 'columns', value: '1*, 1*', bindable: true },
        { label: 'rows', value: 'auto', bindable: true },
        { label: 'columnGapMm', value: '2', bindable: true },
        { label: 'rowGapMm', value: '2', bindable: true },
        ...containerBoxProps('surface'),
      );
      break;
    case 'repeat':
      node.loopId = crypto.randomUUID().toLowerCase();
      node.props.push(
        { label: 'items', value: '', bindable: false },
        { label: 'absentPolicy', value: 'EMPTY', bindable: false, options: ['ERROR', 'EMPTY'] },
        { label: 'itemLayout.kind', value: 'STACK', bindable: false, options: ['STACK', 'GRID'] },
        { label: 'itemLayout.direction', value: 'ROW', bindable: false, options: ['ROW', 'COLUMN'] },
        { label: 'itemLayout.gapMm', value: '1.5', bindable: false },
        { label: 'itemLayout.columns', value: '2', bindable: false },
        { label: 'itemLayout.columnGapMm', value: '1', bindable: false },
        { label: 'itemLayout.rowGapMm', value: '1', bindable: false },
        { label: 'instanceLayout.kind', value: 'STACK', bindable: false, options: ['STACK', 'GRID'] },
        { label: 'instanceLayout.direction', value: 'ROW', bindable: false, options: ['ROW', 'COLUMN'] },
        { label: 'instanceLayout.gapMm', value: '1.5', bindable: false },
        { label: 'instanceLayout.columns', value: '3', bindable: false },
        { label: 'instanceLayout.columnGapMm', value: '1.5', bindable: false },
        { label: 'instanceLayout.rowGapMm', value: '1.5', bindable: false },
      );
      break;
    case 'conditional':
      node.props.push(
        { label: 'condition', value: 'literal(true)', bindable: false },
        { label: 'absentPolicy', value: 'FALSE', bindable: false, options: ['ERROR', 'FALSE'] },
      );
      break;
    case 'templateUse':
      node.useId = crypto.randomUUID().toLowerCase();
      node.name = options.templateId === childTemplateIds.tagPill
        ? `标签胶囊调用 ${ordinal}`
        : options.templateId === childTemplateIds.offerCard
          ? `优惠信息卡调用 ${ordinal}`
          : options.templateId
            ? `嵌套模板容器 ${ordinal}`
            : `嵌套模板容器 ${ordinal}`;
      node.detail = options.templateId
        ? '同工作区 · current revision · 原型'
        : '先选择属性，再选择模板';
      node.props.push(
        { label: 'templateRef.templateId', value: options.templateId ?? '', bindable: false },
        { label: 'contextSelector', value: options.templateId ? 'context(invocation, /brand)' : '', bindable: false },
        { label: 'contextAbsentPolicy', value: 'ERROR', bindable: false, options: ['ERROR', 'SKIP'] },
        { label: 'fills', value: '[]', bindable: false },
      );
      break;
  }
  return { node, box };
}

function kindLabel(kind: Exclude<NodeKind, 'canvas'>): string {
  const labels: Record<Exclude<NodeKind, 'canvas'>, string> = {
    group: '自由分组', frame: '框架', stack: '堆叠', grid: '网格', text: '文本', image: '图片',
    rect: '矩形', ellipse: '椭圆', line: '直线', polygon: '其他形状', polyline: '折线 / 多边形',
    path: '路径', qrCode: '二维码', barcode: '条形码', repeat: '循环容器', conditional: '条件容器',
    templateUse: '嵌套模板容器',
  };
  return labels[kind];
}

function insertChild(tree: DesignerNode, parentId: string, child: DesignerNode): DesignerNode {
  if (tree.id === parentId) return { ...tree, children: [...tree.children, child] };
  return { ...tree, children: tree.children.map((candidate) => insertChild(candidate, parentId, child)) };
}

function replaceChildren(tree: DesignerNode, parentId: string, children: DesignerNode[]): DesignerNode {
  if (tree.id === parentId) return { ...tree, children };
  return { ...tree, children: tree.children.map((child) => replaceChildren(child, parentId, children)) };
}

function reorderSiblingList(
  children: DesignerNode[],
  selectedIds: ReadonlySet<string>,
  operation: LayerOrderOperation,
): { children: DesignerNode[]; changed: boolean } {
  const ordered = [...children];
  if (operation === 'front') {
    ordered.splice(0, ordered.length,
      ...ordered.filter((child) => !selectedIds.has(child.id)),
      ...ordered.filter((child) => selectedIds.has(child.id)));
  } else if (operation === 'back') {
    ordered.splice(0, ordered.length,
      ...ordered.filter((child) => selectedIds.has(child.id)),
      ...ordered.filter((child) => !selectedIds.has(child.id)));
  } else if (operation === 'forward') {
    for (let index = ordered.length - 2; index >= 0; index -= 1) {
      if (selectedIds.has(ordered[index]!.id) && !selectedIds.has(ordered[index + 1]!.id)) {
        [ordered[index], ordered[index + 1]] = [ordered[index + 1]!, ordered[index]!];
      }
    }
  } else {
    for (let index = 1; index < ordered.length; index += 1) {
      if (selectedIds.has(ordered[index]!.id) && !selectedIds.has(ordered[index - 1]!.id)) {
        [ordered[index - 1], ordered[index]] = [ordered[index]!, ordered[index - 1]!];
      }
    }
  }
  return {
    children: ordered,
    changed: ordered.some((child, index) => child.id !== children[index]?.id),
  };
}

function reorderSelectedChildren(
  node: DesignerNode,
  selectedIds: ReadonlySet<string>,
  operation: LayerOrderOperation,
): { node: DesignerNode; changed: boolean } {
  let descendantChanged = false;
  const descendantResults = node.children.map((child) => {
    const result = reorderSelectedChildren(child, selectedIds, operation);
    descendantChanged = descendantChanged || result.changed;
    return result.node;
  });
  const reordered = reorderSiblingList(descendantResults, selectedIds, operation);
  const changed = descendantChanged || reordered.changed;
  return { node: changed ? { ...node, children: reordered.children } : node, changed };
}

export function layerOrderCapabilities(
  tree: DesignerNode,
  selectedNodeIds: readonly string[],
): LayerOrderCapabilities {
  const selectedIds = new Set(selectedNodeIds.filter((id) => id !== nodeIds.canvas));
  let forward = false;
  let backward = false;
  const visit = (node: DesignerNode) => {
    for (let index = 0; index < node.children.length - 1; index += 1) {
      const currentSelected = selectedIds.has(node.children[index]!.id);
      const nextSelected = selectedIds.has(node.children[index + 1]!.id);
      if (currentSelected && !nextSelected) forward = true;
      if (!currentSelected && nextSelected) backward = true;
    }
    node.children.forEach(visit);
  };
  visit(tree);
  return { front: forward, forward, backward, back: backward };
}

function subtreeContains(node: DesignerNode, id: string): boolean {
  return node.id === id || node.children.some((child) => subtreeContains(child, id));
}

function removeSelectedNodes(tree: DesignerNode, ids: Set<string>, extracted: DesignerNode[]): DesignerNode {
  const children: DesignerNode[] = [];
  for (const child of tree.children) {
    if (ids.has(child.id)) {
      extracted.push(child);
    } else {
      children.push(removeSelectedNodes(child, ids, extracted));
    }
  }
  return { ...tree, children };
}

function descendantIds(node: DesignerNode): string[] {
  return [node.id, ...node.children.flatMap(descendantIds)];
}

function selectionBox(boxes: DraftBox[], ids: string[], fallback: DraftBox): DraftBox {
  const hits = boxes.filter((box) => ids.includes(box.nodeId));
  if (hits.length === 0) return fallback;
  const left = Math.min(...hits.map((box) => box.x));
  const top = Math.min(...hits.map((box) => box.y));
  const right = Math.max(...hits.map((box) => box.x + box.w));
  const bottom = Math.max(...hits.map((box) => box.y + box.h));
  return { ...fallback, x: Math.max(0, left - 2), y: Math.max(0, top - 2), w: right - left + 4, h: bottom - top + 4 };
}

function boxesInTreeOrder(boxes: DraftBox[], tree: DesignerNode): DraftBox[] {
  const order = new Map(flattenDesignerTree(tree).map((node, index) => [node.id, index]));
  return [...boxes].sort((left, right) => (order.get(left.nodeId) ?? Number.MAX_SAFE_INTEGER) - (order.get(right.nodeId) ?? Number.MAX_SAFE_INTEGER));
}

function clampNumber(value: number, minimum: number, maximum: number): number {
  return Math.min(maximum, Math.max(minimum, value));
}

function roundMillimetres(value: number): number {
  return Math.round(value * 100) / 100;
}

function constrainDraftGeometry(
  box: Pick<DraftBox, 'x' | 'y' | 'w' | 'h'>,
  canvas: Pick<CanvasProjection, 'widthMm' | 'heightMm'>,
): Pick<DraftBox, 'x' | 'y' | 'w' | 'h'> {
  const w = clampNumber(box.w, 0.5, canvas.widthMm);
  const h = clampNumber(box.h, 0.5, canvas.heightMm);
  return {
    x: roundMillimetres(clampNumber(box.x, 0, canvas.widthMm - w)),
    y: roundMillimetres(clampNumber(box.y, 0, canvas.heightMm - h)),
    w: roundMillimetres(w),
    h: roundMillimetres(h),
  };
}

function syncGeometryProps(
  node: DesignerNode,
  geometryByNodeId: ReadonlyMap<string, Pick<DraftBox, 'x' | 'y' | 'w' | 'h'>>,
): DesignerNode {
  const geometry = geometryByNodeId.get(node.id);
  const values: Record<string, string> | null = geometry
    ? {
        xMm: String(geometry.x),
        yMm: String(geometry.y),
        widthMm: String(geometry.w),
        heightMm: String(geometry.h),
      }
    : null;
  return {
    ...node,
    props: values
      ? node.props.map((prop) => Object.hasOwn(values, prop.label) ? { ...prop, value: values[prop.label]! } : prop)
      : node.props,
    children: node.children.map((child) => syncGeometryProps(child, geometryByNodeId)),
  };
}

function replacePropValues(node: DesignerNode, values: Readonly<Record<string, string>>): DesignerNode {
  return {
    ...node,
    props: node.props.map((prop) => Object.hasOwn(values, prop.label) ? { ...prop, value: values[prop.label]! } : prop),
  };
}

function upsertPropValues(node: DesignerNode, values: Readonly<Record<string, string>>): DesignerNode {
  const existing = new Set(node.props.map((prop) => prop.label));
  const updated = replacePropValues(node, values);
  return {
    ...updated,
    props: [
      ...updated.props,
      ...Object.entries(values)
        .filter(([label]) => !existing.has(label))
        .map(([label, value]): InspectorProp => ({ label, value, bindable: false })),
    ],
  };
}

function prepareRepeatChild(node: DesignerNode, repeat: DesignerNode): DesignerNode {
  const loopId = repeat.loopId ?? repeat.id;
  const prepared = upsertPropValues(node, { 'placement.type': 'PACK' });
  return prepared.kind === 'templateUse'
    ? upsertPropValues(prepared, { contextSelector: `loop(${loopId}), pointer ""` })
    : prepared;
}

function createStackDemo(
  preset: StackDemoPreset,
  ordinal: number,
): { node: DesignerNode; boxes: DraftBox[]; title: string } {
  const definitions: Record<StackDemoPreset, {
    title: string;
    detail: string;
    box: Pick<DraftBox, 'x' | 'y' | 'w' | 'h'>;
    direction: 'VERTICAL' | 'HORIZONTAL';
    gapMm: number;
    mainAlign: 'START' | 'CENTER' | 'SPACE_BETWEEN';
    crossAlign: 'STRETCH' | 'CENTER';
    strokeMm: number;
    padding: { top: number; right: number; bottom: number; left: number };
    children: Array<{
      kind: Extract<NodeKind, 'text' | 'rect' | 'ellipse'>;
      name: string;
      text?: string;
      w: number;
      h: number;
      placement?: Record<string, string>;
    }>;
  }> = {
    'vertical-start': {
      title: '纵向卡片列 Demo',
      detail: 'VERTICAL · START · STRETCH · 固定尺寸子项',
      box: { x: 8, y: 6, w: 74, h: 42 },
      direction: 'VERTICAL',
      gapMm: 3,
      mainAlign: 'START',
      crossAlign: 'STRETCH',
      strokeMm: 0.4,
      padding: { top: 4, right: 6, bottom: 4, left: 6 },
      children: [
        { kind: 'text', name: '标题行', text: '春季新品', w: 24, h: 7 },
        { kind: 'rect', name: '内容色块', w: 34, h: 9 },
        { kind: 'text', name: '行动说明', text: '立即查看详情', w: 22, h: 8 },
      ],
    },
    'horizontal-center': {
      title: '横向居中栏 Demo',
      detail: 'HORIZONTAL · CENTER / CENTER · 固定尺寸子项',
      box: { x: 5, y: 13, w: 80, h: 28 },
      direction: 'HORIZONTAL',
      gapMm: 2,
      mainAlign: 'CENTER',
      crossAlign: 'CENTER',
      strokeMm: 0.3,
      padding: { top: 4, right: 4, bottom: 4, left: 4 },
      children: [
        { kind: 'ellipse', name: '状态圆点', w: 14, h: 8 },
        { kind: 'text', name: '状态正文', text: '实时布局已连接', w: 24, h: 12 },
        { kind: 'rect', name: '状态操作', w: 10, h: 6 },
      ],
    },
    'horizontal-between': {
      title: '横向两端分布 Demo',
      detail: 'HORIZONTAL · SPACE_BETWEEN · STRETCH · 固定尺寸子项',
      box: { x: 5, y: 14, w: 80, h: 26 },
      direction: 'HORIZONTAL',
      gapMm: 1.5,
      mainAlign: 'SPACE_BETWEEN',
      crossAlign: 'STRETCH',
      strokeMm: 0.25,
      padding: { top: 3, right: 4, bottom: 3, left: 4 },
      children: [
        { kind: 'rect', name: '左侧标记', w: 10, h: 8 },
        { kind: 'text', name: '中间信息', text: 'SPACE BETWEEN', w: 14, h: 8 },
        { kind: 'rect', name: '右侧操作', w: 8, h: 8 },
      ],
    },
    'horizontal-fill': {
      title: '横向权重填充 Demo',
      detail: 'HORIZONTAL · FILL 1:2 · max freeze · signed margin / alignSelf',
      box: { x: 5, y: 13, w: 80, h: 28 },
      direction: 'HORIZONTAL',
      gapMm: 2,
      mainAlign: 'START',
      crossAlign: 'CENTER',
      strokeMm: 0,
      padding: { top: 4, right: 4, bottom: 4, left: 4 },
      children: [
        {
          kind: 'rect',
          name: '固定前缀',
          w: 10,
          h: 8,
          placement: {
            'placement.marginRightMm': '2',
            'placement.alignSelf': 'START',
          },
        },
        {
          kind: 'text',
          name: '上限填充项',
          text: 'FILL × 1',
          w: 3,
          h: 10,
          placement: {
            'placement.widthMode': 'FILL',
            'placement.fillWeight': '1',
            'placement.maxWidthMm': '14',
            'placement.marginLeftMm': '1',
            'placement.marginRightMm': '1',
            'placement.alignSelf': 'CENTER',
          },
        },
        {
          kind: 'rect',
          name: '余量填充项',
          w: 3,
          h: 6,
          placement: {
            'placement.widthMode': 'FILL',
            'placement.fillWeight': '2',
            'placement.marginLeftMm': '1',
            'placement.marginRightMm': '-1',
            'placement.alignSelf': 'END',
          },
        },
      ],
    },
  };
  const definition = definitions[preset];
  const stack = createPrototypeDesignerNode('stack', ordinal, { preset: 'stack' });
  stack.box = { ...stack.box, ...definition.box, label: definition.title };
  stack.node = replacePropValues(stack.node, {
    direction: definition.direction,
    gapMm: String(definition.gapMm),
    mainAlign: definition.mainAlign,
    crossAlign: definition.crossAlign,
    'stroke.widthMm': String(definition.strokeMm),
    'padding.topMm': String(definition.padding.top),
    'padding.rightMm': String(definition.padding.right),
    'padding.bottomMm': String(definition.padding.bottom),
    'padding.leftMm': String(definition.padding.left),
  });
  stack.node.name = definition.title;
  stack.node.detail = definition.detail;

  const createdChildren = definition.children.map((childDefinition, index) => {
    const created = createPrototypeDesignerNode(childDefinition.kind, ordinal + index + 1);
    created.box = {
      ...created.box,
      x: definition.box.x,
      y: definition.box.y,
      w: childDefinition.w,
      h: childDefinition.h,
      label: childDefinition.name,
    };
    created.node.name = childDefinition.name;
    created.node.detail = childDefinition.placement?.['placement.widthMode'] === 'FILL'
      ? `Stack 主轴填充子项 · 权重 ${childDefinition.placement['placement.fillWeight'] ?? '1'}`
      : `Stack 固定尺寸子项 · ${childDefinition.w}×${childDefinition.h}mm`;
    if (childDefinition.placement) created.node = replacePropValues(created.node, childDefinition.placement);
    if (childDefinition.text) created.node = replacePropValues(created.node, { 'runs[0].text': childDefinition.text });
    return created;
  });
  stack.node.children = createdChildren.map((created) => created.node);
  const boxes = [stack.box, ...createdChildren.map((created) => created.box)];
  const geometryByNodeId = new Map(boxes.map((box) => [box.nodeId, { x: box.x, y: box.y, w: box.w, h: box.h }] as const));
  stack.node = syncGeometryProps(stack.node, geometryByNodeId);
  return { node: stack.node, boxes, title: definition.title };
}

function createAbsoluteDemo(
  preset: AbsoluteDemoPreset,
  ordinal: number,
): { node: DesignerNode; boxes: DraftBox[]; title: string } {
  if (preset === 'group-hug') {
    const group = createPrototypeDesignerNode('group', ordinal, { preset: 'group' });
    group.node.name = '自由分组边界 Demo';
    group.node.detail = 'HUG_CONTENT · 子项绝对坐标并集 · 无自身外观';
    group.box = { ...group.box, x: 13, y: 9, w: 50, h: 22, label: group.node.name };

    const badge = createPrototypeDesignerNode('rect', ordinal + 1);
    badge.node.name = '左上徽标';
    badge.node.detail = 'Group 局部坐标 5, 4mm';
    badge.box = { ...badge.box, x: 5, y: 4, w: 18, h: 9, label: badge.node.name };

    const copy = createPrototypeDesignerNode('text', ordinal + 2);
    copy.node.name = '右下说明';
    copy.node.detail = '移动后分组边界实时重算';
    copy.node = replacePropValues(copy.node, { 'runs[0].text': '拖动我，观察 Group 自动包围' });
    copy.box = { ...copy.box, x: 28, y: 14, w: 27, h: 12, label: copy.node.name };

    group.node.children = [badge.node, copy.node];
    const boxes = [group.box, badge.box, copy.box];
    const geometryByNodeId = new Map(boxes.map((box) => [box.nodeId, { x: box.x, y: box.y, w: box.w, h: box.h }] as const));
    group.node = syncGeometryProps(group.node, geometryByNodeId);
    return { node: group.node, boxes, title: group.node.name };
  }

  const frame = createPrototypeDesignerNode('frame', ordinal, { preset: 'frame' });
  frame.node.name = '框架内容区 Demo';
  frame.node.detail = '固定 LayoutBox · ContentBox · 绝对定位子项';
  frame.box = { ...frame.box, x: 9, y: 7, w: 72, h: 40, label: frame.node.name };
  frame.node = replacePropValues(frame.node, {
    'stroke.widthMm': '1',
    'padding.topMm': '4',
    'padding.rightMm': '5',
    'padding.bottomMm': '6',
    'padding.leftMm': '7',
    'fill.color': 'accent-wash',
    clipContent: 'true',
  });

  const title = createPrototypeDesignerNode('text', ordinal + 1);
  title.node.name = '内容区标题';
  title.node.detail = '相对 ContentBox 0, 0mm';
  title.node = replacePropValues(title.node, { 'runs[0].text': 'CONTENT BOX', 'runs[0].fontSizePt': '8' });
  title.box = { ...title.box, x: 0, y: 0, w: 28, h: 8, label: title.node.name };

  const card = createPrototypeDesignerNode('rect', ordinal + 2);
  card.node.name = '局部定位卡片';
  card.node.detail = '相对 ContentBox 24, 13mm';
  card.box = { ...card.box, x: 24, y: 13, w: 32, h: 15, label: card.node.name };

  frame.node.children = [title.node, card.node];
  const boxes = [frame.box, title.box, card.box];
  const geometryByNodeId = new Map(boxes.map((box) => [box.nodeId, { x: box.x, y: box.y, w: box.w, h: box.h }] as const));
  frame.node = syncGeometryProps(frame.node, geometryByNodeId);
  return { node: frame.node, boxes, title: frame.node.name };
}

function createGridDemo(
  preset: GridDemoPreset,
  ordinal: number,
): { node: DesignerNode; boxes: DraftBox[]; title: string } {
  const definitions: Record<GridDemoPreset, {
    title: string;
    detail: string;
    box: Pick<DraftBox, 'x' | 'y' | 'w' | 'h'>;
    columns: string;
    rows: string;
    columnGapMm: number;
    rowGapMm: number;
    strokeMm: number;
    padding: { top: number; right: number; bottom: number; left: number };
    children: Array<{
      kind: Extract<NodeKind, 'text' | 'rect' | 'ellipse'>;
      name: string;
      text?: string;
      w: number;
      h: number;
      placement: Record<string, string>;
    }>;
  }> = {
    'fraction-cards': {
      title: '比例卡片网格 Demo',
      detail: '3 列 · 1* / 2* / 1* · 跨列标题 · FILL 卡片',
      box: { x: 6, y: 6, w: 78, h: 42 },
      columns: '1*, 2*, 1*',
      rows: '9, 1*',
      columnGapMm: 2,
      rowGapMm: 2,
      strokeMm: 0.35,
      padding: { top: 3, right: 4, bottom: 3, left: 4 },
      children: [
        { kind: 'text', name: '跨列标题', text: 'FRACTION TRACKS', w: 24, h: 6, placement: { 'placement.column': '0', 'placement.row': '0', 'placement.columnSpan': '3', 'placement.widthMode': 'FILL', 'placement.heightMode': 'FILL' } },
        { kind: 'rect', name: '一份卡片', w: 8, h: 8, placement: { 'placement.column': '0', 'placement.row': '1', 'placement.widthMode': 'FILL', 'placement.heightMode': 'FILL' } },
        { kind: 'text', name: '两份主卡片', text: '2* 主内容区', w: 12, h: 8, placement: { 'placement.column': '1', 'placement.row': '1', 'placement.widthMode': 'FILL', 'placement.heightMode': 'FILL', 'placement.marginLeftMm': '1', 'placement.marginRightMm': '1' } },
        { kind: 'ellipse', name: '居中状态', w: 10, h: 10, placement: { 'placement.column': '2', 'placement.row': '1', 'placement.horizontalAlignSelf': 'CENTER', 'placement.verticalAlignSelf': 'CENTER' } },
      ],
    },
    'auto-span': {
      title: 'AUTO 跨轨约束 Demo',
      detail: 'auto / auto / 1* · 短跨度先求解 · 跨轨 deficit 均分',
      box: { x: 5, y: 7, w: 80, h: 40 },
      columns: 'auto, auto, 1*',
      rows: '9, 1*',
      columnGapMm: 2,
      rowGapMm: 2,
      strokeMm: 0.3,
      padding: { top: 2, right: 3, bottom: 2, left: 3 },
      children: [
        { kind: 'text', name: 'AUTO 指标', text: '库存 128', w: 16, h: 7, placement: { 'placement.column': '0', 'placement.row': '0', 'placement.marginRightMm': '1' } },
        { kind: 'rect', name: 'AUTO 标签', w: 12, h: 6, placement: { 'placement.column': '1', 'placement.row': '0', 'placement.horizontalAlignSelf': 'CENTER', 'placement.verticalAlignSelf': 'CENTER' } },
        { kind: 'text', name: '双 AUTO 跨列说明', text: '跨两条 AUTO 轨道的贡献', w: 42, h: 9, placement: { 'placement.column': '0', 'placement.row': '1', 'placement.columnSpan': '2', 'placement.verticalAlignSelf': 'CENTER' } },
        { kind: 'rect', name: '剩余比例区', w: 8, h: 8, placement: { 'placement.column': '2', 'placement.row': '0', 'placement.rowSpan': '2', 'placement.widthMode': 'FILL', 'placement.heightMode': 'FILL', 'placement.marginLeftMm': '2', 'placement.maxWidthMm': '20' } },
      ],
    },
    'alignment-fill': {
      title: '单元对齐与填充 Demo',
      detail: '2×2 · signed margin · START/CENTER/END · FILL + max clamp',
      box: { x: 5, y: 7, w: 80, h: 40 },
      columns: '1*, 1*',
      rows: '1*, 1*',
      columnGapMm: 3,
      rowGapMm: 2,
      strokeMm: 0,
      padding: { top: 3, right: 4, bottom: 3, left: 4 },
      children: [
        { kind: 'rect', name: '末端固定项', w: 12, h: 7, placement: { 'placement.column': '0', 'placement.row': '0', 'placement.marginTopMm': '1', 'placement.marginRightMm': '2', 'placement.horizontalAlignSelf': 'END', 'placement.verticalAlignSelf': 'CENTER' } },
        { kind: 'text', name: '限宽填充项', text: 'FILL max 24', w: 6, h: 6, placement: { 'placement.column': '1', 'placement.row': '0', 'placement.widthMode': 'FILL', 'placement.heightMode': 'FILL', 'placement.maxWidthMm': '24', 'placement.marginLeftMm': '-1', 'placement.marginRightMm': '2', 'placement.marginTopMm': '1', 'placement.marginBottomMm': '1' } },
        { kind: 'text', name: '跨列居中项', text: 'columnSpan = 2', w: 26, h: 7, placement: { 'placement.column': '0', 'placement.row': '1', 'placement.columnSpan': '2', 'placement.horizontalAlignSelf': 'CENTER', 'placement.verticalAlignSelf': 'CENTER' } },
      ],
    },
  };
  const definition = definitions[preset];
  const grid = createPrototypeDesignerNode('grid', ordinal, { preset: 'grid' });
  grid.box = { ...grid.box, ...definition.box, label: definition.title };
  grid.node = replacePropValues(grid.node, {
    columns: definition.columns,
    rows: definition.rows,
    columnGapMm: String(definition.columnGapMm),
    rowGapMm: String(definition.rowGapMm),
    'stroke.widthMm': String(definition.strokeMm),
    'padding.topMm': String(definition.padding.top),
    'padding.rightMm': String(definition.padding.right),
    'padding.bottomMm': String(definition.padding.bottom),
    'padding.leftMm': String(definition.padding.left),
  });
  grid.node.name = definition.title;
  grid.node.detail = definition.detail;

  const createdChildren = definition.children.map((childDefinition, index) => {
    const created = createPrototypeDesignerNode(childDefinition.kind, ordinal + index + 1);
    created.box = {
      ...created.box,
      x: definition.box.x,
      y: definition.box.y,
      w: childDefinition.w,
      h: childDefinition.h,
      label: childDefinition.name,
    };
    created.node.name = childDefinition.name;
    created.node.detail = `Grid 单元 · row ${childDefinition.placement['placement.row'] ?? '0'} · column ${childDefinition.placement['placement.column'] ?? '0'}`;
    created.node = replacePropValues(created.node, childDefinition.placement);
    if (childDefinition.text) created.node = replacePropValues(created.node, { 'runs[0].text': childDefinition.text });
    return created;
  });
  grid.node.children = createdChildren.map((created) => created.node);
  const boxes = [grid.box, ...createdChildren.map((created) => created.box)];
  const geometryByNodeId = new Map(boxes.map((draftBox) => [draftBox.nodeId, { x: draftBox.x, y: draftBox.y, w: draftBox.w, h: draftBox.h }] as const));
  grid.node = syncGeometryProps(grid.node, geometryByNodeId);
  return { node: grid.node, boxes, title: definition.title };
}

function createRepeatDemo(
  preset: RepeatDemoPreset,
  ordinal: number,
): { node: DesignerNode; boxes: DraftBox[]; title: string } {
  const repeat = createPrototypeDesignerNode('repeat', ordinal, { preset: 'repeat' });
  repeat.box = { ...repeat.box, x: 7, y: 9, w: 76, h: 36, tone: 'chip' };

  const configure = (values: Record<string, string>) => {
    repeat.node = upsertPropValues(repeat.node, values);
  };
  const attachBinding = (node: DesignerNode, source: string): DesignerNode => ({
    ...node,
    props: node.props.map((property) => property.label === 'runs[0].text'
      ? {
          ...property,
          binding: {
            id: crypto.randomUUID().toLowerCase(),
            ref: 'runs[0].text',
            source,
            note: 'Repeat Demo · 显式 loop domain',
          },
        }
      : property),
  });

  if (preset === 'reference-offers') {
    repeat.node.name = '优惠卡循环 Demo';
    repeat.node.detail = 'ReferenceValue[] · exact offer-card@v2 · 2 列实例网格';
    configure({
      items: 'context(invocation, /offers)',
      absentPolicy: 'ERROR',
      'itemLayout.kind': 'STACK',
      'itemLayout.direction': 'COLUMN',
      'itemLayout.gapMm': '0',
      'instanceLayout.kind': 'GRID',
      'instanceLayout.columns': '2',
      'instanceLayout.columnGapMm': '2',
      'instanceLayout.rowGapMm': '2',
    });
    const card = createPrototypeDesignerNode('templateUse', ordinal + 1, { templateId: childTemplateIds.offerCard });
    card.node.name = '优惠信息卡 · 单项内容';
    card.node.detail = '与 offer-card@v2 精确兼容 · 当前循环项作为上下文';
    card.node = prepareRepeatChild(card.node, repeat.node);
    card.box = { ...card.box, x: 0, y: 0, w: 30, h: 12, label: card.node.name };
    repeat.node.children = [card.node];
    const boxes = [repeat.box, card.box];
    repeat.node = syncGeometryProps(repeat.node, new Map(boxes.map((box) => [box.nodeId, box])));
    return { node: repeat.node, boxes, title: repeat.node.name };
  }

  const label = createPrototypeDesignerNode('text', ordinal + 1);
  label.node.name = preset === 'repair-state' ? '旧标量标签内容' : '当前标签文字';
  label.node.detail = '读取当前循环项 /value · PACK';
  label.node = attachBinding(label.node, `context(loop ${repeat.node.loopId}, /value)`);
  label.node = prepareRepeatChild(label.node, repeat.node);
  label.node = replacePropValues(label.node, { 'runs[0].text': '新品', 'runs[0].fontSizePt': '6.5' });
  label.box = { ...label.box, x: 0, y: 0, w: preset === 'repair-state' ? 22 : 11, h: 6, label: label.node.name };

  if (preset === 'repair-state') {
    repeat.node.name = '数据源切换待修复 Demo';
    repeat.node.detail = '源已切换为 offer-card@v2，但单项内容仍读取标量 /value';
    repeat.box = { ...repeat.box, h: 28, label: repeat.node.name };
    configure({
      items: 'context(invocation, /offers)',
      absentPolicy: 'ERROR',
      'itemLayout.kind': 'STACK',
      'itemLayout.direction': 'ROW',
      'instanceLayout.kind': 'STACK',
      'instanceLayout.direction': 'COLUMN',
      'instanceLayout.gapMm': '2',
    });
    repeat.node.children = [label.node];
    const boxes = [repeat.box, label.box];
    repeat.node = syncGeometryProps(repeat.node, new Map(boxes.map((box) => [box.nodeId, box])));
    return { node: repeat.node, boxes, title: repeat.node.name };
  }

  repeat.node.name = '标量标签循环 Demo';
  repeat.node.detail = 'list<text> · 直接设计单项 · 3 列实例网格';
  repeat.box = { ...repeat.box, label: repeat.node.name };
  configure({
    items: 'context(invocation, /tags)',
    absentPolicy: 'EMPTY',
    'itemLayout.kind': 'STACK',
    'itemLayout.direction': 'ROW',
    'itemLayout.gapMm': '1.5',
    'instanceLayout.kind': 'GRID',
    'instanceLayout.columns': '3',
    'instanceLayout.columnGapMm': '1.5',
    'instanceLayout.rowGapMm': '1.5',
  });
  const marker = createPrototypeDesignerNode('rect', ordinal + 2);
  marker.node.name = '标签尾标';
  marker.node.detail = '直接设计的装饰元素 · PACK';
  marker.node = prepareRepeatChild(marker.node, repeat.node);
  marker.node = replacePropValues(marker.node, {
    'fill.color': 'coral',
    'stroke.widthMm': '0',
    'cornerRadii.topLeftMm': '3',
    'cornerRadii.topRightMm': '3',
    'cornerRadii.bottomRightMm': '3',
    'cornerRadii.bottomLeftMm': '3',
  });
  marker.box = { ...marker.box, x: 0, y: 0, w: 5, h: 6, label: marker.node.name };
  repeat.node.children = [label.node, marker.node];
  const boxes = [repeat.box, label.box, marker.box];
  repeat.node = syncGeometryProps(repeat.node, new Map(boxes.map((box) => [box.nodeId, box])));
  return { node: repeat.node, boxes, title: repeat.node.name };
}

function createConditionalDemo(
  preset: ConditionalDemoPreset,
  ordinal: number,
): { node: DesignerNode; boxes: DraftBox[]; title: string; selectedNodeId: string } {
  const stack = createPrototypeDesignerNode('stack', ordinal, { preset: 'stack' });
  stack.node.name = '条件剪枝与重排 Demo';
  stack.node.detail = '横向 Stack · Conditional 在布局前加入或剪枝';
  stack.node = replacePropValues(stack.node, {
    direction: 'HORIZONTAL',
    gapMm: '2',
    mainAlign: 'START',
    crossAlign: 'CENTER',
    'stroke.widthMm': '0.3',
    'padding.topMm': '4',
    'padding.rightMm': '4',
    'padding.bottomMm': '4',
    'padding.leftMm': '4',
  });
  stack.box = { ...stack.box, x: 5, y: 14, w: 80, h: 26, label: stack.node.name };

  const prefix = createPrototypeDesignerNode('rect', ordinal + 1);
  prefix.node.name = '常驻前项';
  prefix.node.detail = '无论条件结果如何都参与 Stack';
  prefix.node = replacePropValues(prefix.node, {
    'fill.color': 'accent-wash',
    'stroke.widthMm': '0.2',
    'placement.alignSelf': 'CENTER',
  });
  prefix.box = { ...prefix.box, x: stack.box.x, y: stack.box.y, w: 12, h: 10, label: prefix.node.name };

  const conditional = createPrototypeDesignerNode('conditional', ordinal + 2, { preset: 'conditional' });
  conditional.node.name = '会员优惠条件';
  conditional.node.detail = 'optional boolean · true 降低为无外观 Frame · false 整树剪枝';
  conditional.node = replacePropValues(conditional.node, {
    condition: 'context(invocation, /memberEligible)',
    absentPolicy: 'FALSE',
    render: 'true',
    'placement.alignSelf': 'CENTER',
  });
  conditional.box = { ...conditional.box, x: stack.box.x, y: stack.box.y, w: 28, h: 12, tone: 'ghost', label: conditional.node.name };

  const branchSurface = createPrototypeDesignerNode('rect', ordinal + 3);
  branchSurface.node.name = '优惠底板';
  branchSurface.node.detail = 'true 分支 · Conditional 局部坐标 0,0';
  branchSurface.node = replacePropValues(branchSurface.node, {
    'fill.color': '#FFF1EC',
    'stroke.color': 'coral',
    'stroke.widthMm': '0.25',
    'cornerRadii.topLeftMm': '2',
    'cornerRadii.topRightMm': '2',
    'cornerRadii.bottomRightMm': '2',
    'cornerRadii.bottomLeftMm': '2',
  });
  branchSurface.box = { ...branchSurface.box, x: 0, y: 0, w: 28, h: 12, label: branchSurface.node.name };

  const branchLabel = createPrototypeDesignerNode('text', ordinal + 4);
  branchLabel.node.name = '会员优惠文案';
  branchLabel.node.detail = 'true 分支 · 只在条件通过后求值';
  branchLabel.node = replacePropValues(branchLabel.node, {
    'runs[0].text': '会员专属 -20%',
    'runs[0].fontSizePt': '7.5',
    'runs[0].color': 'ink',
  });
  branchLabel.box = { ...branchLabel.box, x: 3, y: 3, w: 21, h: 6, label: branchLabel.node.name };
  conditional.node.children = [branchSurface.node, branchLabel.node];

  const suffix = createPrototypeDesignerNode('text', ordinal + 5);
  suffix.node.name = '常驻后项';
  suffix.node.detail = 'Conditional 剪枝后自动向前补位';
  suffix.node = replacePropValues(suffix.node, {
    'runs[0].text': '继续结算 →',
    'runs[0].fontSizePt': '8',
    'placement.alignSelf': 'CENTER',
  });
  suffix.box = { ...suffix.box, x: stack.box.x, y: stack.box.y, w: 18, h: 8, label: suffix.node.name };

  stack.node.children = [prefix.node, conditional.node, suffix.node];
  const boxes = [stack.box, prefix.box, conditional.box, branchSurface.box, branchLabel.box, suffix.box];
  stack.node = syncGeometryProps(stack.node, new Map(boxes.map((box) => [box.nodeId, box])));
  const suffixText = preset === 'condition-true'
    ? 'TRUE：Conditional 参与 Stack，true 分支被求值'
    : preset === 'condition-false'
      ? 'FALSE：Conditional 与子树不占布局，后项前移'
      : 'ABSENT：由 absentPolicy 决定剪枝或终止 Evaluation';
  stack.node.detail = `横向 Stack · ${suffixText}`;
  return { node: stack.node, boxes, title: stack.node.name, selectedNodeId: conditional.node.id };
}

export function designerReducer(state: DesignerState, action: DesignerAction): DesignerState {
  switch (action.type) {
    case 'select-node': {
      if (!action.additive) {
        return { ...state, selectedNodeId: action.nodeId, selectedNodeIds: [action.nodeId], activeTool: 'select' };
      }
      const alreadySelected = state.selectedNodeIds.includes(action.nodeId);
      const selectedNodeIds = alreadySelected
        ? state.selectedNodeIds.filter((id) => id !== action.nodeId)
        : [...state.selectedNodeIds, action.nodeId];
      const selectedNodeId = selectedNodeIds.at(-1) ?? nodeIds.canvas;
      return { ...state, selectedNodeId, selectedNodeIds };
    }
    case 'select-all': {
      const selectedNodeIds = flattenDesignerTree(state.tree)
        .filter((node) => node.kind !== 'canvas')
        .map((node) => node.id);
      return {
        ...state,
        selectedNodeId: selectedNodeIds.at(-1) ?? nodeIds.canvas,
        selectedNodeIds: selectedNodeIds.length > 0 ? selectedNodeIds : [nodeIds.canvas],
        activeTool: 'select',
        notice: selectedNodeIds.length > 0 ? `已选择全部 ${selectedNodeIds.length} 个图层` : '画板中暂无可选图层',
      };
    }
    case 'rename-node': {
      const name = action.name.trim();
      const node = findNode(state.tree, action.nodeId);
      if (!node || node.kind === 'canvas' || !name || name === node.name) return state;
      return {
        ...state,
        tree: mapNode(state.tree, action.nodeId, (candidate) => ({ ...candidate, name })),
        dirty: true,
        notice: `“${node.name}”已重命名为“${name}”`,
      };
    }
    case 'save-definition': {
      const definition = structuredClone(action.definition);
      const existingIndex = state.definitions.findIndex((candidate) => candidate.id === definition.id);
      const definitions = existingIndex < 0
        ? [...state.definitions, definition]
        : state.definitions.map((candidate, index) => index === existingIndex ? definition : candidate);
      return {
        ...state,
        definitions,
        dirty: true,
        notice: `${existingIndex < 0 ? '已创建' : '已更新'} ${definition.name} · 写入当前模板 DesignDSL definitions[]`,
      };
    }
    case 'set-tab':
      return { ...state, leftTab: action.tab };
    case 'set-tool':
      return { ...state, activeTool: action.tool };
    case 'set-space-pan':
      return { ...state, spacePanActive: action.active };
    case 'toggle-element-outlines':
      return { ...state, showElementOutlines: !state.showElementOutlines };
    case 'pan-by':
      return {
        ...state,
        canvasOffset: {
          x: Math.max(-800, Math.min(800, state.canvasOffset.x + action.dx)),
          y: Math.max(-600, Math.min(600, state.canvasOffset.y + action.dy)),
        },
      };
    case 'reset-view':
      return { ...state, canvasOffset: { x: 0, y: 0 }, zoom: 175, spacePanActive: false };
    case 'insert-node': {
      const created = createPrototypeDesignerNode(action.kind, state.nextNodeOrdinal, {
        ...action,
        canvas: canvasProjection(state.tree),
      });
      const selected = findNode(state.tree, state.selectedNodeId);
      const requestedParent = action.parentId ? findNode(state.tree, action.parentId) : null;
      const parent = requestedParent && isContainerNodeKind(requestedParent.kind)
        ? requestedParent
        : selected && isContainerNodeKind(selected.kind)
          ? selected
          : findParentNode(state.tree, state.selectedNodeId) ?? state.tree;
      const createdNode = parent.kind === 'repeat' ? prepareRepeatChild(created.node, parent) : created.node;
      return {
        ...state,
        tree: insertChild(state.tree, parent.id, createdNode),
        boxes: [...state.boxes, created.box],
        selectedNodeId: createdNode.id,
        selectedNodeIds: [createdNode.id],
        activeTool: 'select',
        nextNodeOrdinal: state.nextNodeOrdinal + 1,
        dirty: true,
        notice: `${createdNode.name} 已加入“${parent.name}”${parent.kind === 'repeat' ? ' · 自动使用 PACK 单项布局' : ''} · 仅保存在本页内存`,
      };
    }
    case 'load-absolute-demo': {
      const demo = createAbsoluteDemo(action.preset, state.nextNodeOrdinal);
      const canvas = replacePropValues(state.tree, { layoutMode: 'FREE' });
      const tree = { ...canvas, children: [demo.node] };
      return {
        ...state,
        tree,
        boxes: demo.boxes,
        selectedNodeId: demo.node.id,
        selectedNodeIds: [demo.node.id],
        activeTool: 'select',
        nextNodeOrdinal: state.nextNodeOrdinal + demo.boxes.length,
        dirty: true,
        notice: action.preset === 'group-hug'
          ? '已载入自由分组 Demo；子项局部坐标并集会实时决定 Group HUG 边界'
          : '已载入框架 Demo；描边与内边距实时形成 ContentBox，子项保持局部绝对坐标',
      };
    }
    case 'load-stack-demo': {
      const demo = createStackDemo(action.preset, state.nextNodeOrdinal);
      const canvas = replacePropValues(state.tree, { layoutMode: 'FREE' });
      const tree = { ...canvas, children: [demo.node] };
      return {
        ...state,
        tree,
        boxes: demo.boxes,
        selectedNodeId: demo.node.id,
        selectedNodeIds: [demo.node.id],
        activeTool: 'select',
        nextNodeOrdinal: state.nextNodeOrdinal + demo.boxes.length,
        dirty: true,
        notice: `已载入“${demo.title}”；方向、间距、内边距与对齐修改会实时重新排布`,
      };
    }
    case 'load-grid-demo': {
      const demo = createGridDemo(action.preset, state.nextNodeOrdinal);
      const canvas = replacePropValues(state.tree, { layoutMode: 'FREE' });
      const tree = { ...canvas, children: [demo.node] };
      return {
        ...state,
        tree,
        boxes: demo.boxes,
        selectedNodeId: demo.node.id,
        selectedNodeIds: [demo.node.id],
        activeTool: 'select',
        nextNodeOrdinal: state.nextNodeOrdinal + demo.boxes.length,
        dirty: true,
        notice: `已载入“${demo.title}”；轨道、gap、padding、单元跨度与对齐修改会实时重新计算`,
      };
    }
    case 'load-repeat-demo': {
      const demo = createRepeatDemo(action.preset, state.nextNodeOrdinal);
      const canvas = replacePropValues(state.tree, { layoutMode: 'FREE' });
      const tree = { ...canvas, children: [demo.node] };
      return {
        ...state,
        tree,
        boxes: demo.boxes,
        selectedNodeId: demo.node.id,
        selectedNodeIds: [demo.node.id],
        activeTool: 'select',
        repeatPreviewSample: 'values',
        repeatPreviewMode: 'instances',
        repeatActiveIndex: 0,
        nextNodeOrdinal: state.nextNodeOrdinal + demo.boxes.length,
        dirty: true,
        notice: action.preset === 'repair-state'
          ? '已载入待修复 Demo；切换数组源会保留 authored 单项内容，并显式提示 schema 不兼容'
          : `已载入“${demo.title}”；先设计一份单项内容，再由实例布局生成虚拟预览`,
      };
    }
    case 'set-repeat-preview-sample':
      return { ...state, repeatPreviewSample: action.sample, repeatActiveIndex: 0 };
    case 'set-repeat-preview-mode':
      return { ...state, repeatPreviewMode: action.mode };
    case 'set-repeat-active-index':
      return { ...state, repeatActiveIndex: Math.max(0, Math.trunc(action.index)) };
    case 'set-repeat-template': {
      const repeat = findNode(state.tree, action.nodeId);
      if (!repeat || repeat.kind !== 'repeat') return state;
      const current = repeat.children.length === 1 && repeat.children[0]?.kind === 'templateUse'
        ? repeat.children[0].props.find((property) => property.label === 'templateRef.templateId')?.value
        : null;
      if (current === action.templateId) return state;
      const created = createPrototypeDesignerNode('templateUse', state.nextNodeOrdinal, { templateId: action.templateId });
      const child = prepareRepeatChild(created.node, repeat);
      const removedIds = new Set(repeat.children.flatMap((candidate) => flattenDesignerTree(candidate).map((member) => member.id)));
      return {
        ...state,
        tree: replaceChildren(state.tree, repeat.id, [child]),
        boxes: [...state.boxes.filter((box) => !removedIds.has(box.nodeId)), created.box],
        selectedNodeId: repeat.id,
        selectedNodeIds: [repeat.id],
        nextNodeOrdinal: state.nextNodeOrdinal + 1,
        repeatActiveIndex: 0,
        dirty: true,
        notice: `“${repeat.name}”已使用对应循环模板 · 浏览器内存原型`,
      };
    }
    case 'set-template-use-context': {
      const templateUse = findNode(state.tree, action.nodeId);
      const source = templateUseContextSources.find((candidate) => candidate.id === action.sourceId);
      if (!templateUse || templateUse.kind !== 'templateUse' || !source) return state;
      const currentSelector = templateUse.props.find((property) => property.label === 'contextSelector')?.value ?? '';
      if (currentSelector === source.selector) return state;
      return {
        ...state,
        tree: mapNode(state.tree, templateUse.id, (candidate) => ({
          ...replacePropValues(candidate, {
            'templateRef.templateId': '',
            contextSelector: source.selector,
            contextAbsentPolicy: source.presence === 'optional' ? 'SKIP' : 'ERROR',
            fills: '[]',
          }),
          name: `嵌套模板 · ${source.label}`,
          detail: `${source.pointer} · 等待选择模板`,
        })),
        dirty: true,
        notice: `已选择 ${source.label} · ${source.pointer}`,
      };
    }
    case 'set-template-use-template': {
      const templateUse = findNode(state.tree, action.nodeId);
      if (!templateUse || templateUse.kind !== 'templateUse') return state;
      const selector = templateUse.props.find((property) => property.label === 'contextSelector')?.value ?? '';
      const source = templateUseContextSources.find((candidate) => candidate.selector === selector);
      if (!source) return { ...state, notice: '请先选择属性' };
      const template = nestedTemplates.find((candidate) => candidate.id === action.templateId);
      if (!template || template.lifecycle !== 'ACTIVE' || template.readiness !== 'READY'
        || template.compatibilityKey !== source.compatibilityKey) {
        return { ...state, notice: '该模板与当前属性上下文不兼容，未修改嵌套模板容器' };
      }
      const currentTemplateId = templateUse.props.find((property) => property.label === 'templateRef.templateId')?.value ?? '';
      if (currentTemplateId === template.id) return state;
      return {
        ...state,
        tree: mapNode(state.tree, templateUse.id, (candidate) => ({
          ...replacePropValues(candidate, { 'templateRef.templateId': template.id }),
          name: `${template.name} · ${source.label}`,
          detail: `${source.pointer} → ${template.name}${template.proposal ? ' · 浏览器提案' : ''}`,
        })),
        dirty: true,
        notice: `“${templateUse.name}”已配置为 ${source.pointer} → ${template.name}`,
      };
    }
    case 'load-conditional-demo': {
      const demo = createConditionalDemo(action.preset, state.nextNodeOrdinal);
      const canvas = replacePropValues(state.tree, { layoutMode: 'FREE' });
      const tree = { ...canvas, children: [demo.node] };
      const conditionalPreviewSample: ConditionalPreviewSample = action.preset === 'condition-true'
        ? 'true'
        : action.preset === 'condition-false'
          ? 'false'
          : 'absent';
      return {
        ...state,
        tree,
        boxes: demo.boxes,
        selectedNodeId: demo.selectedNodeId,
        selectedNodeIds: [demo.selectedNodeId],
        activeTool: 'select',
        conditionalPreviewSample,
        nextNodeOrdinal: state.nextNodeOrdinal + demo.boxes.length,
        dirty: true,
        notice: `已载入“${demo.title}” · ${action.preset === 'condition-true' ? 'TRUE 分支参与布局' : action.preset === 'condition-false' ? 'FALSE 分支在布局前剪枝' : '缺失值交给 absentPolicy 裁决'}`,
      };
    }
    case 'set-conditional-preview-sample':
      return { ...state, conditionalPreviewSample: action.sample };
    case 'bind-image': {
      const selected = findNode(state.tree, state.selectedNodeId);
      if (selected?.kind !== 'image') {
        return designerReducer(state, { type: 'insert-node', kind: 'image', assetId: action.assetId });
      }
      const asset = assets.find((candidate) => candidate.id === action.assetId);
      return {
        ...state,
        tree: mapNode(state.tree, selected.id, (node) => ({
          ...mapProp(node, 'imageRef', (prop) => ({ ...prop, value: `{assetId:${action.assetId}}` })),
          detail: `图片资源 ${asset?.name ?? action.assetId} · ${node.props.find((prop) => prop.label === 'fit')?.value ?? 'COVER'}`,
        })),
        dirty: true,
        notice: `“${selected.name}”已切换到图片资源 ${asset?.name ?? action.assetId}`,
      };
    }
    case 'wrap-selection': {
      const selectedIds = state.selectedNodeIds.filter((id) => id !== nodeIds.canvas);
      const selectedNodes = selectedIds.map((id) => findNode(state.tree, id)).filter((node): node is DesignerNode => Boolean(node));
      if (selectedNodes.length === 0) return { ...state, notice: '请先选择至少一个元素或容器' };
      const parentIds = selectedNodes.map((node) => findParentNode(state.tree, node.id)?.id ?? '');
      if (new Set(parentIds).size !== 1 || !parentIds[0]) {
        return { ...state, notice: '编组只接受同一父容器中的选区；请先在结构树调整层级' };
      }
      const kind: Exclude<NodeKind, 'canvas'> = action.preset === 'group'
        ? 'group'
        : action.preset === 'frame'
          ? 'frame'
          : action.preset === 'grid'
            ? 'grid'
            : 'stack';
      const created = createPrototypeDesignerNode(kind, state.nextNodeOrdinal, { preset: action.preset });
      created.node.children = selectedNodes;
      created.node.detail = `${selectedNodes.length} 个直接子节点 · 原型编组`;
      created.box = selectionBox(state.boxes, selectedIds, created.box);
      created.box.nodeId = created.node.id;
      const parent = findNode(state.tree, parentIds[0]);
      if (!parent) return state;
      const selectedSet = new Set(selectedIds);
      const firstIndex = Math.min(...parent.children.map((child, index) => selectedSet.has(child.id) ? index : Number.MAX_SAFE_INTEGER));
      const retained = parent.children.filter((child) => !selectedSet.has(child.id));
      retained.splice(Math.min(firstIndex, retained.length), 0, created.node);
      return {
        ...state,
        tree: replaceChildren(state.tree, parent.id, retained),
        boxes: [...state.boxes, created.box],
        selectedNodeId: created.node.id,
        selectedNodeIds: [created.node.id],
        nextNodeOrdinal: state.nextNodeOrdinal + 1,
        dirty: true,
        notice: `${selectedNodes.length} 个节点已包入${kindLabel(kind)}`,
      };
    }
    case 'flatten-selected': {
      const selected = findNode(state.tree, state.selectedNodeId);
      if (!selected || !isContainerNodeKind(selected.kind) || selected.kind === 'canvas') {
        return { ...state, notice: '请选择一个可拍平的容器' };
      }
      const parent = findParentNode(state.tree, selected.id);
      if (!parent) return state;
      const index = parent.children.findIndex((child) => child.id === selected.id);
      const children = [...parent.children];
      children.splice(index, 1, ...selected.children);
      const selectedNodeIds = selected.children.map((child) => child.id);
      return {
        ...state,
        tree: replaceChildren(state.tree, parent.id, children),
        boxes: state.boxes.filter((box) => box.nodeId !== selected.id),
        selectedNodeId: selectedNodeIds[0] ?? parent.id,
        selectedNodeIds: selectedNodeIds.length > 0 ? selectedNodeIds : [parent.id],
        dirty: true,
        notice: `${selected.name} 已拍平；${selected.children.length} 个子节点提升到上一级`,
      };
    }
    case 'move-selection': {
      const target = findNode(state.tree, action.targetId);
      const ids = new Set(state.selectedNodeIds.filter((id) => id !== nodeIds.canvas));
      if (!target || !isContainerNodeKind(target.kind) || ids.size === 0) return state;
      const selectedNodes = [...ids].map((id) => findNode(state.tree, id)).filter((node): node is DesignerNode => Boolean(node));
      if (ids.has(target.id) || selectedNodes.some((node) => subtreeContains(node, target.id))) {
        return { ...state, notice: '不能把容器移入自己或自己的后代' };
      }
      const extracted: DesignerNode[] = [];
      const withoutSelection = removeSelectedNodes(state.tree, ids, extracted);
      const tree = extracted.reduce(
        (next, node) => insertChild(next, target.id, target.kind === 'repeat' ? prepareRepeatChild(node, target) : node),
        withoutSelection,
      );
      return {
        ...state,
        tree,
        dirty: true,
        notice: `${extracted.length} 个节点已移入“${target.name}”`,
      };
    }
    case 'drop-node': {
      const dragged = findNode(state.tree, action.draggedId);
      const target = findNode(state.tree, action.targetId);
      if (!dragged || !target || dragged.kind === 'canvas' || dragged.id === target.id) {
        return { ...state, notice: '当前图层不能移动到这个位置' };
      }

      let parentId: string;
      if (action.placement === 'into') {
        if (!isContainerNodeKind(target.kind)) {
          return { ...state, notice: '只有画板或容器可以接收子图层' };
        }
        parentId = target.id;
      } else {
        if (target.kind === 'canvas') return { ...state, notice: '根画板只接受移入操作' };
        const parent = findParentNode(state.tree, target.id);
        if (!parent) return state;
        parentId = parent.id;
      }

      if (subtreeContains(dragged, parentId)) {
        return { ...state, notice: '不能把容器移入自己或自己的后代' };
      }

      const extracted: DesignerNode[] = [];
      const withoutDragged = removeSelectedNodes(state.tree, new Set([dragged.id]), extracted);
      const moving = extracted[0];
      const parent = findNode(withoutDragged, parentId);
      if (!moving || !parent) return state;
      const children = [...parent.children];
      let insertionIndex = children.length;
      if (action.placement !== 'into') {
        const targetIndex = children.findIndex((child) => child.id === target.id);
        if (targetIndex < 0) return state;
        insertionIndex = action.placement === 'before' ? targetIndex : targetIndex + 1;
      }
      children.splice(insertionIndex, 0, parent.kind === 'repeat' ? prepareRepeatChild(moving, parent) : moving);
      const tree = replaceChildren(withoutDragged, parent.id, children);
      const placementLabel = action.placement === 'into'
        ? `移入“${target.name}”`
        : `移动到“${target.name}”${action.placement === 'before' ? '之前' : '之后'}`;
      return {
        ...state,
        tree,
        boxes: boxesInTreeOrder(state.boxes, tree),
        selectedNodeId: dragged.id,
        selectedNodeIds: [dragged.id],
        activeTool: 'select',
        dirty: true,
        notice: `“${dragged.name}”已${placementLabel}`,
      };
    }
    case 'reorder-selection': {
      const selectedIds = new Set(
        state.selectedNodeIds.filter((id) => id !== nodeIds.canvas && findNode(state.tree, id)),
      );
      if (selectedIds.size === 0) return state;
      const reordered = reorderSelectedChildren(state.tree, selectedIds, action.operation);
      if (!reordered.changed) return state;
      const labels: Record<LayerOrderOperation, string> = {
        front: '已置于同级顶层',
        forward: '已上移一层',
        backward: '已下移一层',
        back: '已置于同级底层',
      };
      return {
        ...state,
        tree: reordered.node,
        boxes: boxesInTreeOrder(state.boxes, reordered.node),
        dirty: true,
        notice: `${selectedIds.size} 个图层${labels[action.operation]}`,
      };
    }
    case 'transform-box': {
      const node = findNode(state.tree, action.nodeId);
      const current = state.boxes.find((box) => box.nodeId === action.nodeId);
      if (!node || !current || node.props.find((prop) => prop.label === 'locked')?.value === 'true') return state;
      const parent = findParentNode(state.tree, action.nodeId);
      if (action.mode === 'move' && parent && isLayoutManagingNode(parent)) return state;

      const canvas = canvasProjection(state.tree);

      let boxes: DraftBox[];
      if (action.mode === 'move') {
        const x = clampNumber(action.box.x, 0, Math.max(0, canvas.widthMm - current.w));
        const y = clampNumber(action.box.y, 0, Math.max(0, canvas.heightMm - current.h));
        boxes = state.boxes.map((box) => box.nodeId === node.id
          ? { ...box, x: roundMillimetres(x), y: roundMillimetres(y) }
          : box);
      } else {
        const geometry = constrainDraftGeometry(action.box, canvas);
        boxes = state.boxes.map((box) => box.nodeId === action.nodeId ? { ...box, ...geometry } : box);
      }

      const changedIds = new Set([node.id]);
      const geometryByNodeId = new Map(
        boxes
          .filter((box) => changedIds.has(box.nodeId))
          .map((box) => [box.nodeId, { x: box.x, y: box.y, w: box.w, h: box.h }] as const),
      );
      return {
        ...state,
        boxes,
        tree: syncGeometryProps(state.tree, geometryByNodeId),
        dirty: true,
      };
    }
    case 'delete-selection': {
      const ids = new Set(state.selectedNodeIds.filter((id) => id !== nodeIds.canvas));
      if (ids.size === 0) return state;
      const removed: DesignerNode[] = [];
      const tree = removeSelectedNodes(state.tree, ids, removed);
      const removedIds = new Set(removed.flatMap(descendantIds));
      return {
        ...state,
        tree,
        boxes: state.boxes.filter((box) => !removedIds.has(box.nodeId)),
        selectedNodeId: nodeIds.canvas,
        selectedNodeIds: [nodeIds.canvas],
        dirty: true,
        notice: `已从内存文档删除 ${removedIds.size} 个节点`,
      };
    }
    case 'set-zoom':
      return { ...state, zoom: Math.max(25, Math.min(300, action.zoom)) };
    case 'set-zoom-at':
      return {
        ...state,
        zoom: Math.max(25, Math.min(300, action.zoom)),
        canvasOffset: {
          x: Math.max(-800, Math.min(800, action.offset.x)),
          y: Math.max(-600, Math.min(600, action.offset.y)),
        },
      };
    case 'set-dpi':
      return { ...state, dpi: Math.min(1200, Math.max(72, Math.trunc(action.dpi || 96))), previewPhase: 'idle' };
    case 'set-output-format':
      return { ...state, outputFormat: action.format, previewPhase: 'idle' };
    case 'set-jpeg-quality':
      return { ...state, jpegQuality: Math.min(100, Math.max(1, Math.trunc(action.quality || 90))), previewPhase: 'idle' };
    case 'set-layout-trace':
      return { ...state, layoutTrace: action.enabled, previewPhase: 'idle' };
    case 'set-scenario': {
      const enteringConflict = action.scenario === 'conflict';
      return {
        ...state,
        scenario: action.scenario,
        savePhase: 'idle',
        previewPhase: 'idle',
        notice: null,
        templateStatus: action.scenario === 'asset-deleted'
          ? 'INVALID'
          : state.templateStatus === 'INVALID' && action.scenario === 'clean'
            ? 'READY'
            : state.templateStatus,
        currentRevision: enteringConflict ? state.revision + 1 : state.revision,
      };
    }
    case 'mark-dirty':
      return { ...state, dirty: true };
    case 'update-prop': {
      const tree = mapNode(state.tree, action.nodeId, (node) =>
        mapProp(node, action.label, (prop) => ({ ...prop, value: action.value })),
      );
      let boxes = state.boxes.map((box) => {
          if (box.nodeId !== action.nodeId) return box;
          const numericValue = Number(action.value);
          if (!Number.isFinite(numericValue)) return box;
          if (action.label === 'xMm') return { ...box, x: numericValue };
          if (action.label === 'yMm') return { ...box, y: numericValue };
          if (action.label === 'widthMm') return { ...box, w: Math.max(0.5, numericValue) };
          if (action.label === 'heightMm') return { ...box, h: Math.max(0.5, numericValue) };
          return box;
        });
      let nextTree = tree;
      if (action.nodeId === nodeIds.canvas && (action.label === 'widthMm' || action.label === 'heightMm')) {
        const canvas = canvasProjection(tree);
        boxes = boxes.map((box) => ({ ...box, ...constrainDraftGeometry(box, canvas) }));
        const geometryByNodeId = new Map(
          boxes.map((box) => [box.nodeId, { x: box.x, y: box.y, w: box.w, h: box.h }] as const),
        );
        nextTree = syncGeometryProps(tree, geometryByNodeId);
      }
      return {
        ...state,
        dirty: true,
        boxes,
        tree: nextTree,
      };
    }
    case 'open-binding':
      return { ...state, bindingEditor: { nodeId: action.nodeId, label: action.label } };
    case 'close-binding':
      return { ...state, bindingEditor: null };
    case 'save-binding':
      return {
        ...state,
        dirty: true,
        bindingEditor: null,
        tree: mapNode(state.tree, action.nodeId, (node) =>
          mapProp(node, action.label, (prop) => ({
            ...prop,
            binding: {
              id: action.bindingId,
              ref: prop.label,
              source: action.source,
              note: prop.binding?.note ?? '原型新建绑定 · 保存时由服务端权威校验',
            },
          })),
        ),
      };
    case 'remove-binding':
      return {
        ...state,
        dirty: true,
        bindingEditor: null,
        tree: mapNode(state.tree, action.nodeId, (node) =>
          mapProp(node, action.label, (prop) => {
            const next = { ...prop };
            delete next.binding;
            return next;
          }),
        ),
      };
    case 'save': {
      if (state.scenario === 'layout-error') {
        return { ...state, savePhase: 'rejected-hard', notice: 'hard error 不可二次确认 · 服务端零写' };
      }
      if (state.scenario === 'conflict') {
        return { ...state, savePhase: 'rejected-conflict', notice: null };
      }
      if (state.scenario === 'asset-deleted' || state.scenario === 'child-fill-invalid') {
        return { ...state, savePhase: 'confirm-invalid' };
      }
      const next = state.revision + 1;
      return {
        ...state,
        dirty: false,
        revision: next,
        expectedRevision: next,
        currentRevision: next,
        savePhase: 'saved',
        notice: `已在本页记录原型 revision ${next} · 未调用 Template API 或 RenderServer，刷新后状态会消失`,
      };
    }
    case 'confirm-invalid-save': {
      const next = state.revision + 1;
      return {
        ...state,
        dirty: false,
        revision: next,
        expectedRevision: next,
        currentRevision: next,
        templateStatus: 'INVALID',
        savePhase: 'saved',
        notice: `已二次确认保存为 INVALID · revision ${next} · 可继续编辑,不可权威预览 / Render`,
      };
    }
    case 'cancel-save':
      return { ...state, savePhase: 'idle' };
    case 'reload-latest':
      return {
        ...state,
        expectedRevision: state.currentRevision,
        revision: state.currentRevision,
        dirty: false,
        savePhase: 'idle',
        notice: `已重新载入 current revision ${state.currentRevision} · 本地草稿已由导出兜底(原型模拟)`,
      };
    case 'preview-start': {
      if (state.templateStatus === 'INVALID' || state.scenario === 'layout-error') {
        return { ...state, previewPhase: 'blocked' };
      }
      if (state.scenario === 'conflict') {
        return { ...state, previewPhase: 'blocked' };
      }
      return { ...state, previewPhase: 'loading', previewGeneration: state.previewGeneration + 1 };
    }
    case 'preview-finish': {
      if (state.previewPhase !== 'loading') {
        return state;
      }
      return {
        ...state,
        previewPhase: state.scenario === 'clean' ? 'ok' : 'failed',
      };
    }
    case 'preview-cancel': {
      if (state.previewPhase !== 'loading') {
        return state;
      }
      return {
        ...state,
        previewPhase: 'cancelled',
        notice: '预览已取消 · seal 前取消胜出,零输出;旧权威图片已撤下',
      };
    }
    case 'dismiss-notice':
      return { ...state, notice: null };
    case 'set-notice':
      return { ...state, notice: action.notice };
  }
}

export function findNode(node: DesignerNode, id: string): DesignerNode | null {
  if (node.id === id) {
    return node;
  }
  for (const child of node.children) {
    const hit = findNode(child, id);
    if (hit) {
      return hit;
    }
  }
  return null;
}

export const variantNames: Record<PrototypeVariant, string> = {
  A: 'Library Studio 组件库工作台',
  B: 'Canvas Focus 沉浸画布',
  C: 'Structure Bench 结构编排台',
};

export function parseVariant(value: string | null): PrototypeVariant {
  return value === 'B' || value === 'C' ? value : 'A';
}
