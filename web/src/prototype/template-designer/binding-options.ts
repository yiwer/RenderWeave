import {
  definitionDomain,
  definitionValueType,
  findNode,
  findParentNode,
  repeatSourcesForDefinitions,
  schemaFields,
  type DesignerNode,
  type DesignerState,
  type DesignerValueType,
  type NodeKind,
} from './model';

type BindingValueType = Exclude<DesignerValueType, `list<${string}>`>;
type PrototypeBindingTargetValueType = BindingValueType | `enum<${string}>` | 'gridTrackList';

export interface PrototypeBindingSourceOption {
  source: string;
  label: string;
  detail: string;
  group: '系统字段' | '循环域' | '模板定义';
  valueType: BindingValueType;
}

const COMMON_BINDING_TARGETS = {
  xMm: 'decimal',
  yMm: 'decimal',
  widthMm: 'decimal',
  heightMm: 'decimal',
  rotationDeg: 'decimal',
  opacity: 'decimal',
  visible: 'boolean',
  render: 'boolean',
  'placement.minWidthMm': 'decimal',
  'placement.maxWidthMm': 'decimal',
  'placement.minHeightMm': 'decimal',
  'placement.maxHeightMm': 'decimal',
  'placement.marginTopMm': 'decimal',
  'placement.marginRightMm': 'decimal',
  'placement.marginBottomMm': 'decimal',
  'placement.marginLeftMm': 'decimal',
  'placement.alignSelf': 'enum<INHERIT|START|CENTER|END>',
  'placement.fillWeight': 'decimal',
  'placement.row': 'decimal',
  'placement.column': 'decimal',
  'placement.rowSpan': 'decimal',
  'placement.columnSpan': 'decimal',
  'placement.horizontalAlignSelf': 'enum<START|CENTER|END>',
  'placement.verticalAlignSelf': 'enum<START|CENTER|END>',
} as const satisfies Record<string, PrototypeBindingTargetValueType>;

const BOX_BINDING_TARGETS = {
  'fill.color': 'color',
  'stroke.color': 'color',
  'stroke.widthMm': 'decimal',
  'cornerRadii.topLeftMm': 'decimal',
  'cornerRadii.topRightMm': 'decimal',
  'cornerRadii.bottomRightMm': 'decimal',
  'cornerRadii.bottomLeftMm': 'decimal',
  'padding.topMm': 'decimal',
  'padding.rightMm': 'decimal',
  'padding.bottomMm': 'decimal',
  'padding.leftMm': 'decimal',
  clipContent: 'boolean',
} as const satisfies Record<string, PrototypeBindingTargetValueType>;

const NODE_BINDING_TARGETS = {
  canvas: { backgroundColor: 'color' },
  group: {},
  frame: BOX_BINDING_TARGETS,
  stack: {
    ...BOX_BINDING_TARGETS,
    direction: 'enum<HORIZONTAL|VERTICAL>',
    gapMm: 'decimal',
    mainAlign: 'enum<START|CENTER|END|SPACE_BETWEEN>',
    crossAlign: 'enum<START|CENTER|END|STRETCH>',
  },
  grid: {
    ...BOX_BINDING_TARGETS,
    columns: 'gridTrackList',
    rows: 'gridTrackList',
    columnGapMm: 'decimal',
    rowGapMm: 'decimal',
  },
  text: {
    'runs[0].text': 'text',
    'runs[0].fontRef': 'fontRef',
    'runs[0].fontSizePt': 'decimal',
    'runs[0].color': 'color',
    writingMode: 'enum<HORIZONTAL_TB|VERTICAL_RL>',
    lineBreak: 'enum<NONE|WORD|CHAR>',
    overflow: 'enum<VISIBLE|CLIP|ELLIPSIS|FAIL>',
    horizontalAlign: 'enum<LEFT|CENTER|RIGHT|JUSTIFY|SPACE_EVENLY>',
    verticalAlign: 'enum<TOP|CENTER|BOTTOM|JUSTIFY|SPACE_EVENLY>',
    maxLines: 'decimal',
    'padding.topMm': 'decimal',
    'padding.rightMm': 'decimal',
    'padding.bottomMm': 'decimal',
    'padding.leftMm': 'decimal',
    'stroke.widthMm': 'decimal',
    shrinkToFit: 'boolean',
  },
  image: {
    imageRef: 'imageRef',
    fit: 'enum<CONTAIN|COVER|FILL|NONE>',
    sampling: 'enum<LINEAR|NEAREST>',
    cornerRadiusMm: 'decimal',
  },
  rect: {
    'fill.color': 'color',
    'stroke.color': 'color',
    'stroke.widthMm': 'decimal',
    'cornerRadii.topLeftMm': 'decimal',
    'cornerRadii.topRightMm': 'decimal',
    'cornerRadii.bottomRightMm': 'decimal',
    'cornerRadii.bottomLeftMm': 'decimal',
  },
  ellipse: {
    'fill.color': 'color',
    'stroke.color': 'color',
    'stroke.widthMm': 'decimal',
    innerRadiusMm: 'decimal',
  },
  line: {
    'stroke.color': 'color',
    'stroke.widthMm': 'decimal',
    lineCap: 'enum<BUTT|ROUND|SQUARE>',
    startArrow: 'enum<NONE|ARROW|CIRCLE>',
    endArrow: 'enum<NONE|ARROW|CIRCLE>',
  },
  polygon: {
    preset: 'enum<STAR|TRIANGLE|ARROW|POLYGON>',
    pointsCount: 'decimal',
    innerRadiusMm: 'decimal',
    'fill.color': 'color',
    'stroke.color': 'color',
    'stroke.widthMm': 'decimal',
  },
  polyline: {
    closed: 'boolean',
    lineJoin: 'enum<MITER|ROUND|BEVEL>',
    'fill.color': 'color',
    'stroke.color': 'color',
    'stroke.widthMm': 'decimal',
  },
  path: {
    fillRule: 'enum<NON_ZERO|EVEN_ODD>',
    'fill.color': 'color',
    'stroke.color': 'color',
    'stroke.widthMm': 'decimal',
  },
  qrCode: {
    content: 'text',
    errorCorrectionLevel: 'enum<L|M|Q|H>',
    quietZoneMm: 'decimal',
    foregroundColor: 'color',
    backgroundColor: 'color',
  },
  barcode: {
    code: 'text',
    symbology: 'enum<EAN13|CODE128|UPC_A>',
    showText: 'boolean',
    barWidthMm: 'decimal',
    foregroundColor: 'color',
    backgroundColor: 'color',
  },
  repeat: {
    'itemLayout.direction': 'enum<ROW|COLUMN>',
    'itemLayout.gapMm': 'decimal',
    'itemLayout.columns': 'decimal',
    'itemLayout.columnGapMm': 'decimal',
    'itemLayout.rowGapMm': 'decimal',
    'instanceLayout.direction': 'enum<ROW|COLUMN>',
    'instanceLayout.gapMm': 'decimal',
    'instanceLayout.columns': 'decimal',
    'instanceLayout.columnGapMm': 'decimal',
    'instanceLayout.rowGapMm': 'decimal',
  },
  conditional: {},
  templateUse: {},
} as const satisfies Record<NodeKind, Readonly<Record<string, PrototypeBindingTargetValueType>>>;

export function bindingTargetValueType(
  nodeKind: NodeKind,
  propertyLabel: string,
): PrototypeBindingTargetValueType | null {
  if (nodeKind !== 'canvas' && propertyLabel in COMMON_BINDING_TARGETS) {
    return COMMON_BINDING_TARGETS[propertyLabel as keyof typeof COMMON_BINDING_TARGETS];
  }
  const kindTargets = NODE_BINDING_TARGETS[nodeKind] as Readonly<Record<string, PrototypeBindingTargetValueType>>;
  return kindTargets[propertyLabel] ?? null;
}

function isSourceValueType(valueType: PrototypeBindingTargetValueType | null): valueType is BindingValueType {
  return valueType !== null && valueType !== 'gridTrackList' && !valueType.startsWith('enum<');
}

export function prototypeBindingSourceOptions(
  state: DesignerState,
  nodeId: string,
  propertyLabel: string,
): PrototypeBindingSourceOption[] {
  const node = findNode(state.tree, nodeId);
  if (!node) return [];
  const targetType = bindingTargetValueType(node.kind, propertyLabel);
  if (!isSourceValueType(targetType)) return [];

  const systemOptions = schemaFields
    .filter((field) => field.type === targetType)
    .map((field): PrototypeBindingSourceOption => ({
      source: field.path,
      label: field.path,
      detail: `${field.presence === 'required' ? '必填' : '可选'} · ${field.type}`,
      group: '系统字段',
      valueType: targetType,
    }));

  const loops = enclosingRepeats(state.tree, node);
  const activeLoopIds = new Set(loops.flatMap((repeat) => repeat.loopId ? [repeat.loopId] : []));
  const loopOptions = loops.flatMap((repeat) => repeatOptions(state, repeat, targetType));

  const definitionOptions = state.definitions
    .filter((definition) => definitionValueType(definition) === targetType)
    .filter((definition) => {
      const domain = definitionDomain(definition);
      return domain === 'invocation' || activeLoopIds.has(domain.loopId);
    })
    .map((definition): PrototypeBindingSourceOption => ({
      source: `definition(${definition.id})`,
      label: definition.name,
      detail: `${definition.kind} · ${definitionValueType(definition)}`,
      group: '模板定义',
      valueType: targetType,
    }));

  return [...systemOptions, ...loopOptions, ...definitionOptions];
}

function enclosingRepeats(tree: DesignerNode, node: DesignerNode): DesignerNode[] {
  const repeats: DesignerNode[] = [];
  let current: DesignerNode | null = node;
  while (current) {
    const parent = findParentNode(tree, current.id);
    if (!parent) break;
    if (parent.kind === 'repeat' && parent.loopId) repeats.unshift(parent);
    current = parent;
  }
  return repeats;
}

function repeatOptions(
  state: DesignerState,
  repeat: DesignerNode,
  targetType: BindingValueType,
): PrototypeBindingSourceOption[] {
  if (!repeat.loopId) return [];
  const options: PrototypeBindingSourceOption[] = [];
  if (targetType === 'decimal') {
    options.push({
      source: `loopIndex(${repeat.loopId})`,
      label: `${repeat.name} · 原始索引`,
      detail: `loopIndex · ${repeat.loopId.slice(0, 8)}`,
      group: '循环域',
      valueType: 'decimal',
    });
  }

  const items = repeat.props.find((property) => property.label === 'items')?.value ?? '';
  const repeatSource = repeatSourcesForDefinitions(state.definitions)
    .find((candidate) => candidate.expression === items);
  if (!repeatSource) return options;

  if (repeatSource.sourceType === 'SCALAR_LIST' && repeatSource.itemValueType === targetType) {
    options.push({
      source: `context(loop ${repeat.loopId}, /value)`,
      label: `${repeat.name} · 当前值`,
      detail: `/value · ${repeatSource.itemStaticSchemaRef}`,
      group: '循环域',
      valueType: targetType,
    });
  }

  if (repeatSource.itemStaticSchemaRef === 'offer-card@v2') {
    const fields: Array<{ label: string; pointer: string; valueType: BindingValueType }> = [
      { label: '优惠名称', pointer: '/name', valueType: 'text' },
      { label: '优惠价', pointer: '/price', valueType: 'decimal' },
      { label: '角标', pointer: '/badge', valueType: 'text' },
    ];
    for (const field of fields.filter((candidate) => candidate.valueType === targetType)) {
      options.push({
        source: `context(loop ${repeat.loopId}, ${field.pointer})`,
        label: `${repeat.name} · ${field.label}`,
        detail: `${field.pointer} · ${repeatSource.itemStaticSchemaRef}`,
        group: '循环域',
        valueType: field.valueType,
      });
    }
  }

  return options;
}
