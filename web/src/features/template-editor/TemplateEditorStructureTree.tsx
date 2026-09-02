import {
  ArrowDown,
  ArrowUp,
  Barcode,
  BringToFront,
  Circle,
  Frame,
  Group,
  Hexagon,
  Image,
  Layers,
  LayoutGrid,
  Minus,
  Move,
  PenTool,
  Puzzle,
  QrCode,
  Repeat,
  Search,
  SendToBack,
  Split,
  Square,
  Trash2,
  Type,
  type LucideIcon,
} from 'lucide-react';
import {
  useCallback,
  useEffect,
  useId,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
  type CSSProperties,
  type DragEvent as ReactDragEvent,
  type KeyboardEvent,
  type MouseEvent as ReactMouseEvent,
} from 'react';
import { createPortal } from 'react-dom';

import type { EditorNodeProjection } from './template-editor-model';
import {
  isCoreTemplateAuthoringParentKind,
  isTemplateDesignContainerKind,
} from './template-editor-node-contract';
import { finiteTemplateNumber } from './template-editor-numbers';
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
const TREE_DRAG_MIME = 'application/x-renderweave-template-node';

export type TemplateTreeDropPosition = 'before' | 'into' | 'after';
export type TemplateTreeReorderOperation = 'front' | 'forward' | 'backward' | 'back';

interface TreeContextRequest {
  readonly nodeId: string;
  readonly x: number;
  readonly y: number;
  readonly triggerElement: HTMLElement;
}

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
  disabled = false,
  onSelectNode,
  onRenameNode,
  onMoveNode,
  onReorderNode,
  onDeleteNode,
}: {
  nodes: readonly EditorNodeProjection[];
  selectedNodeId: string;
  disabled?: boolean;
  onSelectNode: (nodeId: string) => void;
  onRenameNode?: (nodeId: string, name: string) => void;
  onMoveNode?: (
    sourceNodeId: string,
    targetNodeId: string,
    position: TemplateTreeDropPosition,
  ) => void;
  onReorderNode?: (nodeId: string, operation: TemplateTreeReorderOperation) => void;
  onDeleteNode?: (nodeId: string) => void;
}) {
  const rows = useMemo(() => buildTemplateTreeRows(nodes), [nodes]);
  const [collapsed, setCollapsed] = useState<ReadonlySet<string>>(() => new Set());
  const [query, setQuery] = useState('');
  const [scrollTop, setScrollTop] = useState(0);
  const [viewportHeight, setViewportHeight] = useState(DEFAULT_TREE_VIEWPORT_HEIGHT);
  const [draggedNodeId, setDraggedNodeId] = useState<string | null>(null);
  const [dropHint, setDropHint] = useState<{
    targetNodeId: string;
    position: TemplateTreeDropPosition;
  } | null>(null);
  const [contextMenu, setContextMenu] = useState<TreeContextRequest | null>(null);
  const rowRefs = useRef(new Map<string, HTMLDivElement>());
  const pendingFocusNodeIdRef = useRef<string | null>(null);
  const pendingContextFocusRestoreRef = useRef<TreeContextRequest | null>(null);
  const treeRef = useRef<HTMLDivElement>(null);
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
  const closeContextMenu = useCallback((restoreFocus: boolean) => {
    setContextMenu((current) => {
      pendingContextFocusRestoreRef.current = restoreFocus ? current : null;
      return null;
    });
  }, []);
  useLayoutEffect(() => {
    if (contextMenu || !pendingContextFocusRestoreRef.current) return;
    const request = pendingContextFocusRestoreRef.current;
    pendingContextFocusRestoreRef.current = null;
    restoreTreeContextFocus({
      request,
      rowElements: rowRefs.current,
      selectedNodeId,
      rootNodeId: rootRow?.nodeId,
      treeElement: treeRef.current,
    });
  }, [contextMenu, rootRow?.nodeId, selectedNodeId]);
  useEffect(() => {
    if (!contextMenu || rows.some((row) => row.nodeId === contextMenu.nodeId)) return;
    closeContextMenu(true);
  }, [closeContextMenu, contextMenu, rows]);
  const openContextMenuAt = (
    row: TemplateTreeRow,
    x: number,
    y: number,
    triggerElement: HTMLElement,
  ) => {
    focusAndSelect(row.nodeId);
    setContextMenu({ nodeId: row.nodeId, x, y, triggerElement });
  };
  const openContextMenu = (
    row: TemplateTreeRow,
    event: ReactMouseEvent<HTMLElement>,
  ) => {
    event.preventDefault();
    event.stopPropagation();
    openContextMenuAt(row, event.clientX, event.clientY, event.currentTarget);
  };
  const openContextMenuFromKeyboard = (row: TemplateTreeRow, triggerElement: HTMLElement) => {
    const bounds = triggerElement.getBoundingClientRect();
    openContextMenuAt(row, bounds.left, bounds.bottom, triggerElement);
  };

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
      <div
        ref={treeRef}
        className="te-tree"
        role="tree"
        aria-label="DesignDSL 结构"
        tabIndex={-1}
      >
        {rootRow ? (
          <TreeRow
            key={`${rootRow.nodeId}:${disabled ? 'locked' : 'editable'}`}
            row={rootRow}
            selected={rootRow.nodeId === selectedNodeId}
            focused={rootRow.nodeId === effectiveFocusedNodeId}
            expanded={!activeCollapsed.has(rootRow.nodeId)}
            branchColor={templateTreeGuideColor(rootRow.nodeId)}
            root
            disabled={disabled}
            canMoveBeside={false}
            register={(element) => registerTreeRow(rootRow.nodeId, element)}
            onKeyDown={(event) => handleTreeKeyDown(event, rootRow.nodeId)}
            onSelect={() => focusAndSelect(rootRow.nodeId)}
            onToggle={() => toggleRow(rootRow)}
            onRenameNode={onRenameNode}
            onMoveNode={onMoveNode}
            draggedNodeId={draggedNodeId}
            dropPosition={dropHint?.targetNodeId === rootRow.nodeId ? dropHint.position : undefined}
            onDragStart={setDraggedNodeId}
            onDragEnd={() => {
              setDraggedNodeId(null);
              setDropHint(null);
            }}
            onDropHint={(position) => setDropHint(position
              ? { targetNodeId: rootRow.nodeId, position }
              : null)}
            onOpenContextMenu={(event) => openContextMenu(rootRow, event)}
            onOpenContextMenuFromKeyboard={(trigger) => openContextMenuFromKeyboard(rootRow, trigger)}
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
                  key={`${row.nodeId}:${disabled ? 'locked' : 'editable'}`}
                  row={row}
                  selected={row.nodeId === selectedNodeId}
                  focused={row.nodeId === effectiveFocusedNodeId}
                  expanded={!activeCollapsed.has(row.nodeId)}
                  branchColor={branchColors.get(row.nodeId) ?? templateTreeGuideColor(row.nodeId)}
                  disabled={disabled}
                  canMoveBeside={canMoveBesideTreeRow(rows, row)}
                  register={(element) => registerTreeRow(row.nodeId, element)}
                  style={{ top: `${index * TEMPLATE_TREE_ROW_HEIGHT}px` }}
                  onKeyDown={(event) => handleTreeKeyDown(event, row.nodeId)}
                  onSelect={() => focusAndSelect(row.nodeId)}
                  onToggle={() => toggleRow(row)}
                  onRenameNode={onRenameNode}
                  onMoveNode={onMoveNode}
                  draggedNodeId={draggedNodeId}
                  dropPosition={dropHint?.targetNodeId === row.nodeId ? dropHint.position : undefined}
                  onDragStart={setDraggedNodeId}
                  onDragEnd={() => {
                    setDraggedNodeId(null);
                    setDropHint(null);
                  }}
                  onDropHint={(position) => setDropHint(position
                    ? { targetNodeId: row.nodeId, position }
                    : null)}
                  onOpenContextMenu={(event) => openContextMenu(row, event)}
                  onOpenContextMenuFromKeyboard={(trigger) => openContextMenuFromKeyboard(row, trigger)}
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
      {contextMenu ? (
        <TreeContextMenu
          key={`${contextMenu.nodeId}:${disabled ? 'locked' : 'editable'}`}
          request={contextMenu}
          rows={rows}
          disabled={disabled}
          onReorderNode={onReorderNode}
          onMoveNode={onMoveNode}
          onDeleteNode={onDeleteNode}
          onClose={closeContextMenu}
        />
      ) : null}
    </div>
  );
}

function TreeRow({
  row,
  selected,
  focused,
  expanded,
  branchColor,
  disabled,
  canMoveBeside,
  root = false,
  register,
  style,
  onKeyDown,
  onSelect,
  onToggle,
  onRenameNode,
  onMoveNode,
  draggedNodeId,
  dropPosition,
  onDragStart,
  onDragEnd,
  onDropHint,
  onOpenContextMenu,
  onOpenContextMenuFromKeyboard,
}: {
  row: TemplateTreeRow;
  selected: boolean;
  focused: boolean;
  expanded: boolean;
  branchColor: string;
  disabled: boolean;
  canMoveBeside: boolean;
  root?: boolean;
  register: (element: HTMLDivElement | null) => void;
  style?: CSSProperties;
  onKeyDown: (event: KeyboardEvent<HTMLDivElement>) => void;
  onSelect: () => void;
  onToggle: () => void;
  onRenameNode?: (nodeId: string, name: string) => void;
  onMoveNode?: (
    sourceNodeId: string,
    targetNodeId: string,
    position: TemplateTreeDropPosition,
  ) => void;
  draggedNodeId: string | null;
  dropPosition?: TemplateTreeDropPosition;
  onDragStart: (nodeId: string) => void;
  onDragEnd: () => void;
  onDropHint: (position: TemplateTreeDropPosition | null) => void;
  onOpenContextMenu: (event: ReactMouseEvent<HTMLElement>) => void;
  onOpenContextMenuFromKeyboard: (triggerElement: HTMLElement) => void;
}) {
  const [renaming, setRenaming] = useState(false);
  const [draftName, setDraftName] = useState(row.displayName);
  const renameInputRef = useRef<HTMLInputElement>(null);
  const renameFinishedRef = useRef(false);
  const presentation = KIND_PRESENTATION[row.kind] ?? { icon: Square, label: row.kind };
  const Icon = presentation.icon;
  const hasChildren = row.descendantCount > 0;
  const isContainer = isTemplateDesignContainerKind(row.kind);
  const canAcceptCoreChildren = isCoreTemplateAuthoringParentKind(row.kind);
  const dimensions = root ? rootDimensions(row.value) : null;

  useEffect(() => {
    if (!renaming) return;
    renameInputRef.current?.focus();
    renameInputRef.current?.select();
  }, [renaming]);

  const beginRename = () => {
    if (disabled || root || !onRenameNode) return;
    renameFinishedRef.current = false;
    setDraftName(row.displayName);
    setRenaming(true);
  };

  const finishRename = (commit: boolean) => {
    if (renameFinishedRef.current) return;
    renameFinishedRef.current = true;
    if (commit) onRenameNode?.(row.nodeId, draftName);
    setRenaming(false);
  };

  return (
    <div
      ref={register}
      role="treeitem"
      aria-level={row.depth + 1}
      aria-selected={selected}
      aria-expanded={hasChildren ? expanded : undefined}
      className={`te-tree-row${root ? ' is-root' : ''}${isContainer ? ' is-container' : ''}`}
      data-template-editor-node-id={row.nodeId}
      data-selected={selected}
      data-kind={row.kind}
      data-container={isContainer || undefined}
      data-drop-position={dropPosition}
      data-dragging={draggedNodeId === row.nodeId || undefined}
      draggable={!disabled && !root && !renaming && Boolean(onMoveNode)}
      onClick={onSelect}
      onContextMenu={onOpenContextMenu}
      onDoubleClick={(event) => {
        if ((event.target as HTMLElement).closest('button, input')) return;
        event.preventDefault();
        event.stopPropagation();
        beginRename();
      }}
      onKeyDown={(event) => {
        if (event.key === 'ContextMenu' || (event.key === 'F10' && event.shiftKey)) {
          event.preventDefault();
          event.stopPropagation();
          onOpenContextMenuFromKeyboard(event.currentTarget);
          return;
        }
        if (event.key === 'F2' && !disabled && !root && onRenameNode) {
          event.preventDefault();
          event.stopPropagation();
          beginRename();
          return;
        }
        onKeyDown(event);
      }}
      onDragStart={(event) => {
        if (disabled || root || !onMoveNode) return;
        event.dataTransfer.effectAllowed = 'move';
        event.dataTransfer.setData(TREE_DRAG_MIME, row.nodeId);
        event.dataTransfer.setData('text/plain', row.nodeId);
        onDragStart(row.nodeId);
      }}
      onDragEnd={onDragEnd}
      onDragOver={(event) => {
        if (disabled || !onMoveNode || !draggedNodeId) return;
        const position = treeDropPosition(
          event,
          canAcceptCoreChildren,
          canMoveBeside,
          root,
        );
        if (!position) {
          event.dataTransfer.dropEffect = 'none';
          onDropHint(null);
          return;
        }
        event.preventDefault();
        event.dataTransfer.dropEffect = 'move';
        onDropHint(position);
      }}
      onDragLeave={(event) => {
        if (!event.currentTarget.contains(event.relatedTarget as Node | null)) onDropHint(null);
      }}
      onDrop={(event) => {
        if (disabled || !onMoveNode) return;
        event.preventDefault();
        event.stopPropagation();
        const sourceNodeId = event.dataTransfer.getData(TREE_DRAG_MIME)
          || event.dataTransfer.getData('text/plain')
          || draggedNodeId;
        const position = dropPosition
          ?? treeDropPosition(event, canAcceptCoreChildren, canMoveBeside, root);
        onDropHint(null);
        onDragEnd();
        if (position && sourceNodeId && sourceNodeId !== row.nodeId) {
          onMoveNode(sourceNodeId, row.nodeId, position);
        }
      }}
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
        {renaming ? (
          <input
            ref={renameInputRef}
            className="te-tree-rename"
            value={draftName}
            aria-label={`重命名 ${row.displayName}`}
            onChange={(event) => setDraftName(event.target.value)}
            onClick={(event) => event.stopPropagation()}
            onDoubleClick={(event) => event.stopPropagation()}
            onBlur={() => finishRename(true)}
            onKeyDown={(event) => {
              event.stopPropagation();
              if (event.key === 'Enter') {
                event.preventDefault();
                finishRename(true);
              } else if (event.key === 'Escape') {
                event.preventDefault();
                finishRename(false);
              }
            }}
          />
        ) : <strong>{row.displayName}</strong>}
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

function TreeContextMenu({
  request,
  rows,
  disabled,
  onReorderNode,
  onMoveNode,
  onDeleteNode,
  onClose,
}: {
  request: TreeContextRequest;
  rows: readonly TemplateTreeRow[];
  disabled: boolean;
  onReorderNode?: (nodeId: string, operation: TemplateTreeReorderOperation) => void;
  onMoveNode?: (
    sourceNodeId: string,
    targetNodeId: string,
    position: TemplateTreeDropPosition,
  ) => void;
  onDeleteNode?: (nodeId: string) => void;
  onClose: (restoreFocus: boolean) => void;
}) {
  const menuRef = useRef<HTMLDivElement>(null);
  const moveSemanticsId = useId();
  const row = rows.find((candidate) => candidate.nodeId === request.nodeId);
  const moveTargets = movableTreeTargets(rows, request.nodeId);
  const initialTargetNodeId = moveTargets.some((candidate) => candidate.nodeId === row?.parentNodeId)
    ? row?.parentNodeId ?? ''
    : moveTargets[0]?.nodeId ?? '';
  const [moveMode, setMoveMode] = useState(false);
  const [targetNodeId, setTargetNodeId] = useState(initialTargetNodeId);
  const [position, setPosition] = useState<TemplateTreeDropPosition>(() => (
    defaultTreeMovePosition(
      rows,
      moveTargets.find((candidate) => candidate.nodeId === initialTargetNodeId),
    )
  ));
  const moveTarget = moveTargets.find((candidate) => candidate.nodeId === targetNodeId);
  const movePositions = treeMovePositionCapabilities(rows, moveTarget);
  const capabilities = treeOrderCapabilities(rows, request.nodeId);
  const isRoot = row?.depth === 0;
  const left = Math.max(
    8,
    Math.min(request.x, (typeof window === 'undefined' ? 1440 : window.innerWidth) - 232),
  );
  const top = Math.max(
    8,
    Math.min(request.y, (typeof window === 'undefined' ? 900 : window.innerHeight) - 300),
  );

  useEffect(() => {
    if (moveMode) {
      menuRef.current?.querySelector<HTMLSelectElement>('select')?.focus();
    } else {
      const firstEnabledAction = menuRef.current?.querySelector<HTMLButtonElement>(
        '[role="menuitem"]:not(:disabled)',
      );
      firstEnabledAction?.focus();
    }
    const dismissFromPointer = (event: PointerEvent) => {
      if (!menuRef.current?.contains(event.target as Node)) onClose(true);
    };
    const dismissFromKeyboard = (event: globalThis.KeyboardEvent) => {
      if (event.key !== 'Escape') return;
      event.preventDefault();
      if (moveMode) setMoveMode(false);
      else onClose(true);
    };
    window.addEventListener('pointerdown', dismissFromPointer, true);
    window.addEventListener('keydown', dismissFromKeyboard);
    const dismissFromEnvironment = () => onClose(true);
    window.addEventListener('resize', dismissFromEnvironment);
    window.addEventListener('scroll', dismissFromEnvironment, true);
    return () => {
      window.removeEventListener('pointerdown', dismissFromPointer, true);
      window.removeEventListener('keydown', dismissFromKeyboard);
      window.removeEventListener('resize', dismissFromEnvironment);
      window.removeEventListener('scroll', dismissFromEnvironment, true);
    };
  }, [moveMode, onClose, request.nodeId]);

  if (!row) return null;
  const presentation = KIND_PRESENTATION[row.kind] ?? { icon: Square, label: row.kind };
  const Icon = presentation.icon;
  const runReorder = (operation: TemplateTreeReorderOperation) => {
    onReorderNode?.(row.nodeId, operation);
    onClose(true);
  };
  const runDelete = () => {
    onDeleteNode?.(row.nodeId);
    onClose(true);
  };
  const runMove = () => {
    if (!moveTarget || !movePositions[position]) return;
    onMoveNode?.(row.nodeId, moveTarget.nodeId, position);
    onClose(true);
  };
  const menu = (
    <div
      ref={menuRef}
      className="te-tree-context-menu"
      role={moveMode ? 'dialog' : 'menu'}
      aria-label={moveMode ? `移动 ${row.displayName}` : `${row.displayName} 操作`}
      style={{ left, top }}
      onContextMenu={(event) => event.preventDefault()}
      onKeyDown={moveMode ? undefined : moveContextMenuFocus}
    >
      <header>
        <span aria-hidden="true"><Icon size={15} /></span>
        <div>
          <strong>{row.displayName}</strong>
          <small>{presentation.label}</small>
        </div>
      </header>
      {moveMode ? (
        <form
          className="te-tree-context-section"
          aria-label="移动节点"
          aria-describedby={moveSemanticsId}
          onSubmit={(event) => {
            event.preventDefault();
            runMove();
          }}
          style={{ gap: 8, padding: 8 }}
        >
          <label style={{ display: 'grid', gap: 4 }}>
            <span>目标节点</span>
            <select
              aria-label="目标节点"
              value={targetNodeId}
              onChange={(event) => {
                const nextTargetNodeId = event.target.value;
                const nextTarget = moveTargets.find(
                  (candidate) => candidate.nodeId === nextTargetNodeId,
                );
                setTargetNodeId(nextTargetNodeId);
                setPosition(defaultTreeMovePosition(rows, nextTarget));
              }}
            >
              {moveTargets.map((candidate) => (
                <option key={candidate.nodeId} value={candidate.nodeId}>
                  {candidate.displayName} · {
                    (KIND_PRESENTATION[candidate.kind] ?? { label: candidate.kind }).label
                  }
                </option>
              ))}
            </select>
          </label>
          <small id={moveSemanticsId}>
            之前/之后与目标同级；移入容器则成为目标的最后一个子级。
          </small>
          <fieldset style={{ display: 'grid', gap: 4, margin: 0, padding: 0, border: 0 }}>
            <legend>放置位置</legend>
            <label>
              <input
                type="radio"
                name="tree-move-position"
                value="before"
                checked={position === 'before'}
                disabled={!movePositions.before}
                onChange={() => setPosition('before')}
              /> 之前
            </label>
            <label>
              <input
                type="radio"
                name="tree-move-position"
                value="into"
                checked={position === 'into'}
                disabled={!movePositions.into}
                onChange={() => setPosition('into')}
              /> 移入容器
            </label>
            <label>
              <input
                type="radio"
                name="tree-move-position"
                value="after"
                checked={position === 'after'}
                disabled={!movePositions.after}
                onChange={() => setPosition('after')}
              /> 之后
            </label>
          </fieldset>
          <div style={{ display: 'flex', gap: 6 }}>
            <button type="button" onClick={() => setMoveMode(false)}>取消</button>
            <button type="submit" disabled={!moveTarget || !movePositions[position]}>
              确认移动
            </button>
          </div>
        </form>
      ) : (
        <>
          <div className="te-tree-context-section" role="group" aria-label="结构位置">
            <button
              type="button"
              role="menuitem"
              disabled={disabled || isRoot || !onMoveNode || moveTargets.length === 0}
              onClick={() => setMoveMode(true)}
            >
              <Move aria-hidden="true" size={15} /><span>移动…</span>
            </button>
          </div>
          <div className="te-tree-context-section" role="group" aria-label="Z 轴顺序">
            <p><span>Z 轴顺序</span><small>同级 children[]</small></p>
            <button
              type="button"
              role="menuitem"
              disabled={disabled || !onReorderNode || !capabilities.front}
              onClick={() => runReorder('front')}
            >
              <BringToFront aria-hidden="true" size={15} /><span>置于顶层</span>
            </button>
            <button
              type="button"
              role="menuitem"
              disabled={disabled || !onReorderNode || !capabilities.forward}
              onClick={() => runReorder('forward')}
            >
              <ArrowUp aria-hidden="true" size={15} /><span>上移一层</span>
            </button>
            <button
              type="button"
              role="menuitem"
              disabled={disabled || !onReorderNode || !capabilities.backward}
              onClick={() => runReorder('backward')}
            >
              <ArrowDown aria-hidden="true" size={15} /><span>下移一层</span>
            </button>
            <button
              type="button"
              role="menuitem"
              disabled={disabled || !onReorderNode || !capabilities.back}
              onClick={() => runReorder('back')}
            >
              <SendToBack aria-hidden="true" size={15} /><span>置于底层</span>
            </button>
          </div>
          <div className="te-tree-context-section is-danger" role="group" aria-label="删除操作">
            <button
              type="button"
              role="menuitem"
              disabled={disabled || isRoot || !onDeleteNode}
              onClick={runDelete}
            >
              <Trash2 aria-hidden="true" size={15} /><span>删除</span>
            </button>
          </div>
        </>
      )}
    </div>
  );
  return typeof document === 'undefined' ? menu : createPortal(menu, document.body);
}

function moveContextMenuFocus(event: KeyboardEvent<HTMLDivElement>) {
  if (!['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) return;
  const actions = [...event.currentTarget.querySelectorAll<HTMLButtonElement>(
    '[role="menuitem"]:not(:disabled)',
  )];
  if (actions.length === 0) return;
  event.preventDefault();
  event.stopPropagation();
  const currentIndex = actions.findIndex((action) => action === document.activeElement);
  const nextIndex = event.key === 'Home'
    ? 0
    : event.key === 'End'
      ? actions.length - 1
      : event.key === 'ArrowUp'
        ? (currentIndex <= 0 ? actions.length - 1 : currentIndex - 1)
        : (currentIndex + 1) % actions.length;
  actions[nextIndex]?.focus();
}

function restoreTreeContextFocus({
  request,
  rowElements,
  selectedNodeId,
  rootNodeId,
  treeElement,
}: {
  request: TreeContextRequest;
  rowElements: ReadonlyMap<string, HTMLDivElement>;
  selectedNodeId: string;
  rootNodeId?: string;
  treeElement: HTMLElement | null;
}): void {
  const candidates = [
    request.triggerElement,
    rowElements.get(request.nodeId),
    rowElements.get(selectedNodeId),
    rootNodeId ? rowElements.get(rootNodeId) : undefined,
    treeElement,
  ];
  const attempted = new Set<HTMLElement>();
  for (const candidate of candidates) {
    if (!candidate || attempted.has(candidate)) continue;
    attempted.add(candidate);
    if (focusConnectedElement(candidate)) return;
  }
}

function focusConnectedElement(element: HTMLElement): boolean {
  if (!element.isConnected) return false;
  element.focus({ preventScroll: true });
  return document.activeElement === element;
}

function movableTreeTargets(
  rows: readonly TemplateTreeRow[],
  sourceNodeId: string,
): TemplateTreeRow[] {
  const sourceIndex = rows.findIndex((candidate) => candidate.nodeId === sourceNodeId);
  const source = rows[sourceIndex];
  if (!source || source.depth === 0) return [];
  const firstDescendantIndex = sourceIndex + 1;
  const afterDescendantsIndex = firstDescendantIndex + source.descendantCount;
  return rows.filter((candidate, index) => (
    candidate.nodeId !== sourceNodeId
    && (index < firstDescendantIndex || index >= afterDescendantsIndex)
  ));
}

function treeMovePositionCapabilities(
  rows: readonly TemplateTreeRow[],
  target: TemplateTreeRow | undefined,
): Record<TemplateTreeDropPosition, boolean> {
  if (!target) return { before: false, into: false, after: false };
  if (target.depth === 0) {
    return {
      before: false,
      into: isCoreTemplateAuthoringParentKind(target.kind),
      after: false,
    };
  }
  const parent = target.parentNodeId
    ? rows.find((candidate) => candidate.nodeId === target.parentNodeId)
    : undefined;
  const canMoveBeside = Boolean(parent && isCoreTemplateAuthoringParentKind(parent.kind));
  return {
    before: canMoveBeside,
    into: isCoreTemplateAuthoringParentKind(target.kind),
    after: canMoveBeside,
  };
}

function defaultTreeMovePosition(
  rows: readonly TemplateTreeRow[],
  target: TemplateTreeRow | undefined,
): TemplateTreeDropPosition {
  const capabilities = treeMovePositionCapabilities(rows, target);
  if (capabilities.into) return 'into';
  if (capabilities.before) return 'before';
  return capabilities.after ? 'after' : 'before';
}

function canMoveBesideTreeRow(
  rows: readonly TemplateTreeRow[],
  row: TemplateTreeRow,
): boolean {
  if (!row.parentNodeId) return false;
  const parent = rows.find((candidate) => candidate.nodeId === row.parentNodeId);
  return Boolean(parent && isCoreTemplateAuthoringParentKind(parent.kind));
}

function treeOrderCapabilities(
  rows: readonly TemplateTreeRow[],
  nodeId: string,
): Record<TemplateTreeReorderOperation, boolean> {
  const row = rows.find((candidate) => candidate.nodeId === nodeId);
  if (!row || row.depth === 0) {
    return { front: false, forward: false, backward: false, back: false };
  }
  const siblings = rows.filter((candidate) => candidate.parentNodeId === row.parentNodeId);
  const index = siblings.findIndex((candidate) => candidate.nodeId === nodeId);
  const canMoveForward = index >= 0 && index < siblings.length - 1;
  const canMoveBackward = index > 0;
  return {
    front: canMoveForward,
    forward: canMoveForward,
    backward: canMoveBackward,
    back: canMoveBackward,
  };
}

function treeDropPosition(
  event: ReactDragEvent<HTMLElement>,
  canMoveInto: boolean,
  canMoveBeside: boolean,
  isRoot: boolean,
): TemplateTreeDropPosition | null {
  if (isRoot) return canMoveInto ? 'into' : null;
  if (!canMoveBeside) return canMoveInto ? 'into' : null;
  const bounds = event.currentTarget.getBoundingClientRect();
  const ratio = bounds.height === 0 ? 0.5 : (event.clientY - bounds.top) / bounds.height;
  if (!canMoveInto) return ratio < 0.5 ? 'before' : 'after';
  if (ratio < 0.28) return 'before';
  if (ratio > 0.72) return 'after';
  return 'into';
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
  const widthMm = finiteTemplateNumber(value.widthMm);
  const heightMm = finiteTemplateNumber(value.heightMm);
  return widthMm !== null && heightMm !== null
    ? `${formatNumber(widthMm)}×${formatNumber(heightMm)} mm`
    : null;
}

function formatNumber(value: number): string {
  return Number.isInteger(value) ? String(value) : value.toFixed(2).replace(/0+$/, '').replace(/\.$/, '');
}
