import {
  useEffect,
  useId,
  useRef,
  useState,
  type ChangeEvent,
  type KeyboardEvent,
  type ReactNode,
} from 'react';

import type { TemplateEditorCommandIntent } from './template-editor-commands';
import { normalizeTemplateEditorDisplayName } from './template-editor-display-name';
import {
  sameTemplateNumber,
  templateNumberDraft,
} from './template-editor-numbers';
import {
  objectOrNull,
  type EditorNodeProjection,
} from './template-editor-model';
import { isCoreTemplateAuthoringKind } from './template-editor-node-contract';
import {
  resolveTemplateAssetRef,
  type TemplateAssetResolution,
  type TemplateEditorAssetKind,
  type TemplateEditorAssetTransport,
} from './template-editor-assets';
import { TemplateEditorAssetPicker } from './TemplateEditorAssetPicker';

type InspectorTab = 'properties' | 'bindings';
type DraftParse<T> = { ok: true; value: T } | { ok: false; problem: string };

const COLOR = /^#[0-9A-Fa-f]{8}$/;

const KIND_LABELS: Readonly<Record<string, string>> = {
  canvas: '画布',
  frame: '框架',
  stack: '堆叠',
  text: '文本',
  image: '图片',
  rect: '矩形',
  ellipse: '椭圆',
  line: '直线',
  polygon: '多边形',
  polyline: '折线',
  path: '路径',
  qrCode: '二维码',
  barcode: '条形码',
};

export interface TemplateEditorInspectorProps {
  readonly node?: EditorNodeProjection;
  readonly disabled: boolean;
  readonly onCommand: (intent: TemplateEditorCommandIntent) => void;
  readonly assetTransport?: TemplateEditorAssetTransport;
  readonly dependencyStaleMessage?: string;
}

/**
 * A command-only inspector. Its local drafts never mutate the projected wire;
 * an authored change can only leave this component as a TemplateEditorCommandIntent.
 */
export function TemplateEditorInspector({
  node,
  disabled,
  onCommand,
  assetTransport,
  dependencyStaleMessage,
}: TemplateEditorInspectorProps) {
  const [tab, setTab] = useState<InspectorTab>('properties');
  const tabsId = useId();

  if (!node) {
    return (
      <section className="te-node-inspector is-empty" aria-label="节点检视器">
        <div className="te-node-inspector-empty">
          <strong>未选择元素</strong>
          <span>从画布或结构中选择一个节点以查看它的已编写属性。</span>
        </div>
      </section>
    );
  }

  const bindings = bindingRecords(node.value.bindings);
  const propertiesTabId = `${tabsId}-properties-tab`;
  const bindingsTabId = `${tabsId}-bindings-tab`;
  const propertiesPanelId = `${tabsId}-properties-panel`;
  const bindingsPanelId = `${tabsId}-bindings-panel`;

  function tabKeyDown(event: KeyboardEvent<HTMLButtonElement>, current: InspectorTab) {
    let next: InspectorTab | null = null;
    if (event.key === 'ArrowRight' || event.key === 'ArrowLeft') {
      next = current === 'properties' ? 'bindings' : 'properties';
    } else if (event.key === 'Home') {
      next = 'properties';
    } else if (event.key === 'End') {
      next = 'bindings';
    }
    if (!next) return;
    event.preventDefault();
    setTab(next);
    document.getElementById(next === 'properties' ? propertiesTabId : bindingsTabId)?.focus();
  }

  return (
    <section
      className="te-node-inspector"
      aria-label={`${node.displayName} 属性检视器`}
      data-node-kind={node.kind}
    >
      <header className="te-node-inspector-heading">
        <span>{KIND_LABELS[node.kind] ?? node.kind}</span>
        <h2 title={node.displayName}>{node.displayName}</h2>
      </header>

      <div className="te-node-inspector-tabs" role="tablist" aria-label="检视器页签">
        <button
          type="button"
          id={propertiesTabId}
          role="tab"
          aria-selected={tab === 'properties'}
          aria-controls={propertiesPanelId}
          tabIndex={tab === 'properties' ? 0 : -1}
          onClick={() => setTab('properties')}
          onKeyDown={(event) => tabKeyDown(event, 'properties')}
        >
          属性
        </button>
        <button
          type="button"
          id={bindingsTabId}
          role="tab"
          aria-selected={tab === 'bindings'}
          aria-controls={bindingsPanelId}
          tabIndex={tab === 'bindings' ? 0 : -1}
          onClick={() => setTab('bindings')}
          onKeyDown={(event) => tabKeyDown(event, 'bindings')}
        >
          绑定 <span aria-label={`${bindings.length} 个绑定`}>{bindings.length}</span>
        </button>
      </div>

      {tab === 'properties' ? (
        <div
          key={propertyRevisionKey(node)}
          id={propertiesPanelId}
          className="te-node-inspector-panel"
          role="tabpanel"
          aria-labelledby={propertiesTabId}
        >
          <PropertiesPanel
            node={node}
            disabled={disabled}
            onCommand={onCommand}
            assetTransport={assetTransport}
            dependencyStaleMessage={dependencyStaleMessage}
          />
        </div>
      ) : (
        <div
          id={bindingsPanelId}
          className="te-node-inspector-panel"
          role="tabpanel"
          aria-labelledby={bindingsTabId}
        >
          <BindingsPanel bindings={bindings} />
        </div>
      )}
    </section>
  );
}

function PropertiesPanel({
  node,
  disabled,
  onCommand,
  assetTransport,
  dependencyStaleMessage,
}: Required<Pick<TemplateEditorInspectorProps, 'node' | 'disabled' | 'onCommand'>>
  & Pick<TemplateEditorInspectorProps, 'assetTransport' | 'dependencyStaleMessage'>) {
  const value = node.value;
  const placement = objectOrNull(value.placement);
  const fill = objectOrNull(value.fill);
  const stroke = objectOrNull(value.stroke);
  const runs = Array.isArray(value.runs)
    ? value.runs.map(objectOrNull).filter((run): run is Record<string, unknown> => run !== null)
    : [];
  const run = runs.length === 1 ? runs[0] : undefined;
  const isCore = isCoreTemplateAuthoringKind(node.kind);
  const isCanvas = node.kind === 'canvas';
  const isFrame = node.kind === 'frame';
  const isStack = node.kind === 'stack';
  const isRect = node.kind === 'rect';
  const isText = node.kind === 'text';
  const isImage = node.kind === 'image';
  const isQrCode = node.kind === 'qrCode';
  const isBarcode = node.kind === 'barcode';
  const hasAbsoluteGeometry = placement?.type === 'ABSOLUTE' && !isCanvas;
  const hasLayoutProperties = isFrame || isStack;
  const hasFill = isFrame || isStack || isRect || node.kind === 'ellipse'
    || node.kind === 'polygon' || node.kind === 'path';
  const hasStroke = isRect || node.kind === 'ellipse' || node.kind === 'line'
    || node.kind === 'polygon' || node.kind === 'polyline' || node.kind === 'path';
  const hasAppearance = isCanvas || hasFill || hasStroke || isImage || isQrCode || isBarcode;
  const fontAssetId = assetIdValue(run?.fontRef);
  const imageAssetId = assetIdValue(value.imageRef);
  const command = (property: Extract<TemplateEditorCommandIntent, { operation: 'set-property' }>['property'], next: unknown) => onCommand({
    operation: 'set-property', nodeId: node.nodeId, property, value: next,
  });

  return (
    <div className="te-node-inspector-groups">
      {isCore && !isCanvas ? (
        <InspectorGroup group="content" title="内容">
          <CommitInput
            label="名称"
            initialValue={stringValue(value.displayName, node.displayName)}
            disabled={disabled}
            parse={parseDisplayName}
            isUnchanged={(next) => next === value.displayName}
            onCommit={(displayName) => onCommand({
              operation: 'rename', nodeId: node.nodeId, displayName,
            })}
          />
          {isText && run ? (
            <CommitInput
              label="文本值"
              initialValue={stringValue(run.text)}
              disabled={disabled}
              parse={parseTextValue}
              isUnchanged={(next) => next === run.text}
              onCommit={(next) => command('text', next)}
            />
          ) : null}
          {isText && !run ? (
            <div className="te-node-inspector-warning" role="status">
              <strong>多 Run 内容保持只读</strong>
              <span>当前版本不会合并、拍平或覆盖这些 Text Run。</span>
            </div>
          ) : null}
          {isQrCode ? (
            <CommitInput
              label="二维码内容"
              initialValue={stringValue(value.content)}
              disabled={disabled}
              parse={(draft) => parseRequiredText(draft, '二维码内容不能为空。')}
              isUnchanged={(next) => next === value.content}
              onCommit={(next) => command('content', next)}
            />
          ) : null}
          {isBarcode ? (
            <CommitInput
              label="条形码值"
              initialValue={stringValue(value.value)}
              disabled={disabled}
              parse={(draft) => parseRequiredText(draft, '条形码值不能为空。')}
              isUnchanged={(next) => next === value.value}
              onCommit={(next) => command('barcodeValue', next)}
            />
          ) : null}
        </InspectorGroup>
      ) : null}

      {isText && run ? (
        <InspectorGroup group="asset" title="字体 Asset">
          <AssetReferenceField
            expectedKind="FONT"
            assetId={fontAssetId}
            disabled={disabled}
            transport={assetTransport}
            dependencyStaleMessage={dependencyStaleMessage}
            onSelect={(assetId) => command('fontRef', assetId)}
          />
        </InspectorGroup>
      ) : isImage ? (
        <InspectorGroup group="asset" title="图片 Asset">
          <AssetReferenceField
            expectedKind="IMAGE"
            assetId={imageAssetId}
            disabled={disabled}
            transport={assetTransport}
            dependencyStaleMessage={dependencyStaleMessage}
            onSelect={(assetId) => command('imageRef', assetId)}
          />
        </InspectorGroup>
      ) : null}

      {isText && run ? (
        <InspectorGroup group="typography" title="文字">
          <div className="te-node-inspector-field-grid">
            <CommitInput
              label="字号"
              suffix="pt"
              inputMode="decimal"
              initialValue={templateNumberDraft(run.fontSizePt)}
              disabled={disabled}
              parse={(draft) => parseNumber(draft, true, '字号必须大于 0。')}
              isUnchanged={(next) => sameTemplateNumber(run.fontSizePt, next)}
              onCommit={(next) => command('fontSizePt', next)}
            />
            <CommitInput
              label="文字颜色"
              initialValue={stringValue(run.color)}
              placeholder="#RRGGBBAA"
              disabled={disabled}
              parse={parseColor}
              isUnchanged={(next) => next === run.color}
              onCommit={(next) => command('textColor', next)}
            />
          </div>
          <label className="te-node-inspector-field">
            <span>装饰</span>
            <select
              aria-label="装饰"
              value={run.decoration === 'UNDERLINE' || run.decoration === 'LINE_THROUGH'
                ? run.decoration : 'NONE'}
              disabled={disabled}
              onChange={(event) => command('decoration', event.currentTarget.value)}
            >
              <option value="NONE">无</option>
              <option value="UNDERLINE">下划线</option>
              <option value="LINE_THROUGH">删除线</option>
            </select>
          </label>
        </InspectorGroup>
      ) : null}

      {hasLayoutProperties ? (
        <InspectorGroup group="layout-constraints" title="布局 / 约束">
          {isStack ? (
            <label className="te-node-inspector-field">
              <span>排列方向</span>
              <select
                value={value.direction === 'ROW' ? 'ROW' : 'COLUMN'}
                disabled={disabled}
                onChange={(event) => onCommand({
                  operation: 'set-property',
                  nodeId: node.nodeId,
                  property: 'direction',
                  value: event.currentTarget.value,
                })}
              >
                <option value="ROW">横向</option>
                <option value="COLUMN">纵向</option>
              </select>
            </label>
          ) : null}
          {isStack ? (
            <CommitInput
              label="间距"
              suffix="mm"
              inputMode="decimal"
              initialValue={templateNumberDraft(value.gapMm)}
              disabled={disabled}
              parse={(draft) => parseNumber(draft, false, '间距必须是非负有限数值。')}
              isUnchanged={(next) => sameTemplateNumber(value.gapMm, next)}
              onCommit={(next) => onCommand({
                operation: 'set-property', nodeId: node.nodeId, property: 'gapMm', value: next,
              })}
            />
          ) : null}
          <label className="te-node-inspector-check">
            <span>
              <strong>裁剪溢出内容</strong>
              <small>子元素不会绘制到容器边界之外</small>
            </span>
            <input
              type="checkbox"
              checked={value.clipContent === true}
              disabled={disabled}
              onChange={(event) => onCommand({
                operation: 'set-property',
                nodeId: node.nodeId,
                property: 'clipContent',
                value: event.currentTarget.checked,
              })}
            />
          </label>
        </InspectorGroup>
      ) : null}

      {isCanvas ? (
        <InspectorGroup group="position-size" title="位置 / 尺寸">
          <div className="te-node-inspector-field-grid">
            <CommitInput
              label="画布宽度"
              suffix="mm"
              inputMode="decimal"
              initialValue={templateNumberDraft(value.widthMm)}
              disabled={disabled}
              parse={(draft) => parseNumber(draft, true, '画布宽度必须大于 0。')}
              isUnchanged={(next) => sameTemplateNumber(value.widthMm, next)}
              onCommit={(next) => onCommand({
                operation: 'set-property', nodeId: node.nodeId,
                property: 'canvasWidthMm', value: next,
              })}
            />
            <CommitInput
              label="画布高度"
              suffix="mm"
              inputMode="decimal"
              initialValue={templateNumberDraft(value.heightMm)}
              disabled={disabled}
              parse={(draft) => parseNumber(draft, true, '画布高度必须大于 0。')}
              isUnchanged={(next) => sameTemplateNumber(value.heightMm, next)}
              onCommit={(next) => onCommand({
                operation: 'set-property', nodeId: node.nodeId,
                property: 'canvasHeightMm', value: next,
              })}
            />
          </div>
        </InspectorGroup>
      ) : hasAbsoluteGeometry && placement ? (
        <InspectorGroup group="position-size" title="位置 / 尺寸">
          <GeometryFields
            nodeId={node.nodeId}
            placement={placement}
            disabled={disabled}
            onCommand={onCommand}
          />
        </InspectorGroup>
      ) : null}

      {hasAppearance ? (
        <InspectorGroup group="appearance" title="外观">
          {isCanvas || hasFill ? (
            <CommitInput
              label={isCanvas ? '背景颜色' : '填充颜色'}
              initialValue={isCanvas
                ? stringValue(value.backgroundColor)
                : stringValue(fill?.color)}
              placeholder="#RRGGBBAA"
              disabled={disabled}
              parse={parseColor}
              isUnchanged={(next) => next === (isCanvas ? value.backgroundColor : fill?.color)}
              onCommit={(next) => command(isCanvas ? 'backgroundColor' : 'fillColor', next)}
            />
          ) : null}
          {hasStroke ? (
            <div className="te-node-inspector-field-grid">
              <CommitInput
                label="描边颜色"
                initialValue={stringValue(stroke?.color)}
                placeholder="#RRGGBBAA"
                disabled={disabled}
                parse={parseColor}
                isUnchanged={(next) => next === stroke?.color}
                onCommit={(next) => command('strokeColor', next)}
              />
              <CommitInput
                label="描边宽度"
                suffix="mm"
                inputMode="decimal"
                initialValue={templateNumberDraft(stroke?.widthMm)}
                disabled={disabled}
                parse={(draft) => parseNumber(draft, true, '描边宽度必须大于 0。')}
                isUnchanged={(next) => sameTemplateNumber(stroke?.widthMm, next)}
                onCommit={(next) => command('strokeWidthMm', next)}
              />
            </div>
          ) : null}
          {isRect ? (
            <CommitInput
              label="圆角"
              suffix="mm"
              inputMode="decimal"
              initialValue={templateNumberDraft(objectOrNull(value.cornerRadii)?.topLeftMm ?? 0)}
              disabled={disabled}
              parse={(draft) => parseNumber(draft, false, '圆角必须是非负有限值。')}
              isUnchanged={(next) => sameTemplateNumber(
                objectOrNull(value.cornerRadii)?.topLeftMm ?? 0,
                next,
              )}
              onCommit={(next) => command('cornerRadiusMm', next)}
            />
          ) : null}
          {isImage ? (
            <>
              <label className="te-node-inspector-field">
                <span>适配</span>
                <select
                  aria-label="图片适配"
                  value={value.fit === 'COVER' || value.fit === 'FILL' ? value.fit : 'CONTAIN'}
                  disabled={disabled}
                  onChange={(event) => command('fit', event.currentTarget.value)}
                >
                  <option value="CONTAIN">完整显示</option>
                  <option value="COVER">覆盖边界</option>
                  <option value="FILL">拉伸填满</option>
                </select>
              </label>
              <label className="te-node-inspector-field">
                <span>采样</span>
                <select
                  aria-label="图片采样"
                  value={value.sampling === 'NEAREST' ? 'NEAREST' : 'LINEAR'}
                  disabled={disabled}
                  onChange={(event) => command('sampling', event.currentTarget.value)}
                >
                  <option value="LINEAR">平滑</option>
                  <option value="NEAREST">邻近像素</option>
                </select>
              </label>
            </>
          ) : null}
          {node.kind === 'path' ? (
            <label className="te-node-inspector-field">
              <span>填充规则</span>
              <select
                aria-label="填充规则"
                value={value.fillRule === 'EVEN_ODD' ? 'EVEN_ODD' : 'NONZERO'}
                disabled={disabled}
                onChange={(event) => command('fillRule', event.currentTarget.value)}
              >
                <option value="NONZERO">非零环绕</option>
                <option value="EVEN_ODD">奇偶</option>
              </select>
            </label>
          ) : null}
          {isQrCode || isBarcode ? (
            <div className="te-node-inspector-field-grid">
              <CommitInput
                label="前景颜色"
                initialValue={stringValue(value.foregroundColor)}
                placeholder="#RRGGBBAA"
                disabled={disabled}
                parse={parseColor}
                isUnchanged={(next) => next === value.foregroundColor}
                onCommit={(next) => command('foregroundColor', next)}
              />
              <CommitInput
                label="背景颜色"
                initialValue={stringValue(value.backgroundColor)}
                placeholder="#RRGGBBAA"
                disabled={disabled}
                parse={parseColor}
                isUnchanged={(next) => next === value.backgroundColor}
                onCommit={(next) => command('backgroundColor', next)}
              />
            </div>
          ) : null}
          {isQrCode ? (
            <label className="te-node-inspector-field">
              <span>纠错级别</span>
              <select
                aria-label="二维码纠错级别"
                value={typeof value.errorCorrectionLevel === 'string'
                  ? value.errorCorrectionLevel : 'M'}
                disabled={disabled}
                onChange={(event) => command('errorCorrectionLevel', event.currentTarget.value)}
              >
                {['L', 'M', 'Q', 'H'].map((level) => <option key={level} value={level}>{level}</option>)}
              </select>
            </label>
          ) : null}
          {isBarcode ? (
            <label className="te-node-inspector-field">
              <span>编码格式</span>
              <select
                aria-label="条形码格式"
                value={typeof value.format === 'string' ? value.format : 'CODE_128'}
                disabled={disabled}
                onChange={(event) => command('format', event.currentTarget.value)}
              >
                {['CODE_128', 'EAN_13', 'EAN_8', 'UPC_A'].map((format) => (
                  <option key={format} value={format}>{format}</option>
                ))}
              </select>
            </label>
          ) : null}
        </InspectorGroup>
      ) : null}

      <InspectorGroup group="advanced" title="高级" defaultOpen={false}>
        <dl className="te-node-inspector-facts">
          <div><dt>节点类型</dt><dd>{KIND_LABELS[node.kind] ?? node.kind}</dd></div>
          <div><dt>nodeId</dt><dd><code title={node.nodeId}>{node.nodeId}</code></dd></div>
          <div><dt>子元素</dt><dd>{node.childCount}</dd></div>
          <div><dt>已有绑定</dt><dd>{bindingRecords(value.bindings).length}</dd></div>
          {placement && typeof placement.type === 'string' ? (
            <div><dt>Placement</dt><dd>{placement.type}</dd></div>
          ) : null}
          {Array.isArray(value.points) ? <div><dt>点数量</dt><dd>{value.points.length}</dd></div> : null}
          {Array.isArray(value.commands) ? <div><dt>Path 命令</dt><dd>{value.commands.length}</dd></div> : null}
        </dl>
      </InspectorGroup>
    </div>
  );
}

function AssetReferenceField({
  expectedKind,
  assetId,
  disabled,
  transport,
  dependencyStaleMessage,
  onSelect,
}: {
  expectedKind: TemplateEditorAssetKind;
  assetId: string | null;
  disabled: boolean;
  transport?: TemplateEditorAssetTransport;
  dependencyStaleMessage?: string;
  onSelect: (assetId: string) => void;
}) {
  const [pickerOpen, setPickerOpen] = useState(false);
  const [resolution, setResolution] = useState<TemplateAssetResolution | null>(null);
  const kindLabel = expectedKind === 'FONT' ? '字体' : '图片';

  useEffect(() => {
    if (!assetId || !transport) {
      let active = true;
      queueMicrotask(() => {
        if (active) setResolution(null);
      });
      return () => {
        active = false;
      };
    }
    const controller = new AbortController();
    queueMicrotask(() => {
      if (!controller.signal.aborted) setResolution(null);
    });
    void resolveTemplateAssetRef(
      { assetId },
      expectedKind,
      transport,
      controller.signal,
    ).then(
      (next) => {
        if (!controller.signal.aborted) setResolution(next);
      },
      () => {
        if (!controller.signal.aborted) {
          setResolution({
            state: 'unavailable',
            ref: { assetId },
            expectedKind,
            code: 'ASSET_REQUEST_UNAVAILABLE',
          });
        }
      },
    );
    return () => controller.abort();
  }, [assetId, expectedKind, transport]);

  return (
    <div className="te-node-inspector-asset">
      <div className="te-node-inspector-asset-summary" aria-live="polite">
        <span>{assetResolutionLabel(resolution, assetId, kindLabel, dependencyStaleMessage)}</span>
        {assetId ? <code title={assetId}>{assetId}</code> : null}
      </div>
      <button
        type="button"
        className="te-node-inspector-asset-action"
        disabled={disabled}
        onClick={() => setPickerOpen(true)}
      >
        {assetId ? `更换${kindLabel} Asset` : `选择${kindLabel} Asset`}
      </button>
      <TemplateEditorAssetPicker
        open={pickerOpen}
        expectedKind={expectedKind}
        selectedAssetId={assetId ?? undefined}
        transport={transport}
        onOpenChange={setPickerOpen}
        onSelect={(selection) => onSelect(selection.ref.assetId)}
      />
    </div>
  );
}

function assetResolutionLabel(
  resolution: TemplateAssetResolution | null,
  assetId: string | null,
  kindLabel: string,
  dependencyStaleMessage?: string,
): string {
  if (!assetId) return `未设置${kindLabel} Asset`;
  if (!resolution) return `正在核验${kindLabel} Asset…`;
  switch (resolution.state) {
    case 'active': return dependencyStaleMessage
      ? `${resolution.asset.displayName} · 当前 Asset ACTIVE；${dependencyStaleMessage}`
      : `${resolution.asset.displayName} · ACTIVE`;
    case 'missing': return `${kindLabel} Asset 不存在；引用已保留`;
    case 'deleted': return `${kindLabel} Asset 已删除；引用已保留`;
    case 'kind-mismatch': return `${kindLabel} Asset 类型不匹配；引用已保留`;
    case 'unavailable': return `${kindLabel} Asset 暂不可核验；引用已保留`;
  }
}

function InspectorGroup({
  group,
  title,
  defaultOpen = true,
  children,
}: {
  group: string;
  title: string;
  defaultOpen?: boolean;
  children: ReactNode;
}) {
  return (
    <details className="te-node-inspector-group" data-inspector-group={group} open={defaultOpen}>
      <summary>{title}</summary>
      <div className="te-node-inspector-group-content">{children}</div>
    </details>
  );
}

function CommitInput<T>({
  label,
  initialValue,
  disabled,
  parse,
  isUnchanged,
  onCommit,
  inputMode,
  suffix,
  placeholder,
}: {
  label: string;
  initialValue: string;
  disabled: boolean;
  parse: (draft: string) => DraftParse<T>;
  isUnchanged: (value: T) => boolean;
  onCommit: (value: T) => void;
  inputMode?: 'text' | 'decimal';
  suffix?: string;
  placeholder?: string;
}) {
  const inputId = useId();
  const problemId = `${inputId}-problem`;
  const [draft, setDraft] = useState(initialValue);
  const [problem, setProblem] = useState<string | null>(null);
  const submittedDraft = useRef<string | null>(null);

  function commit() {
    if (disabled) return;
    const parsed = parse(draft);
    if (!parsed.ok) {
      setProblem(parsed.problem);
      return;
    }
    setProblem(null);
    if (submittedDraft.current === draft) return;
    submittedDraft.current = draft;
    if (!isUnchanged(parsed.value)) onCommit(parsed.value);
  }

  function change(event: ChangeEvent<HTMLInputElement>) {
    setDraft(event.currentTarget.value);
    setProblem(null);
    submittedDraft.current = null;
  }

  function keyDown(event: KeyboardEvent<HTMLInputElement>) {
    if (event.key === 'Enter') {
      event.preventDefault();
      commit();
    } else if (event.key === 'Escape') {
      setDraft(initialValue);
      setProblem(null);
      submittedDraft.current = null;
    }
  }

  return (
    <label className="te-node-inspector-field" htmlFor={inputId}>
      <span>{label}</span>
      <span className="te-node-inspector-input">
        <input
          id={inputId}
          type="text"
          inputMode={inputMode}
          value={draft}
          placeholder={placeholder}
          disabled={disabled}
          aria-label={label}
          aria-invalid={problem !== null}
          aria-describedby={problem ? problemId : undefined}
          onChange={change}
          onBlur={commit}
          onKeyDown={keyDown}
        />
        {suffix ? <span aria-hidden="true">{suffix}</span> : null}
      </span>
      {problem ? <small id={problemId} className="te-node-inspector-problem" role="alert">{problem}</small> : null}
    </label>
  );
}

type GeometryKey = 'xMm' | 'yMm' | 'widthMm' | 'heightMm';
type GeometryDraft = Record<GeometryKey, string>;

function GeometryFields({
  nodeId,
  placement,
  disabled,
  onCommand,
}: {
  nodeId: string;
  placement: Record<string, unknown>;
  disabled: boolean;
  onCommand: (intent: TemplateEditorCommandIntent) => void;
}) {
  const fieldId = useId();
  const fixedWidth = placement.widthMode === 'FIXED';
  const fixedHeight = placement.heightMode === 'FIXED';
  const [draft, setDraft] = useState<GeometryDraft>(() => ({
    xMm: templateNumberDraft(placement.xMm),
    yMm: templateNumberDraft(placement.yMm),
    widthMm: templateNumberDraft(placement.widthMm),
    heightMm: templateNumberDraft(placement.heightMm),
  }));
  const [problems, setProblems] = useState<Partial<Record<GeometryKey, string>>>({});
  const submittedDraft = useRef<string | null>(null);

  function commit() {
    if (disabled) return;
    const parsed = parseGeometry(draft, fixedWidth, fixedHeight);
    setProblems(parsed.problems);
    if (!parsed.geometry) return;
    const signature = JSON.stringify(draft);
    if (submittedDraft.current === signature) return;
    submittedDraft.current = signature;
    if (sameGeometry(parsed.geometry, placement)) return;
    onCommand({ operation: 'set-geometry', nodeId, geometry: parsed.geometry });
  }

  function change(key: GeometryKey, event: ChangeEvent<HTMLInputElement>) {
    const nextValue = event.currentTarget.value;
    setDraft((current) => ({ ...current, [key]: nextValue }));
    setProblems((current) => ({ ...current, [key]: undefined }));
    submittedDraft.current = null;
  }

  function keyDown(event: KeyboardEvent<HTMLInputElement>) {
    if (event.key === 'Enter') {
      event.preventDefault();
      commit();
    }
  }

  const fields: Array<{ key: GeometryKey; label: string }> = [
    { key: 'xMm', label: 'X 坐标' },
    { key: 'yMm', label: 'Y 坐标' },
    ...(fixedWidth ? [{ key: 'widthMm' as const, label: '宽度' }] : []),
    ...(fixedHeight ? [{ key: 'heightMm' as const, label: '高度' }] : []),
  ];

  return (
    <div className="te-node-inspector-field-grid">
      {fields.map((field) => {
        const inputId = `${fieldId}-${field.key}`;
        const problemId = `${inputId}-problem`;
        const problem = problems[field.key];
        return (
          <label key={field.key} className="te-node-inspector-field" htmlFor={inputId}>
            <span>{field.label}</span>
            <span className="te-node-inspector-input">
              <input
                id={inputId}
                type="text"
                inputMode="decimal"
                value={draft[field.key]}
                disabled={disabled}
                aria-label={field.label}
                aria-invalid={problem !== undefined}
                aria-describedby={problem ? problemId : undefined}
                onChange={(event) => change(field.key, event)}
                onBlur={commit}
                onKeyDown={keyDown}
              />
              <span aria-hidden="true">mm</span>
            </span>
            {problem ? <small id={problemId} className="te-node-inspector-problem" role="alert">{problem}</small> : null}
          </label>
        );
      })}
    </div>
  );
}

function BindingsPanel({ bindings }: { bindings: readonly Record<string, unknown>[] }) {
  if (bindings.length === 0) {
    return (
      <div className="te-node-inspector-empty is-compact">
        <strong>暂无绑定</strong>
        <span>该节点的 DesignDSL 当前没有 authored binding。</span>
      </div>
    );
  }

  return (
    <ol className="te-node-inspector-bindings" aria-label="已有绑定">
      {bindings.map((binding, index) => {
        const bindingId = stringValue(binding.bindingId, `binding-${index + 1}`);
        return (
          <li key={`${bindingId}-${index}`}>
            <div>
              <span>目标属性</span>
              <strong>{targetPropertyLabel(binding.targetPropertyRef)}</strong>
            </div>
            <div>
              <span>数据来源</span>
              <strong>{sourceLabel(binding.source)}</strong>
            </div>
            <code title={bindingId}>{bindingId}</code>
          </li>
        );
      })}
    </ol>
  );
}

function parseGeometry(
  draft: GeometryDraft,
  fixedWidth: boolean,
  fixedHeight: boolean,
): {
  geometry?: Extract<TemplateEditorCommandIntent, { operation: 'set-geometry' }>['geometry'];
  problems: Partial<Record<GeometryKey, string>>;
} {
  const problems: Partial<Record<GeometryKey, string>> = {};
  const xMm = finiteDraft(draft.xMm);
  const yMm = finiteDraft(draft.yMm);
  const widthMm = fixedWidth ? positiveDraft(draft.widthMm) : undefined;
  const heightMm = fixedHeight ? positiveDraft(draft.heightMm) : undefined;
  if (xMm === null) problems.xMm = 'X 必须是有限数值。';
  if (yMm === null) problems.yMm = 'Y 必须是有限数值。';
  if (fixedWidth && widthMm === null) problems.widthMm = '宽度必须大于 0。';
  if (fixedHeight && heightMm === null) problems.heightMm = '高度必须大于 0。';
  if (Object.keys(problems).length > 0 || xMm === null || yMm === null) return { problems };
  return {
    problems,
    geometry: {
      xMm,
      yMm,
      ...(fixedWidth && widthMm !== null ? { widthMm } : {}),
      ...(fixedHeight && heightMm !== null ? { heightMm } : {}),
    },
  };
}

function sameGeometry(
  geometry: Extract<TemplateEditorCommandIntent, { operation: 'set-geometry' }>['geometry'],
  placement: Record<string, unknown>,
): boolean {
  return sameTemplateNumber(placement.xMm, geometry.xMm)
    && sameTemplateNumber(placement.yMm, geometry.yMm)
    && (geometry.widthMm === undefined
      || sameTemplateNumber(placement.widthMm, geometry.widthMm))
    && (geometry.heightMm === undefined
      || sameTemplateNumber(placement.heightMm, geometry.heightMm));
}

function parseDisplayName(draft: string): DraftParse<string> {
  const result = normalizeTemplateEditorDisplayName(draft);
  if (result.state === 'invalid') {
    return { ok: false, problem: '名称必须是 1–128 个有效字符。' };
  }
  return { ok: true, value: result.value };
}

function parseTextValue(draft: string): DraftParse<string> {
  for (const character of draft) {
    const code = character.codePointAt(0) ?? 0;
    if (code < 0x20 && code !== 0x0a) {
      return { ok: false, problem: '文本只允许 LF 换行，不允许其他控制字符。' };
    }
  }
  return { ok: true, value: draft };
}

function parseRequiredText(draft: string, problem: string): DraftParse<string> {
  const parsed = parseTextValue(draft);
  if (!parsed.ok) return parsed;
  return draft.length > 0 ? parsed : { ok: false, problem };
}

function parseColor(draft: string): DraftParse<string> {
  const value = draft.trim();
  return COLOR.test(value)
    ? { ok: true, value: value.toUpperCase() }
    : { ok: false, problem: '请输入 #RRGGBBAA 格式的颜色。' };
}

function parseNumber(draft: string, positive: boolean, problem: string): DraftParse<number> {
  const value = finiteDraft(draft);
  if (value === null || (positive ? value <= 0 : value < 0)) return { ok: false, problem };
  return { ok: true, value };
}

function finiteDraft(draft: string): number | null {
  if (draft.trim().length === 0) return null;
  const value = Number(draft);
  return Number.isFinite(value) ? value : null;
}

function positiveDraft(draft: string): number | null {
  const value = finiteDraft(draft);
  return value !== null && value > 0 ? value : null;
}

function bindingRecords(value: unknown): readonly Record<string, unknown>[] {
  return Array.isArray(value)
    ? value.map(objectOrNull).filter((entry): entry is Record<string, unknown> => entry !== null)
    : [];
}

function assetIdValue(value: unknown): string | null {
  const ref = objectOrNull(value);
  return typeof ref?.assetId === 'string' && ref.assetId.length > 0 ? ref.assetId : null;
}

function targetPropertyLabel(value: unknown): string {
  const target = objectOrNull(value);
  if (!target) return '未知目标';
  let label = stringValue(target.rootPropertyId, '未知目标');
  if (!Array.isArray(target.selectors)) return label;
  for (const candidate of target.selectors) {
    const selector = objectOrNull(candidate);
    if (selector?.kind === 'member' && typeof selector.name === 'string') {
      label += `.${selector.name}`;
    } else if (selector?.kind === 'index') {
      const index = typeof selector.index === 'string'
        ? selector.index
        : templateNumberDraft(selector.index);
      if (index.length > 0) label += `[${index}]`;
    }
  }
  return label;
}

function sourceLabel(value: unknown): string {
  const source = objectOrNull(value);
  if (!source || typeof source.kind !== 'string') return '未知来源';
  switch (source.kind) {
    case 'literal':
      return `字面量 · ${valueTypeLabel(source.valueType)}`;
    case 'context':
      return `上下文 · ${compactParts(source.domain, source.pointer)}`;
    case 'definition':
      return `定义 · ${stringValue(source.definitionId, '未指定')}`;
    case 'loopIndex':
      return `循环索引 · ${stringValue(source.loopId, '未指定')}`;
    case 'capability':
      return `能力 · ${compactParts(source.capability, source.operation)}`;
    default:
      return source.kind;
  }
}

function valueTypeLabel(value: unknown): string {
  if (typeof value === 'string') return value;
  const type = objectOrNull(value);
  return type && typeof type.type === 'string' ? type.type : '未指定类型';
}

function compactParts(...values: unknown[]): string {
  const parts = values.filter((value): value is string => typeof value === 'string' && value.length > 0);
  return parts.length > 0 ? parts.join(' / ') : '未指定';
}

function propertyRevisionKey(node: EditorNodeProjection): string {
  return JSON.stringify([node.nodeId, node.kind, node.value, node.childCount]);
}

function stringValue(value: unknown, fallback = ''): string {
  return typeof value === 'string' ? value : fallback;
}
