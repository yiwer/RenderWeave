import type { EditorNodeProjection } from './template-editor-model';

export interface TemplateTreeRow extends EditorNodeProjection {
  readonly parentNodeId: string | null;
  readonly descendantCount: number;
}

export type TemplateTreeKeyAction =
  | { readonly kind: 'focus'; readonly nodeId: string }
  | { readonly kind: 'select'; readonly nodeId: string }
  | { readonly kind: 'toggle'; readonly nodeId: string };

export interface TemplateTreeWindow {
  readonly start: number;
  readonly end: number;
  readonly totalHeight: number;
}

export interface TemplateTreeGuidePiece {
  readonly axis: 'horizontal' | 'vertical';
  readonly color: string;
  readonly height: number;
  readonly left: number;
  readonly ownerNodeId: string;
  readonly top: number;
  readonly width: number;
}

export const TEMPLATE_TREE_ROW_HEIGHT = 44;
export const TEMPLATE_TREE_INDENT_BASE = 10;
export const TEMPLATE_TREE_INDENT_STEP = 14;
export const TEMPLATE_TREE_DOT_SLOT = 20;
export const TEMPLATE_TREE_CONTAINER_DOT_RADIUS = 5;
export const TEMPLATE_TREE_LEAF_DOT_RADIUS = 3;

const TEMPLATE_TREE_GUIDE_LINE = 2;

export function templateTreeDotX(depth: number): number {
  return TEMPLATE_TREE_INDENT_BASE + depth * TEMPLATE_TREE_INDENT_STEP
    + TEMPLATE_TREE_DOT_SLOT / 2;
}

export function templateTreeGuideColor(nodeId: string): string {
  let hash = 0;
  for (let index = 0; index < nodeId.length; index += 1) {
    hash = (hash * 31 + nodeId.charCodeAt(index)) >>> 0;
  }
  const slot = Math.round((hash % 4096) * 0.6180339887) % 12;
  return `var(--te-tree-branch-${slot})`;
}

export function buildTemplateTreeBranchColors(
  rows: readonly TemplateTreeRow[],
): ReadonlyMap<string, string> {
  const colors = new Map<string, string>();
  for (const row of rows) {
    if (row.depth <= 1 || !row.parentNodeId) {
      colors.set(row.nodeId, templateTreeGuideColor(row.nodeId));
      continue;
    }
    colors.set(
      row.nodeId,
      colors.get(row.parentNodeId) ?? templateTreeGuideColor(row.parentNodeId),
    );
  }
  return colors;
}

export function buildTemplateTreeGuidePieces(
  rows: readonly TemplateTreeRow[],
  rootNodeId: string,
): Array<TemplateTreeGuidePiece[]> {
  const pieces = rows.map((): TemplateTreeGuidePiece[] => []);
  const byId = new Map(rows.map((row, index) => [row.nodeId, { index, row }]));
  const directChildren = new Map<string, number[]>();
  const branchColors = buildTemplateTreeBranchColors(rows);

  rows.forEach((row, index) => {
    if (!row.parentNodeId) return;
    const children = directChildren.get(row.parentNodeId) ?? [];
    children.push(index);
    directChildren.set(row.parentNodeId, children);
  });

  for (const [parentNodeId, childIndices] of directChildren) {
    const parent = byId.get(parentNodeId);
    if (parentNodeId !== rootNodeId && !parent) continue;
    const lastChildIndex = childIndices.at(-1);
    if (lastChildIndex === undefined) continue;
    const parentDepth = parentNodeId === rootNodeId ? 0 : parent!.row.depth;
    const startY = parentNodeId === rootNodeId
      ? 0
      : parent!.index * TEMPLATE_TREE_ROW_HEIGHT
        + TEMPLATE_TREE_ROW_HEIGHT / 2
        + TEMPLATE_TREE_CONTAINER_DOT_RADIUS;
    const endY = lastChildIndex * TEMPLATE_TREE_ROW_HEIGHT + TEMPLATE_TREE_ROW_HEIGHT / 2;
    if (endY <= startY) continue;
    const firstBand = Math.max(0, Math.floor(startY / TEMPLATE_TREE_ROW_HEIGHT));
    const lastBand = Math.min(
      rows.length - 1,
      Math.floor((endY - 0.001) / TEMPLATE_TREE_ROW_HEIGHT),
    );
    const color = parentNodeId === rootNodeId
      ? templateTreeGuideColor(rootNodeId)
      : branchColors.get(parentNodeId) ?? templateTreeGuideColor(parentNodeId);

    for (let index = firstBand; index <= lastBand; index += 1) {
      const rowTop = index * TEMPLATE_TREE_ROW_HEIGHT;
      const segmentTop = Math.max(startY, rowTop);
      const segmentBottom = Math.min(endY, rowTop + TEMPLATE_TREE_ROW_HEIGHT);
      if (segmentBottom <= segmentTop) continue;
      pieces[index]?.push({
        axis: 'vertical',
        color,
        height: segmentBottom - segmentTop,
        left: templateTreeDotX(parentDepth) - TEMPLATE_TREE_GUIDE_LINE / 2,
        ownerNodeId: parentNodeId,
        top: segmentTop,
        width: TEMPLATE_TREE_GUIDE_LINE,
      });
    }
  }

  rows.forEach((row, index) => {
    if (!row.parentNodeId || row.depth < 1) return;
    const parentDotX = templateTreeDotX(row.depth - 1);
    const childDotX = templateTreeDotX(row.depth);
    const childRadius = row.childCount > 0
      ? TEMPLATE_TREE_CONTAINER_DOT_RADIUS
      : TEMPLATE_TREE_LEAF_DOT_RADIUS;
    pieces[index]?.push({
      axis: 'horizontal',
      color: row.parentNodeId === rootNodeId
        ? templateTreeGuideColor(rootNodeId)
        : branchColors.get(row.parentNodeId) ?? templateTreeGuideColor(row.parentNodeId),
      height: TEMPLATE_TREE_GUIDE_LINE,
      left: parentDotX,
      ownerNodeId: row.parentNodeId,
      top: index * TEMPLATE_TREE_ROW_HEIGHT
        + TEMPLATE_TREE_ROW_HEIGHT / 2
        - TEMPLATE_TREE_GUIDE_LINE / 2,
      width: Math.max(0, childDotX - childRadius - parentDotX),
    });
  });
  return pieces;
}

export function buildTemplateTreeRows(
  nodes: readonly EditorNodeProjection[],
): TemplateTreeRow[] {
  const rows: TemplateTreeRow[] = [];
  const ancestors: TemplateTreeRow[] = [];
  for (const node of nodes) {
    const depth = Math.max(0, node.depth);
    ancestors.length = Math.min(ancestors.length, depth);
    const parent = depth > 0 ? ancestors[depth - 1] : undefined;
    const row: TemplateTreeRow = {
      ...node,
      depth,
      parentNodeId: parent?.nodeId ?? null,
      descendantCount: 0,
    };
    rows.push(row);
    ancestors[depth] = row;
    ancestors.length = depth + 1;
  }
  return rows.map((row, index) => {
    let end = index + 1;
    while (end < rows.length && (rows[end]?.depth ?? 0) > row.depth) end += 1;
    return { ...row, descendantCount: end - index - 1 };
  });
}

export function projectVisibleTemplateTreeRows(
  rows: readonly TemplateTreeRow[],
  collapsed: ReadonlySet<string>,
  query: string,
): TemplateTreeRow[] {
  const normalized = query.trim().toLocaleLowerCase('zh-CN');
  if (normalized) {
    const byId = new Map(rows.map((row) => [row.nodeId, row]));
    const visible = new Set<string>();
    for (const row of rows) {
      if (!`${row.displayName}\n${row.kind}`.toLocaleLowerCase('zh-CN').includes(normalized)) {
        continue;
      }
      let current: TemplateTreeRow | undefined = row;
      let remaining = rows.length;
      while (current && remaining > 0) {
        visible.add(current.nodeId);
        current = current.parentNodeId ? byId.get(current.parentNodeId) : undefined;
        remaining -= 1;
      }
    }
    return rows.filter((row) => visible.has(row.nodeId));
  }

  const visible: TemplateTreeRow[] = [];
  let hiddenBelowDepth: number | null = null;
  for (const row of rows) {
    if (hiddenBelowDepth !== null) {
      if (row.depth > hiddenBelowDepth) continue;
      hiddenBelowDepth = null;
    }
    visible.push(row);
    if (row.descendantCount > 0 && collapsed.has(row.nodeId)) {
      hiddenBelowDepth = row.depth;
    }
  }
  return visible;
}

export function templateTreeKeyAction(
  rows: readonly TemplateTreeRow[],
  collapsed: ReadonlySet<string>,
  focusedNodeId: string,
  key: string,
): TemplateTreeKeyAction | null {
  const index = rows.findIndex((row) => row.nodeId === focusedNodeId);
  const row = rows[index];
  if (!row) return null;
  switch (key) {
    case 'ArrowDown': {
      const next = rows[index + 1];
      return next ? { kind: 'focus', nodeId: next.nodeId } : null;
    }
    case 'ArrowUp': {
      const previous = rows[index - 1];
      return previous ? { kind: 'focus', nodeId: previous.nodeId } : null;
    }
    case 'Home': {
      const first = rows[0];
      return first ? { kind: 'focus', nodeId: first.nodeId } : null;
    }
    case 'End': {
      const last = rows.at(-1);
      return last ? { kind: 'focus', nodeId: last.nodeId } : null;
    }
    case 'ArrowRight': {
      if (row.descendantCount === 0) return null;
      if (collapsed.has(row.nodeId)) return { kind: 'toggle', nodeId: row.nodeId };
      const child = rows[index + 1];
      return child && child.depth > row.depth
        ? { kind: 'focus', nodeId: child.nodeId }
        : null;
    }
    case 'ArrowLeft':
      if (row.descendantCount > 0 && !collapsed.has(row.nodeId)) {
        return { kind: 'toggle', nodeId: row.nodeId };
      }
      return row.parentNodeId
        ? { kind: 'focus', nodeId: row.parentNodeId }
        : null;
    case ' ':
    case 'Enter':
      return { kind: 'select', nodeId: row.nodeId };
    default:
      return null;
  }
}

export function templateTreeWindow({
  rowCount,
  scrollTop,
  viewportHeight,
  rowHeight,
  overscan,
}: {
  readonly rowCount: number;
  readonly scrollTop: number;
  readonly viewportHeight: number;
  readonly rowHeight: number;
  readonly overscan: number;
}): TemplateTreeWindow {
  const count = Math.max(0, Math.floor(rowCount));
  const height = Number.isFinite(rowHeight) && rowHeight > 0 ? rowHeight : 1;
  const extra = Math.max(0, Math.floor(overscan));
  const firstVisible = Math.max(0, Math.floor(Math.max(0, scrollTop) / height));
  const visibleCount = Math.max(1, Math.ceil(Math.max(0, viewportHeight) / height));
  const start = Math.max(0, Math.min(count, firstVisible - extra));
  const end = Math.max(start, Math.min(count, firstVisible + visibleCount + extra));
  return { start, end, totalHeight: count * height };
}
