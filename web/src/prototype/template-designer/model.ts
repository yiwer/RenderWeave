/**
 * PROTOTYPE — throwaway. 三个在线 Template 设计器变体共享的内存模型。
 * 问题:技术型作者如何完成「结构 → 绑定 → 样本数据 → 权威预览 → 保存」闭环?
 * 数据来源:.scratch/renderweave-template-v1/issues/17 + 18 的 inherited constraints。
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

export type LeftTab = 'library' | 'tree' | 'assets' | 'definitions' | 'data' | 'exchange';

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
  usedBy?: string;
  misuse?: string;
}

export interface DesignerDefinition {
  id: string;
  name: string;
  kind: 'CUSTOM' | 'EXPRESSION' | 'MAPPING';
  visibility: 'PUBLIC' | 'PRIVATE';
  domain: string;
  detail: string;
  inputs?: string[];
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
} as const;

export const assetIds = {
  logoBadge: '5a000000-0000-4000-8000-000000000001',
  logoLegacy: '5a000000-0000-4000-8000-000000000002',
  brandSans: '5a000000-0000-4000-8000-000000000003',
  wrongKind: '5a000000-0000-4000-8000-000000000004',
} as const;

export const childTemplateIds = {
  tagPill: '6a000000-0000-4000-8000-000000000001',
  brandBadge: '6a000000-0000-4000-8000-000000000002',
} as const;

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
  { kind: 'templateUse', label: 'TemplateUse 子模板', group: 'compose' },
];

export const nodeGroupLabels: Record<NodeGroup, string> = {
  container: '容器 · 拥有 children',
  element: '元素 · 叶子',
  compose: '组合 · 子模板',
};

export const designTree: DesignerNode = {
  id: nodeIds.canvas,
  kind: 'canvas',
  name: '画板 Canvas',
  detail: '90×54mm · 物理尺寸固定',
  flags: [],
  props: [
    { label: 'widthMm', value: '90', bindable: false },
    { label: 'heightMm', value: '54', bindable: false },
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
          detail: '1 Run · 10.5pt · fontRef brand-sans',
          flags: [],
          props: [
            {
              label: 'runs[0].text',
              value: '「春季新品发布会」',
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
              detail: '1 Run · 16pt · 等宽数字',
              flags: [],
              props: [
                {
                  label: 'runs[0].text',
                  value: '「¥ 199.00」',
                  bindable: true,
                  binding: { id: bindingIds.price, ref: 'runs[0].text', source: `definition(${definitionIds.priceText})`, note: 'Expression definition · PUBLIC' },
                },
                { label: 'runs[0].fontSizePt', value: '16', bindable: true },
                { label: 'runs[0].color', value: 'ink', bindable: true },
              ],
              children: [],
            },
            {
              id: nodeIds.currencyNote,
              kind: 'text',
              name: 'currencyNote 单位',
              detail: '静态 baseline「起」· 无 Binding',
              flags: [],
              props: [
                { label: 'runs[0].text', value: '「起」', bindable: true },
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
              value: '「2026-08-01」',
              bindable: true,
              binding: { id: bindingIds.date, ref: 'runs[0].text', source: '/launchDate', note: 'optional field · 运行时可能 typed ABSENT' },
            },
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
                  value: '「新品」',
                  bindable: true,
                  binding: { id: bindingIds.tag, ref: 'runs[0].text', source: `context(loop ${loopIds.tags}, /value)`, note: '显式 loopId domain · /value · 无 $parent 动态作用域' },
                },
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
  { id: assetIds.brandSans, name: 'brand-sans', kind: 'FONT', status: 'ACTIVE', detail: 'TTF · single face · 388 KB · contentVersion 1', tags: ['品牌', '字体'], usedBy: '/designRoot/children/0/children/0/runs/0/fontRef' },
  { id: assetIds.wrongKind, name: 'legacy-display', kind: 'IMAGE', status: 'ACTIVE', detail: 'PNG · 12 KB · contentVersion 1', tags: ['迁移'], misuse: 'dateLine.runs[0].fontRef 引用 IMAGE → kind mismatch' },
];

export const definitions: DesignerDefinition[] = [
  { id: definitionIds.priceText, name: 'priceText', kind: 'EXPRESSION', visibility: 'PUBLIC', domain: 'invocation', detail: "concat('¥ ', formatDecimal(input.price, 2, 2, HALF_UP))", inputs: ['price ← context /price'] },
  { id: definitionIds.brandName, name: 'brandName', kind: 'CUSTOM', visibility: 'PRIVATE', domain: 'invocation', detail: 'default 「海博优选」· 外部 override 静默忽略' },
  { id: definitionIds.tagLabel, name: 'tagLabel', kind: 'MAPPING', visibility: 'PUBLIC', domain: `loop ${loopIds.tags}`, detail: 'ordered Mapping: PATTERN_MATCH → value · otherwise', inputs: ['value ← loop /value'] },
  { id: definitionIds.detailUrl, name: 'detailUrl', kind: 'EXPRESSION', visibility: 'PUBLIC', domain: 'invocation', detail: "concat('https://example.cn/p/', input.sku)", inputs: ['sku ← context /sku'] },
  { id: definitionIds.renderDate, name: 'renderDate', kind: 'EXPRESSION', visibility: 'PUBLIC', domain: 'invocation', detail: 'formatDate(input.today)', inputs: ['today ← capability UTC_DATE'] },
  { id: definitionIds.badgeVariant, name: 'badgeVariant', kind: 'EXPRESSION', visibility: 'PRIVATE', domain: `loop ${loopIds.tags}`, detail: "if(input.draw < 0.5, 'A', 'B')", inputs: ['draw ← capability UNIFORM_DECIMAL_0_1'] },
  { id: definitionIds.brandIcon, name: 'brandIcon', kind: 'CUSTOM', visibility: 'PUBLIC', domain: 'invocation', detail: `default imageRef ${assetIds.logoBadge} · expected IMAGE` },
];

export const draftBoxes: DraftBox[] = [
  { nodeId: nodeIds.titleText, x: 4, y: 4, w: 58, h: 6, tone: 'text', label: 'titleText' },
  { nodeId: nodeIds.priceBand, x: 4, y: 11.5, w: 50, h: 15, tone: 'frame', label: 'priceBand' },
  { nodeId: nodeIds.priceText, x: 6.5, y: 13.5, w: 32, h: 10, tone: 'text', label: 'priceText' },
  { nodeId: nodeIds.currencyNote, x: 40, y: 16.5, w: 11, h: 5, tone: 'text', label: 'currencyNote' },
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
  { path: '/launchDate', type: 'date', presence: 'optional' },
  { path: '/tags', type: 'array[text]', presence: 'optional' },
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
  ];
  if (scenario !== 'binding-absent') {
    lines.push('  "launchDate": "2026-08-01",');
  }
  lines.push(
    '  "tags": ["新品", "限量", "会员"],',
    '  "brand": { "name": "海博优选" },',
    '  "sku": "SKU-1042"',
    '}',
  );
  return lines.join('\n');
}

export const customValuesSample = [
  '{',
  '  "brandName": "外部覆盖值",',
  `  "brandIcon": "${assetIds.logoBadge}",`,
  '  "unknownKey": 1',
  '}',
].join('\n');

export interface BindingEditorTarget {
  nodeId: string;
  label: string;
}

export interface DesignerState {
  tree: DesignerNode;
  scenario: Scenario;
  selectedNodeId: string;
  leftTab: LeftTab;
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
  tree: structuredClone(designTree),
  scenario: 'clean',
  selectedNodeId: nodeIds.titleText,
  leftTab: 'tree',
  zoom: 200,
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

export type DesignerAction =
  | { type: 'select-node'; nodeId: string }
  | { type: 'set-tab'; tab: LeftTab }
  | { type: 'set-zoom'; zoom: number }
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

export function designerReducer(state: DesignerState, action: DesignerAction): DesignerState {
  switch (action.type) {
    case 'select-node':
      return { ...state, selectedNodeId: action.nodeId };
    case 'set-tab':
      return { ...state, leftTab: action.tab };
    case 'set-zoom':
      return { ...state, zoom: action.zoom };
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
    case 'update-prop':
      return {
        ...state,
        dirty: true,
        tree: mapNode(state.tree, action.nodeId, (node) =>
          mapProp(node, action.label, (prop) => ({ ...prop, value: action.value })),
        ),
      };
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
        notice: `已保存 revision ${next} · 服务端 canonical 重同步:metadata trim、definitions/bindings 按 ID 排序、decimal 词法规范化`,
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
  A: 'Docked Workbench 三栏工作台',
  B: 'Immersive Canvas 沉浸画布',
  C: 'Binding Bench 绑定工作台',
};

export function parseVariant(value: string | null): PrototypeVariant {
  return value === 'B' || value === 'C' ? value : 'A';
}
