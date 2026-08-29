import {
  Barcode,
  Circle,
  Frame,
  Group,
  Hexagon,
  Image,
  Layers,
  LayoutGrid,
  Minus,
  PenTool,
  Puzzle,
  QrCode,
  Repeat,
  Search,
  Split,
  Square,
  Type,
  type LucideIcon,
} from 'lucide-react';
import {
  useCallback,
  useEffect,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
  type CSSProperties,
  type KeyboardEvent,
} from 'react';

import type { EditorNodeProjection } from './template-editor-model';
import {
  buildTemplateTreeBranchColors,
  buildTemplateTreeGuidePieces,
  buildTemplateTreeRows,
  projectVisibleTemplateTreeRows,
  TEMPLATE_TREE_INDENT_BASE,
  TEMPLATE_TREE_INDENT_STEP,
  TEMPLATE_TREE_ROW_HEIGHT,
  templateTreeKeyAction,
  templateTreeGuideColor,
  templateTreeWindow,
  type TemplateTreeRow,
} from './template-structure-tree';

const TREE_OVERSCAN = 6;
const DEFAULT_TREE_VIEWPORT_HEIGHT = 440;

const KIND_PRESENTATION: Record<string, { icon: LucideIcon; label: string }> = {
  canvas: { icon: Square, label: '画布' },
  group: { icon: Group, label: '分组' },
  frame: { icon: Frame, label: '框架' },
  stack: { icon: Layers, label: '堆叠' },
  grid: { icon: LayoutGrid, label: '网格' },
  repeat: { icon: Repeat, label: '重复' },
  text: { icon: Type, label: '文本' },
  image: { icon: Image, label: '图片' },
  rect: { icon: Square, label: '矩形' },
  ellipse: { icon: Circle, label: '椭圆' },
  line: { icon: Minus, label: '线段' },
  polygon: { icon: Hexagon, label: '多边形' },
  polyline: { icon: PenTool, label: '折线' },
  path: { icon: PenTool, label: '路径' },
  qrCode: { icon: QrCode, label: '二维码' },
  barcode: { icon: Barcode, label: '条码' },
  templateUse: { icon: Puzzle, label: 'Template 引用' },
  conditional: { icon: Split, label: '条件' },
};

export function TemplateEditorStructureTree({
  nodes,
  selectedNodeId,
  onSelectNode,
}: {
  nodes: readonly EditorNodeProjection[];
  selectedNodeId: string;
  onSelectNode: (nodeId: string) => void;
}) {
  const rows = useMemo(() => buildTemplateTreeRows(nodes), [nodes]);
  const [collapsed, setCollapsed] = useState<ReadonlySet<string>>(() => new Set());
  const [query, setQuery] = useState('');
  const [scrollTop, setScrollTop] = useState(0);
  const [viewportHeight, setViewportHeight] = useState(DEFAULT_TREE_VIEWPORT_HEIGHT);
  const rowRefs = useRef(new Map<string, HTMLDivElement>());
  const pendingFocusNodeIdRef = useRef<string | null>(null);
  const viewportRef = useRef<HTMLDivElement>(null);

  const selectedAncestors = useMemo(
    () => ancestorIds(rows, selectedNodeId),
    [rows, selectedNodeId],
  );
  const effectiveCollapsed = useMemo(() => {
    if (selectedAncestors.size === 0) return collapsed;
    const next = new Set(collapsed);
    for (const nodeId of selectedAncestors) next.delete(nodeId);
    return next;
  }, [collapsed, selectedAncestors]);
  const activeCollapsed = useMemo<ReadonlySet<string>>(
    () => query.trim().length > 0 ? new Set<string>() : effectiveCollapsed,
    [effectiveCollapsed, query],
  );
  const visibleRows = useMemo(
    () => projectVisibleTemplateTreeRows(rows, activeCollapsed, query),
    [activeCollapsed, query, rows],
  );
  const rootRow = visibleRows[0]?.depth === 0 ? visibleRows[0] : undefined;
  const nodeRows = useMemo(
    () => visibleRows[0]?.depth === 0 ? visibleRows.slice(1) : visibleRows,
    [visibleRows],
  );
  const branchColors = useMemo(
    () => buildTemplateTreeBranchColors(nodeRows),
    [nodeRows],
  );
  const guidePieces = useMemo(
    () => buildTemplateTreeGuidePieces(nodeRows, rootRow?.nodeId ?? ''),
    [nodeRows, rootRow?.nodeId],
  );
  const window = templateTreeWindow({
    rowCount: nodeRows.length,
    scrollTop,
    viewportHeight,
    rowHeight: TEMPLATE_TREE_ROW_HEIGHT,
    overscan: TREE_OVERSCAN,
  });
  const selectedWindowIndex = nodeRows.findIndex((row) => row.nodeId === selectedNodeId);
  const windowIndices = new Set<number>();
  for (let index = window.start; index < window.end; index += 1) windowIndices.add(index);
  if (selectedWindowIndex >= 0) windowIndices.add(selectedWindowIndex);
  const windowRows = [...windowIndices]
    .sort((left, right) => left - right)
    .map((index) => ({ index, row: nodeRows[index] }))
    .filter((entry): entry is { index: number; row: TemplateTreeRow } => entry.row !== undefined);
  const effectiveFocusedNodeId = visibleRows.some((row) => row.nodeId === selectedNodeId)
    ? selectedNodeId
    : rootRow?.nodeId ?? visibleRows[0]?.nodeId ?? '';

  useEffect(() => {
    const viewport = viewportRef.current;
    if (!viewport || selectedWindowIndex < 0) return;
    const top = selectedWindowIndex * TEMPLATE_TREE_ROW_HEIGHT;
    const bottom = top + TEMPLATE_TREE_ROW_HEIGHT;
    let nextScrollTop = viewport.scrollTop;
    if (top < viewport.scrollTop) nextScrollTop = top;
    else if (bottom > viewport.scrollTop + viewport.clientHeight) {
      nextScrollTop = Math.max(0, bottom - viewport.clientHeight);
    }
    if (nextScrollTop !== viewport.scrollTop) {
      viewport.scrollTop = nextScrollTop;
      setScrollTop(nextScrollTop);
    }
  }, [selectedWindowIndex]);

  useLayoutEffect(() => {
    const nodeId = pendingFocusNodeIdRef.current;
    if (!nodeId) return;
    const row = rowRefs.current.get(nodeId);
    if (!row) return;
    row.focus({ preventScroll: true });
    pendingFocusNodeIdRef.current = null;
  }, [selectedNodeId, window.end, window.start]);

  useEffect(() => {
    const viewport = viewportRef.current;
    if (!viewport || typeof ResizeObserver === 'undefined') return undefined;
    const observer = new ResizeObserver(([entry]) => {
      const height = entry?.contentRect.height ?? 0;
      if (height > 0) setViewportHeight(height);
    });
    observer.observe(viewport);
    return () => observer.disconnect();
  }, []);

  const toggleRow = (row: TemplateTreeRow) => {
    if (row.descendantCount === 0) return;
    const willCollapse = !effectiveCollapsed.has(row.nodeId);
    if (willCollapse && selectedAncestors.has(row.nodeId)) onSelectNode(row.nodeId);
    setCollapsed((current) => {
      const next = new Set(current);
      if (next.has(row.nodeId)) next.delete(row.nodeId);
      else next.add(row.nodeId);
      return next;
    });
  };

  const focusAndSelect = (nodeId: string) => {
    const row = rowRefs.current.get(nodeId);
    if (row) {
      pendingFocusNodeIdRef.current = null;
      row.focus({ preventScroll: true });
    } else {
      pendingFocusNodeIdRef.current = nodeId;
    }
    onSelectNode(nodeId);
  };

  const registerTreeRow = useCallback((nodeId: string, element: HTMLDivElement | null) => {
    if (element) rowRefs.current.set(nodeId, element);
    else rowRefs.current.delete(nodeId);
  }, []);

  const handleTreeKeyDown = (
    event: KeyboardEvent<HTMLDivElement>,
    nodeId: string,
  ) => {
    const action = templateTreeKeyAction(visibleRows, activeCollapsed, nodeId, event.key);
    if (!action) return;
    event.preventDefault();
    event.stopPropagation();
    if (action.kind === 'toggle') {
      const row = rows.find((candidate) => candidate.nodeId === action.nodeId);
      if (row) toggleRow(row);
      return;
    }
    focusAndSelect(action.nodeId);
  };

  return (
    <div className="te-structure-tree">
      <header className="te-panel-heading">
        <div>
          <h2>结构</h2>
          <small>真实父子树 · 方向键遍历</small>
        </div>
        <span>{nodes.length} 个节点</span>
      </header>
      <label className="te-tree-search">
        <Search aria-hidden="true" size={14} />
        <span className="sr-only">搜索 DesignDSL 结构</span>
        <input
          type="search"
          aria-label="搜索 DesignDSL 结构"
          placeholder="搜索节点名称或类型…"
          value={query}
          onChange={(event) => {
            setQuery(event.target.value);
            setScrollTop(0);
            if (viewportRef.current) viewportRef.current.scrollTop = 0;
          }}
        />
      </label>
      <div className="te-tree" role="tree" aria-label="DesignDSL 结构">
        {rootRow ? (
          <TreeRow
            row={rootRow}
            selected={rootRow.nodeId === selectedNodeId}
            focused={rootRow.nodeId === effectiveFocusedNodeId}
            expanded={!activeCollapsed.has(rootRow.nodeId)}
            branchColor={templateTreeGuideColor(rootRow.nodeId)}
            root
            register={(element) => registerTreeRow(rootRow.nodeId, element)}
            onKeyDown={(event) => handleTreeKeyDown(event, rootRow.nodeId)}
            onSelect={() => focusAndSelect(rootRow.nodeId)}
            onToggle={() => toggleRow(rootRow)}
          />
        ) : null}
        {visibleRows.length === 0 ? (
          <p className="te-tree-empty" role="status">没有匹配的 authored 节点。</p>
        ) : (
          <div
            className="te-tree-viewport"
            ref={viewportRef}
            role="group"
            onScroll={(event) => setScrollTop(event.currentTarget.scrollTop)}
          >
            <div
              className="te-tree-spacer"
              role="presentation"
              style={{ height: `${window.totalHeight}px` }}
            >
              {windowRows.map(({ index, row }) => (
                <TreeRow
                  key={row.nodeId}
                  row={row}
                  selected={row.nodeId === selectedNodeId}
                  focused={row.nodeId === effectiveFocusedNodeId}
                  expanded={!activeCollapsed.has(row.nodeId)}
                  branchColor={branchColors.get(row.nodeId) ?? templateTreeGuideColor(row.nodeId)}
                  register={(element) => registerTreeRow(row.nodeId, element)}
                  style={{ top: `${index * TEMPLATE_TREE_ROW_HEIGHT}px` }}
                  onKeyDown={(event) => handleTreeKeyDown(event, row.nodeId)}
                  onSelect={() => focusAndSelect(row.nodeId)}
                  onToggle={() => toggleRow(row)}
                />
              ))}
              {windowRows.flatMap(({ index, row }) => (guidePieces[index] ?? []).map((piece, pieceIndex) => (
                <span
                  key={`${row.nodeId}-${piece.axis}-${pieceIndex}`}
                  className="te-tree-guide"
                  data-axis={piece.axis}
                  aria-hidden="true"
                  style={{
                    top: `${piece.top}px`,
                    left: `${piece.left}px`,
                    width: `${piece.width}px`,
                    height: `${piece.height}px`,
                    background: piece.color,
                  }}
                />
              )))}
            </div>
          </div>
        )}
      </div>
    </div>
  );
}

function TreeRow({
  row,
  selected,
  focused,
  expanded,
  branchColor,
  root = false,
  register,
  style,
  onKeyDown,
  onSelect,
  onToggle,
}: {
  row: TemplateTreeRow;
  selected: boolean;
  focused: boolean;
  expanded: boolean;
  branchColor: string;
  root?: boolean;
  register: (element: HTMLDivElement | null) => void;
  style?: CSSProperties;
  onKeyDown: (event: KeyboardEvent<HTMLDivElement>) => void;
  onSelect: () => void;
  onToggle: () => void;
}) {
  const presentation = KIND_PRESENTATION[row.kind] ?? { icon: Square, label: row.kind };
  const Icon = presentation.icon;
  const hasChildren = row.descendantCount > 0;
  const dimensions = root ? rootDimensions(row.value) : null;
  return (
    <div
      ref={register}
      role="treeitem"
      aria-level={row.depth + 1}
      aria-selected={selected}
      aria-expanded={hasChildren ? expanded : undefined}
      className={`te-tree-row${root ? ' is-root' : ''}`}
      data-template-editor-node-id={row.nodeId}
      data-selected={selected}
      data-kind={row.kind}
      onClick={onSelect}
      onKeyDown={onKeyDown}
      style={{
        ...style,
        '--te-tree-indent': `${TEMPLATE_TREE_INDENT_BASE + row.depth * TEMPLATE_TREE_INDENT_STEP}px`,
        '--te-tree-branch': branchColor,
      } as CSSProperties}
      tabIndex={focused ? 0 : -1}
      title={`${row.displayName} · ${presentation.label}`}
    >
      {hasChildren ? (
        <button
          type="button"
          className="te-tree-disclosure"
          aria-expanded={expanded}
          aria-label={`${expanded ? '折叠' : '展开'}${row.displayName}子级`}
          tabIndex={-1}
          onClick={(event) => {
            event.stopPropagation();
            onToggle();
          }}
        >
          <span aria-hidden="true" />
        </button>
      ) : <span className="te-tree-leaf" aria-hidden="true" />}
      <span className="te-tree-kind-icon" aria-hidden="true">
        <Icon size={14} />
      </span>
      <span className="te-tree-copy">
        <strong>{row.displayName}</strong>
        <small>
          {presentation.label}
          {!expanded && hasChildren ? ` · 已折叠 ${row.descendantCount}` : ''}
        </small>
      </span>
      {root ? (
        <span className="te-tree-root-chip">canvas{dimensions ? ` · ${dimensions}` : ''}</span>
      ) : row.childCount > 0 ? (
        <span className="te-tree-count" aria-label={`${row.childCount} 个直接子级`}>
          {row.childCount}
        </span>
      ) : null}
    </div>
  );
}

function ancestorIds(rows: readonly TemplateTreeRow[], nodeId: string): Set<string> {
  const byId = new Map(rows.map((row) => [row.nodeId, row]));
  const ancestors = new Set<string>();
  let current = byId.get(nodeId);
  let remaining = rows.length;
  while (current?.parentNodeId && remaining > 0) {
    ancestors.add(current.parentNodeId);
    current = byId.get(current.parentNodeId);
    remaining -= 1;
  }
  return ancestors;
}

function rootDimensions(value: Record<string, unknown>): string | null {
  return typeof value.widthMm === 'number' && typeof value.heightMm === 'number'
    ? `${formatNumber(value.widthMm)}×${formatNumber(value.heightMm)} mm`
    : null;
}

function formatNumber(value: number): string {
  return Number.isInteger(value) ? String(value) : value.toFixed(2).replace(/0+$/, '').replace(/\.$/, '');
}
