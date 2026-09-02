import type { DesignCanvasNode, DesignNode } from '../../api/generated';

export type TemplateDesignNodeKind = DesignCanvasNode['kind'] | DesignNode['kind'];
export type TemplateDesignPlacementVariant = 'ABSOLUTE' | 'STACK' | 'GRID' | 'PACK';

interface TemplateEditorNodeContract {
  readonly container: boolean;
  readonly childPlacement: TemplateDesignPlacementVariant | null;
  readonly coreAuthoring: boolean;
  readonly coreParent: boolean;
}

/**
 * Exhaustive browser projection of the exact renderweave-design/1.0 node-kind
 * capability matrix. Components consume this map instead of maintaining their
 * own kind switches; the generated Canvas/DesignNode unions make missing kinds a build error.
 */
const NODE_CONTRACTS = Object.freeze({
  canvas: contract(true, 'ABSOLUTE', true, true),
  group: contract(true, 'ABSOLUTE'),
  frame: contract(true, 'ABSOLUTE', true, true),
  stack: contract(true, 'STACK', true, true),
  grid: contract(true, 'GRID'),
  repeat: contract(true, 'PACK'),
  conditional: contract(true, 'ABSOLUTE'),
  text: contract(false, null, true),
  image: contract(false, null, true),
  rect: contract(false, null, true),
  ellipse: contract(false, null, true),
  line: contract(false, null, true),
  polygon: contract(false, null, true),
  polyline: contract(false, null, true),
  path: contract(false, null, true),
  qrCode: contract(false, null, true),
  barcode: contract(false, null, true),
  templateUse: contract(false),
} satisfies Record<TemplateDesignNodeKind, TemplateEditorNodeContract>);

export const TEMPLATE_DESIGN_NODE_KINDS: ReadonlySet<string> = new Set(
  Object.keys(NODE_CONTRACTS),
);

export const TEMPLATE_DESIGN_CONTAINER_NODE_KINDS: ReadonlySet<string> = new Set(
  Object.entries(NODE_CONTRACTS)
    .filter(([, value]) => value.container)
    .map(([kind]) => kind),
);

export function isTemplateDesignNodeKind(value: unknown): value is TemplateDesignNodeKind {
  return typeof value === 'string' && TEMPLATE_DESIGN_NODE_KINDS.has(value);
}

export function isTemplateDesignContainerKind(value: unknown): value is TemplateDesignNodeKind {
  return typeof value === 'string' && TEMPLATE_DESIGN_CONTAINER_NODE_KINDS.has(value);
}

export function expectedTemplateChildPlacement(
  parentKind: unknown,
): TemplateDesignPlacementVariant | null {
  return isTemplateDesignNodeKind(parentKind) ? NODE_CONTRACTS[parentKind].childPlacement : null;
}

export function isCoreTemplateAuthoringKind(value: unknown): boolean {
  return isTemplateDesignNodeKind(value) && NODE_CONTRACTS[value].coreAuthoring;
}

export function isCoreTemplateAuthoringParentKind(value: unknown): boolean {
  return isTemplateDesignNodeKind(value) && NODE_CONTRACTS[value].coreParent;
}

function contract(
  container: boolean,
  childPlacement: TemplateDesignPlacementVariant | null = null,
  coreAuthoring = false,
  coreParent = false,
): TemplateEditorNodeContract {
  return Object.freeze({ container, childPlacement, coreAuthoring, coreParent });
}
