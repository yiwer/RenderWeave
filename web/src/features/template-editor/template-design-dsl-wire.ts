import { isLosslessNumber } from 'lossless-json';

export const DESIGN_DSL_VERSION = 'renderweave-design/1.0';
export const EXPRESSION_PROFILE = 'renderweave-expression/1.0';

export type DesignDslWireInspection =
  | { status: 'supported' }
  | { status: 'unsupported-profile'; path: 'dslVersion' | 'expressionProfile' }
  | { status: 'malformed'; path: string }
  | { status: 'unknown'; path: string };

const SUPPORTED: DesignDslWireInspection = Object.freeze({ status: 'supported' });

/**
 * Answers only whether the editor can project the complete closed wire without loss.
 * Domain validation remains server-owned; known members with invalid scalar values are
 * deliberately left to that authority.
 */
export function inspectDesignDslWire(
  root: Record<string, unknown>,
): DesignDslWireInspection {
  if (root.dslVersion !== DESIGN_DSL_VERSION) {
    return { status: 'unsupported-profile', path: 'dslVersion' };
  }
  if (root.expressionProfile !== EXPRESSION_PROFILE) {
    return { status: 'unsupported-profile', path: 'expressionProfile' };
  }

  const inspector = new ClosedWireInspector();
  inspector.allowed(root, ROOT_MEMBERS, 'DesignDSL');
  inspector.requiredString(root.displayName, 'displayName');
  const definitions = inspector.array(root.definitions, 'definitions');
  definitions?.forEach((entry, index) => inspector.definition(entry, `definitions[${index}]`));
  if (isRecord(root.designRoot)
    && typeof root.designRoot.kind === 'string'
    && NODE_KINDS.has(root.designRoot.kind)
    && root.designRoot.kind !== 'canvas') {
    inspector.malformed('designRoot.kind');
  }
  inspector.node(root.designRoot, 'designRoot');
  if (inspector.malformedPath !== null) {
    return { status: 'malformed', path: inspector.malformedPath };
  }
  if (inspector.unknownPath !== null) return { status: 'unknown', path: inspector.unknownPath };
  return SUPPORTED;
}

class ClosedWireInspector {
  malformedPath: string | null = null;
  unknownPath: string | null = null;

  malformed(path: string) {
    if (this.malformedPath === null) this.malformedPath = path;
  }

  markUnknown(path: string) {
    if (this.unknownPath === null) this.unknownPath = path;
  }

  object(value: unknown, path: string): Record<string, unknown> | null {
    if (isRecord(value)) return value;
    this.malformed(path);
    return null;
  }

  array(value: unknown, path: string): unknown[] | null {
    if (Array.isArray(value)) return value;
    this.malformed(path);
    return null;
  }

  required(value: unknown, path: string): unknown {
    if (value === undefined) this.malformed(path);
    return value;
  }

  requiredString(value: unknown, path: string) {
    if (typeof value !== 'string') this.malformed(path);
  }

  allowed(value: unknown, allowed: ReadonlySet<string>, path: string) {
    if (!isRecord(value)) return;
    const unknown = Object.keys(value).find((key) => !allowed.has(key));
    if (unknown !== undefined) this.markUnknown(`${path}.${unknown}`);
  }

  union(kind: unknown, known: ReadonlySet<string>, path: string): kind is string {
    if (typeof kind !== 'string') {
      this.malformed(path);
      return false;
    }
    if (!known.has(kind)) this.markUnknown(path);
    return known.has(kind);
  }

  definition(value: unknown, path: string) {
    const definition = this.object(value, path);
    if (!definition) return;
    const kind = definition.kind;
    if (!this.union(kind, DEFINITION_KINDS, `${path}.kind`)) return;
    const allowed = new Set(COMMON_DEFINITION_MEMBERS);
    for (const member of DEFINITION_MEMBERS[kind] ?? []) allowed.add(member);
    this.allowed(definition, allowed, path);
    this.requiredString(definition.definitionId, `${path}.definitionId`);
    this.requiredString(definition.displayName, `${path}.displayName`);
    if (kind === 'custom') {
      this.requiredString(definition.exposure, `${path}.exposure`);
      this.valueType(this.required(definition.valueType, `${path}.valueType`), `${path}.valueType`);
      this.literal(
        this.required(definition.defaultValue, `${path}.defaultValue`),
        definition.valueType,
        `${path}.defaultValue`,
      );
    } else if (kind === 'mapping') {
      this.domain(this.required(definition.domain, `${path}.domain`), `${path}.domain`);
      this.valueType(this.required(definition.output, `${path}.output`), `${path}.output`);
      this.source(this.required(definition.input, `${path}.input`), `${path}.input`);
      const cases = this.array(definition.cases, `${path}.cases`);
      cases?.forEach((entry, index) => this.mappingCase(entry, `${path}.cases[${index}]`));
      this.source(this.required(definition.otherwise, `${path}.otherwise`), `${path}.otherwise`);
    } else if (kind === 'expression') {
      this.domain(this.required(definition.domain, `${path}.domain`), `${path}.domain`);
      this.valueType(this.required(definition.output, `${path}.output`), `${path}.output`);
      const inputs = this.array(definition.inputs, `${path}.inputs`);
      inputs?.forEach((entry, index) => {
        const inputPath = `${path}.inputs[${index}]`;
        const input = this.object(entry, inputPath);
        if (!input) return;
        this.allowed(input, EXPRESSION_INPUT_MEMBERS, inputPath);
        this.requiredString(input.alias, `${inputPath}.alias`);
        this.source(this.required(input.source, `${inputPath}.source`), `${inputPath}.source`);
      });
      this.requiredString(definition.source, `${path}.source`);
    }
  }

  mappingCase(value: unknown, path: string) {
    const mappingCase = this.object(value, path);
    if (!mappingCase) return;
    this.allowed(mappingCase, CASE_MEMBERS, path);
    this.requiredString(mappingCase.operator, `${path}.operator`);
    if (mappingCase.operand !== undefined) {
      const operand = this.object(mappingCase.operand, `${path}.operand`);
      if (operand) {
        this.allowed(operand, OPERAND_MEMBERS, `${path}.operand`);
        this.valueType(
          this.required(operand.valueType, `${path}.operand.valueType`),
          `${path}.operand.valueType`,
        );
        this.literal(
          this.required(operand.value, `${path}.operand.value`),
          operand.valueType,
          `${path}.operand.value`,
        );
      }
    }
    this.source(this.required(mappingCase.then, `${path}.then`), `${path}.then`);
  }

  valueType(value: unknown, path: string) {
    if (typeof value === 'string') {
      if (!BASE_VALUE_TYPES.has(value)) this.markUnknown(path);
      return;
    }
    const valueType = this.object(value, path);
    if (!valueType) return;
    if (!this.union(valueType.type, VALUE_TYPE_KINDS, `${path}.type`)) return;
    this.allowed(valueType, VALUE_TYPE_MEMBERS[valueType.type] ?? EMPTY_MEMBERS, path);
    if (valueType.type === 'list') {
      this.requiredString(valueType.items, `${path}.items`);
    } else {
      this.requiredString(valueType.catalogId, `${path}.catalogId`);
    }
  }

  literal(value: unknown, valueType: unknown, path: string) {
    const key = valueTypeKey(valueType);
    if (key === 'imageRef' || key === 'fontRef') {
      const assetRef = this.object(value, path);
      if (assetRef) {
        this.allowed(assetRef, ASSET_REF_MEMBERS, path);
        this.requiredString(assetRef.assetId, `${path}.assetId`);
      }
    }
    if (key?.startsWith('list:')) {
      const items = this.array(value, path);
      if (!items) return;
      const itemType = key.slice('list:'.length);
      items.forEach((item, index) => this.literal(item, itemType, `${path}[${index}]`));
    }
  }

  domain(value: unknown, path: string) {
    if (typeof value === 'string') return;
    const domain = this.object(value, path);
    if (!domain) return;
    this.allowed(domain, DOMAIN_LOOP_MEMBERS, path);
    if (!this.union(domain.kind, DOMAIN_KINDS, `${path}.kind`)) return;
    this.requiredString(domain.loopId, `${path}.loopId`);
  }

  source(value: unknown, path: string) {
    const source = this.object(value, path);
    if (!source) return;
    const kind = source.kind;
    if (!this.union(kind, VALUE_SOURCE_KINDS, `${path}.kind`)) return;
    this.allowed(source, SOURCE_MEMBERS[kind] ?? EMPTY_MEMBERS, path);
    if (kind === 'literal') {
      this.valueType(this.required(source.valueType, `${path}.valueType`), `${path}.valueType`);
      this.literal(this.required(source.value, `${path}.value`), source.valueType, `${path}.value`);
    } else if (kind === 'context') {
      this.domain(this.required(source.domain, `${path}.domain`), `${path}.domain`);
      this.requiredString(source.pointer, `${path}.pointer`);
    } else if (kind === 'loopIndex') {
      this.requiredString(source.loopId, `${path}.loopId`);
    } else if (kind === 'definition') {
      this.requiredString(source.definitionId, `${path}.definitionId`);
    } else {
      this.requiredString(source.capability, `${path}.capability`);
      this.requiredString(source.operation, `${path}.operation`);
    }
  }

  node(value: unknown, path: string) {
    const node = this.object(value, path);
    if (!node) return;
    this.requiredString(node.nodeId, `${path}.nodeId`);
    const kind = node.kind;
    if (!this.union(kind, NODE_KINDS, `${path}.kind`)) return;
    const allowed = kind === 'canvas' ? CANVAS_MEMBERS : NODE_MEMBERS[kind] ?? COMMON_NODE_MEMBERS;
    this.allowed(node, allowed, path);
    this.bindings(node.bindings, `${path}.bindings`);
    if (kind !== 'canvas') this.placement(node.placement, `${path}.placement`);
    this.optionalObject(node.transform, TRANSFORM_MEMBERS, `${path}.transform`);
    this.optionalObject(node.fill, FILL_MEMBERS, `${path}.fill`);
    this.stroke(node.stroke, kind === 'text', `${path}.stroke`);
    this.optionalObject(node.cornerRadii, CORNER_RADII_MEMBERS, `${path}.cornerRadii`);
    this.optionalObject(node.padding, PADDING_MEMBERS, `${path}.padding`);

    if (kind === 'canvas') this.optionalObject(node.bleed, BLEED_MEMBERS, `${path}.bleed`);
    if (kind === 'grid') {
      this.tracks(node.rows, `${path}.rows`);
      this.tracks(node.columns, `${path}.columns`);
    } else if (kind === 'repeat') {
      this.source(this.required(node.items, `${path}.items`), `${path}.items`);
      this.packing(node.itemLayout, `${path}.itemLayout`);
      this.packing(node.instanceLayout, `${path}.instanceLayout`);
    } else if (kind === 'text') {
      const runs = this.array(node.runs, `${path}.runs`);
      runs?.forEach((run, index) => {
        const runPath = `${path}.runs[${index}]`;
        const runObject = this.object(run, runPath);
        if (!runObject) return;
        this.allowed(runObject, RUN_MEMBERS, runPath);
        const fontRef = this.object(runObject.fontRef, `${runPath}.fontRef`);
        if (fontRef) {
          this.allowed(fontRef, ASSET_REF_MEMBERS, `${runPath}.fontRef`);
          this.requiredString(fontRef.assetId, `${runPath}.fontRef.assetId`);
        }
      });
      if (node.lineHeight !== undefined) this.lineHeight(node.lineHeight, `${path}.lineHeight`);
    } else if (kind === 'image') {
      const imageRef = this.object(node.imageRef, `${path}.imageRef`);
      if (imageRef) {
        this.allowed(imageRef, ASSET_REF_MEMBERS, `${path}.imageRef`);
        this.requiredString(imageRef.assetId, `${path}.imageRef.assetId`);
      }
    } else if (kind === 'line') {
      this.requiredObject(node.start, POINT_MM_MEMBERS, `${path}.start`);
      this.requiredObject(node.end, POINT_MM_MEMBERS, `${path}.end`);
      this.requiredObject(node.stroke, STROKE_MM_MEMBERS, `${path}.stroke`);
    } else if (kind === 'polygon' || kind === 'polyline') {
      this.points(node.points, `${path}.points`);
      if (kind === 'polyline') {
        this.requiredObject(node.stroke, STROKE_MM_MEMBERS, `${path}.stroke`);
      }
    } else if (kind === 'path') {
      this.pathCommands(node.commands, `${path}.commands`);
    } else if (kind === 'templateUse') {
      this.templateUse(node, path);
    } else if (kind === 'conditional') {
      this.source(this.required(node.condition, `${path}.condition`), `${path}.condition`);
    }

    if (CONTAINER_NODE_KINDS.has(kind)) {
      const children = this.array(node.children, `${path}.children`);
      children?.forEach((child, index) => this.node(child, `${path}.children[${index}]`));
    }
  }

  bindings(value: unknown, path: string) {
    const bindings = this.array(value, path);
    bindings?.forEach((entry, index) => {
      const bindingPath = `${path}[${index}]`;
      const binding = this.object(entry, bindingPath);
      if (!binding) return;
      this.allowed(binding, BINDING_MEMBERS, bindingPath);
      this.requiredString(binding.bindingId, `${bindingPath}.bindingId`);
      const target = this.object(binding.targetPropertyRef, `${bindingPath}.targetPropertyRef`);
      if (target) {
        this.allowed(
          target,
          TARGET_PROPERTY_REF_MEMBERS,
          `${bindingPath}.targetPropertyRef`,
        );
        this.requiredString(
          target.rootPropertyId,
          `${bindingPath}.targetPropertyRef.rootPropertyId`,
        );
        const selectors = this.array(
          target.selectors,
          `${bindingPath}.targetPropertyRef.selectors`,
        );
        selectors?.forEach((selector, selectorIndex) => {
          const selectorPath = `${bindingPath}.targetPropertyRef.selectors[${selectorIndex}]`;
          const selectorObject = this.object(selector, selectorPath);
          if (!selectorObject) return;
          const kind = selectorObject.kind;
          if (!this.union(kind, SELECTOR_KINDS, `${selectorPath}.kind`)) return;
          this.allowed(
            selectorObject,
            kind === 'member' ? MEMBER_SELECTOR_MEMBERS : INDEX_SELECTOR_MEMBERS,
            selectorPath,
          );
          if (kind === 'member') this.requiredString(selectorObject.name, `${selectorPath}.name`);
          else this.required(selectorObject.index, `${selectorPath}.index`);
        });
      }
      this.source(this.required(binding.source, `${bindingPath}.source`), `${bindingPath}.source`);
    });
  }

  placement(value: unknown, path: string) {
    const placement = this.object(value, path);
    if (!placement) return;
    const type = placement.type;
    if (!this.union(type, PLACEMENT_KINDS, `${path}.type`)) return;
    this.allowed(placement, PLACEMENT_MEMBERS[type] ?? EMPTY_MEMBERS, path);
  }

  stroke(value: unknown, points: boolean, path: string) {
    if (value === undefined) return;
    const stroke = this.object(value, path);
    if (stroke) this.allowed(stroke, points ? STROKE_PT_MEMBERS : STROKE_MM_MEMBERS, path);
  }

  tracks(value: unknown, path: string) {
    const tracks = this.array(value, path);
    tracks?.forEach((track, index) => {
      const trackPath = `${path}[${index}]`;
      const trackObject = this.object(track, trackPath);
      if (!trackObject) return;
      const type = trackObject.type;
      if (!this.union(type, TRACK_KINDS, `${trackPath}.type`)) return;
      this.allowed(trackObject, TRACK_MEMBERS[type] ?? EMPTY_MEMBERS, trackPath);
      if (type === 'FIXED') this.required(trackObject.valueMm, `${trackPath}.valueMm`);
      if (type === 'FRACTION') this.required(trackObject.weight, `${trackPath}.weight`);
    });
  }

  packing(value: unknown, path: string) {
    const packing = this.object(value, path);
    if (!packing) return;
    const kind = packing.kind;
    if (!this.union(kind, PACKING_KINDS, `${path}.kind`)) return;
    this.allowed(packing, kind === 'STACK' ? STACK_PACKING_MEMBERS : GRID_PACKING_MEMBERS, path);
    if (kind === 'STACK') this.requiredString(packing.direction, `${path}.direction`);
    else this.required(packing.columns, `${path}.columns`);
  }

  lineHeight(value: unknown, path: string) {
    const lineHeight = this.object(value, path);
    if (!lineHeight) return;
    const type = lineHeight.type;
    if (!this.union(type, LINE_HEIGHT_KINDS, `${path}.type`)) return;
    this.allowed(lineHeight, LINE_HEIGHT_MEMBERS, path);
    this.required(
      type === 'FACTOR' ? lineHeight.factor : lineHeight.valuePt,
      `${path}.${type === 'FACTOR' ? 'factor' : 'valuePt'}`,
    );
  }

  points(value: unknown, path: string) {
    const points = this.array(value, path);
    points?.forEach((point, index) => {
      const pointPath = `${path}[${index}]`;
      this.requiredObject(point, POINT_MM_MEMBERS, pointPath);
    });
  }

  pathCommands(value: unknown, path: string) {
    const commands = this.array(value, path);
    commands?.forEach((command, index) => {
      const commandPath = `${path}[${index}]`;
      const commandObject = this.object(command, commandPath);
      if (!commandObject) return;
      const type = commandObject.type;
      if (!this.union(type, PATH_COMMAND_KINDS, `${commandPath}.type`)) return;
      this.allowed(commandObject, PATH_COMMAND_MEMBERS[type] ?? EMPTY_MEMBERS, commandPath);
    });
  }

  optionalObject(value: unknown, members: ReadonlySet<string>, path: string) {
    if (value === undefined) return;
    this.requiredObject(value, members, path);
  }

  requiredObject(value: unknown, members: ReadonlySet<string>, path: string) {
    const object = this.object(value, path);
    if (object) this.allowed(object, members, path);
    return object;
  }

  templateUse(value: Record<string, unknown>, path: string) {
    const templateRef = this.requiredObject(
      value.templateRef,
      TEMPLATE_REF_MEMBERS,
      `${path}.templateRef`,
    );
    if (templateRef) this.requiredString(templateRef.templateId, `${path}.templateRef.templateId`);
    const selector = this.object(value.contextSelector, `${path}.contextSelector`);
    if (selector) {
      const kind = selector.kind;
      if (this.union(kind, CONTEXT_SELECTOR_KINDS, `${path}.contextSelector.kind`)) {
        this.allowed(
          selector,
          kind === 'context' ? CONTEXT_SELECTOR_MEMBERS : EMPTY_SELECTOR_MEMBERS,
          `${path}.contextSelector`,
        );
        if (kind === 'context') {
          this.selectorDomain(
            this.required(selector.domain, `${path}.contextSelector.domain`),
            `${path}.contextSelector.domain`,
          );
          this.requiredString(
            selector.contextAbsentPolicy,
            `${path}.contextSelector.contextAbsentPolicy`,
          );
        }
      }
    }
    const fills = this.array(value.fills, `${path}.fills`);
    fills?.forEach((fill, index) => {
      const fillPath = `${path}.fills[${index}]`;
      const fillObject = this.object(fill, fillPath);
      if (!fillObject) return;
      this.allowed(fillObject, USE_FILL_MEMBERS, fillPath);
      this.requiredString(fillObject.targetDefinitionId, `${fillPath}.targetDefinitionId`);
      this.source(this.required(fillObject.source, `${fillPath}.source`), `${fillPath}.source`);
    });
  }

  selectorDomain(value: unknown, path: string) {
    const domain = this.object(value, path);
    if (!domain) return;
    if (!this.union(domain.kind, SELECTOR_DOMAIN_KINDS, `${path}.kind`)) return;
    this.allowed(domain, SELECTOR_DOMAIN_MEMBERS[domain.kind] ?? EMPTY_MEMBERS, path);
    if (domain.kind === 'loop') this.requiredString(domain.loopId, `${path}.loopId`);
  }
}

const ROOT_MEMBERS = set(
  'dslVersion',
  'expressionProfile',
  'displayName',
  'description',
  'definitions',
  'designRoot',
);
const CANVAS_MEMBERS = set('nodeId', 'kind', 'displayName', 'widthMm', 'heightMm', 'backgroundColor', 'bleed', 'bindings', 'children');
const BLEED_MEMBERS = set('topMm', 'rightMm', 'bottomMm', 'leftMm');
const COMMON_NODE_MEMBERS = set('nodeId', 'kind', 'displayName', 'bindings', 'placement', 'render', 'visible', 'opacity', 'transform');
const CONTAINER_MEMBERS = set('children');
const APPEARANCE_MEMBERS = set('fill', 'stroke', 'cornerRadii', 'padding', 'clipContent');
const STACK_MEMBERS = set('direction', 'gapMm', 'justifyContent', 'alignItems');
const GRID_MEMBERS = set('rows', 'columns', 'rowGapMm', 'columnGapMm');
const REPEAT_MEMBERS = set('loopId', 'items', 'absentPolicy', 'itemLayout', 'instanceLayout');
const TEXT_MEMBERS = set('runs', 'writingMode', 'horizontalAlign', 'verticalAlign', 'lineBreak', 'overflow', 'lineHeight', 'maxLines', 'padding', 'stroke', 'fitMode', 'minScale');
const RUN_MEMBERS = set('text', 'fontRef', 'fontSizePt', 'color', 'decoration', 'letterSpacingPt', 'letterSpacingFactor');
const LINE_HEIGHT_MEMBERS = set('type', 'factor', 'valuePt');
const IMAGE_MEMBERS = set('imageRef', 'fit', 'sampling');
const RECT_MEMBERS = set('fill', 'stroke', 'cornerRadii');
const ELLIPSE_MEMBERS = set('fill', 'stroke');
const LINE_MEMBERS = set('start', 'end', 'stroke');
const POLYGON_MEMBERS = set('points', 'fill', 'stroke');
const POLYLINE_MEMBERS = set('points', 'stroke');
const PATH_MEMBERS = set('commands', 'fill', 'stroke', 'fillRule');
const QRCODE_MEMBERS = set('content', 'errorCorrectionLevel', 'foregroundColor', 'backgroundColor');
const BARCODE_MEMBERS = set('format', 'value', 'foregroundColor', 'backgroundColor');
const POINT_MM_MEMBERS = set('xMm', 'yMm');
const TEMPLATE_USE_MEMBERS = set('useId', 'templateRef', 'contextSelector', 'fills');
const CONDITIONAL_MEMBERS = set('condition', 'absentPolicy');
const NODE_KINDS = set('canvas', 'group', 'frame', 'stack', 'grid', 'repeat', 'text', 'image', 'rect', 'ellipse', 'line', 'polygon', 'polyline', 'path', 'qrCode', 'barcode', 'templateUse', 'conditional');
export const SUPPORTED_NODE_KIND_COUNT = NODE_KINDS.size;
const CONTAINER_NODE_KINDS = set('canvas', 'group', 'frame', 'stack', 'grid', 'repeat', 'conditional');

const NODE_MEMBERS: Record<string, ReadonlySet<string>> = {
  group: union(COMMON_NODE_MEMBERS, CONTAINER_MEMBERS),
  frame: union(COMMON_NODE_MEMBERS, CONTAINER_MEMBERS, APPEARANCE_MEMBERS),
  stack: union(COMMON_NODE_MEMBERS, CONTAINER_MEMBERS, APPEARANCE_MEMBERS, STACK_MEMBERS),
  grid: union(COMMON_NODE_MEMBERS, CONTAINER_MEMBERS, APPEARANCE_MEMBERS, GRID_MEMBERS),
  repeat: union(COMMON_NODE_MEMBERS, CONTAINER_MEMBERS, REPEAT_MEMBERS),
  text: union(COMMON_NODE_MEMBERS, TEXT_MEMBERS),
  image: union(COMMON_NODE_MEMBERS, IMAGE_MEMBERS),
  rect: union(COMMON_NODE_MEMBERS, RECT_MEMBERS),
  ellipse: union(COMMON_NODE_MEMBERS, ELLIPSE_MEMBERS),
  line: union(COMMON_NODE_MEMBERS, LINE_MEMBERS),
  polygon: union(COMMON_NODE_MEMBERS, POLYGON_MEMBERS),
  polyline: union(COMMON_NODE_MEMBERS, POLYLINE_MEMBERS),
  path: union(COMMON_NODE_MEMBERS, PATH_MEMBERS),
  qrCode: union(COMMON_NODE_MEMBERS, QRCODE_MEMBERS),
  barcode: union(COMMON_NODE_MEMBERS, BARCODE_MEMBERS),
  templateUse: union(COMMON_NODE_MEMBERS, TEMPLATE_USE_MEMBERS),
  conditional: union(COMMON_NODE_MEMBERS, CONTAINER_MEMBERS, CONDITIONAL_MEMBERS),
};
const FILL_MEMBERS = set('color');
const STROKE_MM_MEMBERS = set('color', 'widthMm', 'cap', 'join');
const STROKE_PT_MEMBERS = set('color', 'widthPt', 'cap', 'join');
const PADDING_MEMBERS = set('topMm', 'rightMm', 'bottomMm', 'leftMm');
const CORNER_RADII_MEMBERS = set('topLeftMm', 'topRightMm', 'bottomRightMm', 'bottomLeftMm');
const TRANSFORM_MEMBERS = set('rotationDeg', 'scaleX', 'scaleY', 'originX', 'originY');
const ABSOLUTE_PLACEMENT_MEMBERS = set('type', 'xMm', 'yMm', 'widthMode', 'heightMode', 'widthMm', 'heightMm', 'minWidthMm', 'minHeightMm', 'maxWidthMm', 'maxHeightMm', 'rightInsetMm', 'bottomInsetMm');
const STACK_PLACEMENT_MEMBERS = set('type', 'widthMode', 'heightMode', 'widthMm', 'heightMm', 'minWidthMm', 'minHeightMm', 'maxWidthMm', 'maxHeightMm', 'marginTopMm', 'marginRightMm', 'marginBottomMm', 'marginLeftMm', 'alignSelf', 'fillWeight');
const GRID_PLACEMENT_MEMBERS = set('type', 'widthMode', 'heightMode', 'widthMm', 'heightMm', 'minWidthMm', 'minHeightMm', 'maxWidthMm', 'maxHeightMm', 'row', 'column', 'rowSpan', 'columnSpan', 'marginTopMm', 'marginRightMm', 'marginBottomMm', 'marginLeftMm', 'horizontalAlignSelf', 'verticalAlignSelf');
const PACK_PLACEMENT_MEMBERS = set('type', 'widthMode', 'heightMode', 'widthMm', 'heightMm', 'minWidthMm', 'minHeightMm', 'maxWidthMm', 'maxHeightMm');
const PLACEMENT_KINDS = set('ABSOLUTE', 'STACK', 'GRID', 'PACK');
const PLACEMENT_MEMBERS: Record<string, ReadonlySet<string>> = {
  ABSOLUTE: ABSOLUTE_PLACEMENT_MEMBERS,
  STACK: STACK_PLACEMENT_MEMBERS,
  GRID: GRID_PLACEMENT_MEMBERS,
  PACK: PACK_PLACEMENT_MEMBERS,
};
const TRACK_KINDS = set('FIXED', 'FRACTION', 'AUTO');
const TRACK_MEMBERS: Record<string, ReadonlySet<string>> = {
  FIXED: set('type', 'valueMm'), FRACTION: set('type', 'weight'), AUTO: set('type'),
};
const STACK_PACKING_MEMBERS = set('kind', 'direction', 'gapMm');
const GRID_PACKING_MEMBERS = set('kind', 'columns', 'columnGapMm', 'rowGapMm');
const PACKING_KINDS = set('STACK', 'GRID');

const DEFINITION_KINDS = set('custom', 'mapping', 'expression');
const COMMON_DEFINITION_MEMBERS = set('definitionId', 'kind', 'displayName');
const DEFINITION_MEMBERS: Record<string, ReadonlySet<string>> = {
  custom: set('exposure', 'valueType', 'defaultValue'),
  mapping: set('domain', 'output', 'input', 'cases', 'otherwise'),
  expression: set('domain', 'output', 'inputs', 'source'),
};
const BASE_VALUE_TYPES = set('text', 'decimal', 'boolean', 'date', 'time', 'color', 'imageRef', 'fontRef');
const VALUE_TYPE_MEMBERS: Record<string, ReadonlySet<string>> = {
  list: set('type', 'items'), enum: set('type', 'catalogId'),
};
const VALUE_TYPE_KINDS = set('list', 'enum');
const VALUE_SOURCE_KINDS = set('literal', 'context', 'loopIndex', 'definition', 'capability');
const SOURCE_MEMBERS: Record<string, ReadonlySet<string>> = {
  literal: set('kind', 'valueType', 'value'),
  context: set('kind', 'domain', 'pointer'),
  loopIndex: set('kind', 'loopId'),
  definition: set('kind', 'definitionId'),
  capability: set('kind', 'capability', 'operation'),
};
const CASE_MEMBERS = set('operator', 'operand', 'then');
const OPERAND_MEMBERS = set('valueType', 'value');
const EXPRESSION_INPUT_MEMBERS = set('alias', 'source');
const DOMAIN_LOOP_MEMBERS = set('kind', 'loopId');
const DOMAIN_KINDS = set('loop');
const ASSET_REF_MEMBERS = set('assetId');

const BINDING_MEMBERS = set('bindingId', 'targetPropertyRef', 'source');
const TARGET_PROPERTY_REF_MEMBERS = set('rootPropertyId', 'selectors');
const MEMBER_SELECTOR_MEMBERS = set('kind', 'name');
const INDEX_SELECTOR_MEMBERS = set('kind', 'index');
const SELECTOR_KINDS = set('member', 'index');
const TEMPLATE_REF_MEMBERS = set('templateId');
const CONTEXT_SELECTOR_MEMBERS = set('kind', 'domain', 'pointer', 'contextAbsentPolicy');
const EMPTY_SELECTOR_MEMBERS = set('kind');
const CONTEXT_SELECTOR_KINDS = set('context', 'empty');
const SELECTOR_DOMAIN_MEMBERS: Record<string, ReadonlySet<string>> = {
  invocation: set('kind'), loop: set('kind', 'loopId'),
};
const SELECTOR_DOMAIN_KINDS = set('invocation', 'loop');
const USE_FILL_MEMBERS = set('targetDefinitionId', 'source');
const LINE_HEIGHT_KINDS = set('FACTOR', 'FIXED');
const PATH_COMMAND_KINDS = set('MOVE_TO', 'LINE_TO', 'QUAD_TO', 'CUBIC_TO', 'CLOSE');
const PATH_COMMAND_MEMBERS: Record<string, ReadonlySet<string>> = {
  MOVE_TO: set('type', 'xMm', 'yMm'),
  LINE_TO: set('type', 'xMm', 'yMm'),
  QUAD_TO: set('type', 'cxMm', 'cyMm', 'xMm', 'yMm'),
  CUBIC_TO: set('type', 'c1xMm', 'c1yMm', 'c2xMm', 'c2yMm', 'xMm', 'yMm'),
  CLOSE: set('type'),
};
const EMPTY_MEMBERS = new Set<string>();

function valueTypeKey(value: unknown): string | null {
  if (typeof value === 'string') return value;
  if (isRecord(value) && value.type === 'list' && typeof value.items === 'string') {
    return `list:${value.items}`;
  }
  return null;
}

function set(...values: string[]): ReadonlySet<string> {
  return new Set(values);
}

function union(...sets: ReadonlySet<string>[]): ReadonlySet<string> {
  return new Set(sets.flatMap((value) => [...value]));
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
    && !isLosslessNumber(value);
}
