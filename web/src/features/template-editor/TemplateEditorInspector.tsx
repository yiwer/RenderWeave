import * as Dialog from '@radix-ui/react-dialog';
import { Link2, Trash2, X } from 'lucide-react';
import {
  useEffect,
  useId,
  useRef,
  useState,
  type ChangeEvent,
  type KeyboardEvent,
  type ReactNode,
} from 'react';

import { SelectField, type SelectFieldOption } from '../../components/SelectField';
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
import {
  isCoreTemplateAuthoringKind,
  isTemplateNodeSizeModeAllowed,
} from './template-editor-node-contract';
import {
  resolveTemplateAssetRef,
  type TemplateAssetResolution,
  type TemplateEditorAssetKind,
  type TemplateEditorAssetTransport,
} from './template-editor-assets';
import { TemplateEditorAssetPicker } from './TemplateEditorAssetPicker';
import type { StaticSnapshot } from '../schema-studio/lossless-api';
import {
  bindingValueTypeLabel,
  projectBindableProperties,
  projectBindingSources,
  type TemplateBindableProperty,
  type TemplateBindingSource,
  type TemplateDataAuthoringContext,
  type TemplateDataAuthoringIntent,
} from './template-editor-data-authoring';
import type { TemplateStaticSchemaView } from './TemplateEditorDataSources';
import type { TemplateBindingFocusDescriptor } from './template-problem-locator';
import { decodeTemplateTargetPropertyRef } from './template-target-property-ref';
import {
  loadExactTemplateStaticSchema,
  projectPendingTemplateStaticSchemaReferences,
  type TemplateStaticSchemaPendingReference,
  type TemplateStaticSchemaTransport,
} from './template-editor-static-schema';
import {
  formatTemplateGridTracks,
  isTemplateGridTrackList,
  parseTemplateGridTracks,
  type TemplateGridTrack,
} from './template-editor-grid-tracks';
import { canonicalTemplateDecimal } from './template-canonical-decimal';

type InspectorTab = 'properties' | 'bindings';
type DraftParse<T> = { ok: true; value: T } | { ok: false; problem: string };

const COLOR = /^#[0-9A-Fa-f]{8}$/;

const STACK_JUSTIFICATION_OPTIONS: SelectFieldOption[] = [
  { value: 'START', label: '起始' },
  { value: 'CENTER', label: '居中' },
  { value: 'END', label: '末端' },
  { value: 'SPACE_BETWEEN', label: '两端对齐' },
  { value: 'SPACE_AROUND', label: '环绕均分' },
  { value: 'SPACE_EVENLY', label: '等距均分' },
];

const SELF_ALIGNMENT_OPTIONS: SelectFieldOption[] = [
  { value: 'START', label: '起始' },
  { value: 'CENTER', label: '居中' },
  { value: 'END', label: '末端' },
];

const OPTIONAL_SELF_ALIGNMENT_OPTIONS: SelectFieldOption[] = [
  { value: '', label: '默认（起始）' },
  ...SELF_ALIGNMENT_OPTIONS,
];

const KIND_LABELS: Readonly<Record<string, string>> = {
  canvas: '画布',
  group: '自由分组',
  frame: '框架',
  stack: '堆叠',
  grid: '网格',
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
  readonly projectedSizeMm?: Readonly<{ widthMm: number; heightMm: number }>;
  readonly disabled: boolean;
  readonly onCommand: (intent: TemplateEditorCommandIntent) => void;
  readonly assetTransport?: TemplateEditorAssetTransport;
  readonly dependencyStaleMessage?: string;
  readonly designDsl?: Readonly<Record<string, unknown>>;
  readonly staticSchema?: TemplateStaticSchemaView;
  readonly staticSchemaTransport?: TemplateStaticSchemaTransport;
  readonly onDataIntent?: (
    intent: TemplateDataAuthoringIntent,
    context?: TemplateDataAuthoringContext,
  ) => boolean | void;
  readonly problemFocus?: TemplateEditorInspectorFocusRequest;
  readonly onProblemFocusResult?: (requestId: number, focused: boolean) => void;
}

export interface TemplateEditorInspectorFocusRequest {
  readonly requestId: number;
  readonly nodeId: string;
  readonly mode: 'binding' | 'property';
  readonly focus: TemplateBindingFocusDescriptor;
}

/**
 * A command-only inspector. Its local drafts never mutate the projected wire;
 * an authored change can only leave this component as a TemplateEditorCommandIntent.
 */
export function TemplateEditorInspector({
  node,
  projectedSizeMm,
  disabled,
  onCommand,
  assetTransport,
  dependencyStaleMessage,
  designDsl,
  staticSchema,
  staticSchemaTransport,
  onDataIntent,
  problemFocus,
  onProblemFocusResult,
}: TemplateEditorInspectorProps) {
  const [tab, setTab] = useState<InspectorTab>('properties');
  const [bindingProperty, setBindingProperty] = useState<TemplateBindableProperty | null>(null);
  const tabsId = useId();
  const inspectorRef = useRef<HTMLElement>(null);
  const handledProblemFocus = useRef<number | null>(null);

  useEffect(() => {
    if (!problemFocus || handledProblemFocus.current === problemFocus.requestId) return;
    if (!node || node.nodeId !== problemFocus.nodeId) {
      handledProblemFocus.current = problemFocus.requestId;
      onProblemFocusResult?.(problemFocus.requestId, false);
      return;
    }

    const targetTab: InspectorTab = problemFocus.mode === 'binding' ? 'bindings' : 'properties';
    if (tab !== targetTab) {
      let active = true;
      queueMicrotask(() => {
        if (active) setTab(targetTab);
      });
      return () => {
        active = false;
      };
    }

    const root = inspectorRef.current;
    const matches = root
      ? [...root.querySelectorAll<HTMLElement>(
        problemFocus.mode === 'binding'
          ? '[data-template-binding-id]'
          : '[data-template-property-path]',
      )].filter((candidate) => (
        problemFocus.mode === 'binding'
          ? candidate.dataset.templateBindingId === problemFocus.focus.bindingId
          : candidate.dataset.templatePropertyPath === problemFocus.focus.propertyPath
      ))
      : [];
    const target = matches.length === 1 ? matches[0] : undefined;
    handledProblemFocus.current = problemFocus.requestId;
    let focused = false;
    if (target) {
      let disclosure = target.closest<HTMLDetailsElement>('details');
      while (disclosure) {
        disclosure.open = true;
        disclosure = disclosure.parentElement?.closest<HTMLDetailsElement>('details') ?? null;
      }
      target.focus({ preventScroll: true });
      target.scrollIntoView?.({ block: 'nearest', inline: 'nearest' });
      focused = target.ownerDocument.activeElement === target;
    }
    onProblemFocusResult?.(problemFocus.requestId, focused);
  }, [node, onProblemFocusResult, problemFocus, tab]);

  if (!node) {
    return (
      <section ref={inspectorRef} className="te-node-inspector is-empty" aria-label="节点检视器">
        <div className="te-node-inspector-empty">
          <strong>未选择元素</strong>
          <span>从画布或结构中选择一个节点以查看它的已编写属性。</span>
        </div>
      </section>
    );
  }

  const bindings = bindingRecords(node.value.bindings);
  const bindableProperties = onDataIntent && staticSchema?.state === 'ready'
    ? projectBindableProperties(node.value)
    : [];
  const bindableByPath = new Map(bindableProperties.map((property) => [property.propertyPath, property]));
  const bindingAction = (path: string) => {
    const property = bindableByPath.get(path);
    if (!property) return undefined;
    if (property.bindingId) {
      return (
        <button
          type="button"
          className="te-property-binding-action is-bound"
          aria-label={`查看${property.label}绑定`}
          onClick={() => setTab('bindings')}
        >
          <Link2 aria-hidden="true" size={13} />已绑定
        </button>
      );
    }
    return (
      <button
        type="button"
        className="te-property-binding-action"
        disabled={disabled}
        aria-label={`绑定${property.label}`}
        onClick={() => setBindingProperty(property)}
      >
        <Link2 aria-hidden="true" size={13} />绑定
      </button>
    );
  };
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
      ref={inspectorRef}
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
            projectedSizeMm={projectedSizeMm}
            disabled={disabled}
            onCommand={onCommand}
            assetTransport={assetTransport}
            dependencyStaleMessage={dependencyStaleMessage}
            bindingAction={bindingAction}
          />
        </div>
      ) : (
        <div
          id={bindingsPanelId}
          className="te-node-inspector-panel"
          role="tabpanel"
          aria-labelledby={bindingsTabId}
        >
          <BindingsPanel
            bindings={bindings}
            disabled={disabled}
            onRemove={onDataIntent
              ? (bindingId) => onDataIntent({
                operation: 'remove-binding', nodeId: node.nodeId, bindingId,
              })
              : undefined}
          />
        </div>
      )}
      {bindingProperty && designDsl && staticSchema?.state === 'ready' && onDataIntent ? (
        <BindingEditorDialog
          property={bindingProperty}
          designDsl={designDsl}
          staticSchema={staticSchema.snapshot}
          staticSchemaTransport={staticSchemaTransport}
          nodeId={node.nodeId}
          onClose={() => setBindingProperty(null)}
          onSubmit={(source, staticSchemas) => {
            const intent: TemplateDataAuthoringIntent = {
              operation: 'create-binding',
              nodeId: node.nodeId,
              propertyPath: bindingProperty.propertyPath,
              source,
            };
            const result = staticSchemas.length > 1
              ? onDataIntent(intent, { staticSchemas })
              : onDataIntent(intent);
            if (result !== false) setBindingProperty(null);
          }}
        />
      ) : null}
    </section>
  );
}

function PropertiesPanel({
  node,
  projectedSizeMm,
  disabled,
  onCommand,
  assetTransport,
  dependencyStaleMessage,
  bindingAction,
}: Required<Pick<TemplateEditorInspectorProps, 'node' | 'disabled' | 'onCommand'>>
  & Pick<TemplateEditorInspectorProps, 'assetTransport' | 'dependencyStaleMessage' | 'projectedSizeMm'>
  & { bindingAction: (path: string) => ReactNode }) {
  const value = node.value;
  const placement = objectOrNull(value.placement);
  const fill = objectOrNull(value.fill);
  const stroke = objectOrNull(value.stroke);
  const padding = objectOrNull(value.padding);
  const runs = Array.isArray(value.runs)
    ? value.runs.map(objectOrNull).filter((run): run is Record<string, unknown> => run !== null)
    : [];
  const run = runs.length === 1 ? runs[0] : undefined;
  const isCore = isCoreTemplateAuthoringKind(node.kind);
  const isCanvas = node.kind === 'canvas';
  const isGroup = node.kind === 'group';
  const isFrame = node.kind === 'frame';
  const isStack = node.kind === 'stack';
  const isGrid = node.kind === 'grid';
  const isRect = node.kind === 'rect';
  const isText = node.kind === 'text';
  const isImage = node.kind === 'image';
  const isQrCode = node.kind === 'qrCode';
  const isBarcode = node.kind === 'barcode';
  const hasAbsoluteGeometry = placement?.type === 'ABSOLUTE' && !isCanvas;
  const hasManagedPlacement = placement?.type === 'STACK' || placement?.type === 'GRID';
  const hasLayoutProperties = isFrame || isStack || isGrid;
  const hasFill = isFrame || isStack || isGrid || isRect || node.kind === 'ellipse'
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
            propertyPath="runs[0].text"
            action={bindingAction('runs[0].text')}
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
              propertyPath="content"
              action={bindingAction('content')}
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
              propertyPath="value"
              action={bindingAction('value')}
            />
          ) : null}
        </InspectorGroup>
      ) : null}

      {isText && run ? (
        <InspectorGroup group="asset" title="字体 Asset">
          <BindablePropertyRow path="runs[0].fontRef" action={bindingAction('runs[0].fontRef')}>
            <AssetReferenceField
              expectedKind="FONT"
              assetId={fontAssetId}
              disabled={disabled}
              transport={assetTransport}
              dependencyStaleMessage={dependencyStaleMessage}
              onSelect={(assetId) => command('fontRef', assetId)}
            />
          </BindablePropertyRow>
        </InspectorGroup>
      ) : isImage ? (
        <InspectorGroup group="asset" title="图片 Asset">
          <BindablePropertyRow path="imageRef" action={bindingAction('imageRef')}>
            <AssetReferenceField
              expectedKind="IMAGE"
              assetId={imageAssetId}
              disabled={disabled}
              transport={assetTransport}
              dependencyStaleMessage={dependencyStaleMessage}
              onSelect={(assetId) => command('imageRef', assetId)}
            />
          </BindablePropertyRow>
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
              propertyPath="runs[0].fontSizePt"
              action={bindingAction('runs[0].fontSizePt')}
            />
            <CommitInput
              label="文字颜色"
              initialValue={stringValue(run.color)}
              placeholder="#RRGGBBAA"
              disabled={disabled}
              parse={parseColor}
              isUnchanged={(next) => next === run.color}
              onCommit={(next) => command('textColor', next)}
              propertyPath="runs[0].color"
              action={bindingAction('runs[0].color')}
            />
          </div>
          <BindablePropertyRow path="runs[0].decoration" action={bindingAction('runs[0].decoration')}>
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
          </BindablePropertyRow>
        </InspectorGroup>
      ) : null}

      {hasLayoutProperties ? (
        <InspectorGroup group="container-layout" title="布局模式与容器设置">
          {isStack ? (
            <BindablePropertyRow path="direction" action={bindingAction('direction')}>
              <label className="te-node-inspector-field">
                <span>排列方向</span>
                <select
                  aria-label="排列方向"
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
            </BindablePropertyRow>
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
              propertyPath="gapMm"
              action={bindingAction('gapMm')}
            />
          ) : null}
          {isStack ? (
            <>
              <BindablePropertyRow
                path="justifyContent"
                action={bindingAction('justifyContent')}
              >
                <label className="te-node-inspector-field">
                  <span>主轴分布</span>
                  <SelectField
                    ariaLabel="主轴分布"
                    value={stackJustification(value.justifyContent)}
                    disabled={disabled}
                    options={STACK_JUSTIFICATION_OPTIONS}
                    onChange={(next) => command('justifyContent', next)}
                  />
                </label>
              </BindablePropertyRow>
              <BindablePropertyRow path="alignItems" action={bindingAction('alignItems')}>
                <label className="te-node-inspector-field">
                  <span>交叉轴对齐</span>
                  <SelectField
                    ariaLabel="交叉轴对齐"
                    value={selfAlignment(value.alignItems)}
                    disabled={disabled}
                    options={SELF_ALIGNMENT_OPTIONS}
                    onChange={(next) => command('alignItems', next)}
                  />
                </label>
              </BindablePropertyRow>
            </>
          ) : null}
          {isGrid ? (
            <>
              <CommitInput
                label="列轨道"
                initialValue={gridTrackDraft(value.columns)}
                disabled={disabled}
                parse={parseGridTrackDraft}
                isUnchanged={(next) => sameGridTracks(value.columns, next)}
                onCommit={(next) => command('columns', next)}
              />
              <CommitInput
                label="行轨道"
                initialValue={gridTrackDraft(value.rows)}
                disabled={disabled}
                parse={parseGridTrackDraft}
                isUnchanged={(next) => sameGridTracks(value.rows, next)}
                onCommit={(next) => command('rows', next)}
              />
              <div className="te-node-inspector-field-grid">
                <CommitInput
                  label="列间距"
                  suffix="mm"
                  inputMode="decimal"
                  initialValue={templateNumberDraft(value.columnGapMm ?? 0)}
                  disabled={disabled}
                  parse={(draft) => parseNumber(draft, false, '列间距必须是非负有限数值。')}
                  isUnchanged={(next) => sameTemplateNumber(value.columnGapMm ?? 0, next)}
                  onCommit={(next) => command('columnGapMm', next)}
                  propertyPath="columnGapMm"
                  action={bindingAction('columnGapMm')}
                />
                <CommitInput
                  label="行间距"
                  suffix="mm"
                  inputMode="decimal"
                  initialValue={templateNumberDraft(value.rowGapMm ?? 0)}
                  disabled={disabled}
                  parse={(draft) => parseNumber(draft, false, '行间距必须是非负有限数值。')}
                  isUnchanged={(next) => sameTemplateNumber(value.rowGapMm ?? 0, next)}
                  onCommit={(next) => command('rowGapMm', next)}
                  propertyPath="rowGapMm"
                  action={bindingAction('rowGapMm')}
                />
              </div>
            </>
          ) : null}
          <BindablePropertyRow path="clipContent" action={bindingAction('clipContent')}>
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
          </BindablePropertyRow>
        </InspectorGroup>
      ) : null}

      {hasLayoutProperties ? (
        <InspectorGroup group="padding" title="内边距">
          <div className="te-node-inspector-field-grid">
            <CommitInput
              label="上内边距"
              suffix="mm"
              inputMode="decimal"
              initialValue={templateNumberDraft(padding?.topMm ?? 0)}
              disabled={disabled}
              parse={(draft) => parseNumber(draft, false, '上内边距必须是非负有限数值。')}
              isUnchanged={(next) => sameTemplateNumber(padding?.topMm ?? 0, next)}
              onCommit={(next) => command('paddingTopMm', next)}
              propertyPath="padding.topMm"
              action={bindingAction('padding.topMm')}
            />
            <CommitInput
              label="右内边距"
              suffix="mm"
              inputMode="decimal"
              initialValue={templateNumberDraft(padding?.rightMm ?? 0)}
              disabled={disabled}
              parse={(draft) => parseNumber(draft, false, '右内边距必须是非负有限数值。')}
              isUnchanged={(next) => sameTemplateNumber(padding?.rightMm ?? 0, next)}
              onCommit={(next) => command('paddingRightMm', next)}
              propertyPath="padding.rightMm"
              action={bindingAction('padding.rightMm')}
            />
            <CommitInput
              label="下内边距"
              suffix="mm"
              inputMode="decimal"
              initialValue={templateNumberDraft(padding?.bottomMm ?? 0)}
              disabled={disabled}
              parse={(draft) => parseNumber(draft, false, '下内边距必须是非负有限数值。')}
              isUnchanged={(next) => sameTemplateNumber(padding?.bottomMm ?? 0, next)}
              onCommit={(next) => command('paddingBottomMm', next)}
              propertyPath="padding.bottomMm"
              action={bindingAction('padding.bottomMm')}
            />
            <CommitInput
              label="左内边距"
              suffix="mm"
              inputMode="decimal"
              initialValue={templateNumberDraft(padding?.leftMm ?? 0)}
              disabled={disabled}
              parse={(draft) => parseNumber(draft, false, '左内边距必须是非负有限数值。')}
              isUnchanged={(next) => sameTemplateNumber(padding?.leftMm ?? 0, next)}
              onCommit={(next) => command('paddingLeftMm', next)}
              propertyPath="padding.leftMm"
              action={bindingAction('padding.leftMm')}
            />
          </div>
        </InspectorGroup>
      ) : null}

      {hasManagedPlacement && placement ? (
        <InspectorGroup group="child-constraints" title={placement.type === 'STACK'
          ? '堆叠子项约束'
          : '网格子项约束'}>
          <ManagedPlacementFields
            nodeId={node.nodeId}
            nodeKind={node.kind}
            projectedSizeMm={projectedSizeMm}
            placement={placement}
            disabled={disabled}
            onCommand={onCommand}
            bindingAction={bindingAction}
          />
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
          <PlacementSizeConstraintFields
            nodeId={node.nodeId}
            nodeKind={node.kind}
            projectedSizeMm={projectedSizeMm}
            placement={placement}
            disabled={disabled}
            onCommand={onCommand}
            bindingAction={bindingAction}
            includeFixedSizes={false}
            allowMinMax={!isGroup}
            lockHugModes={isGroup}
          />
          <GeometryFields
            nodeId={node.nodeId}
            placement={placement}
            disabled={disabled}
            onCommand={onCommand}
            bindingAction={bindingAction}
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
              propertyPath={isCanvas ? 'backgroundColor' : 'fill.color'}
              action={bindingAction(isCanvas ? 'backgroundColor' : 'fill.color')}
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
                propertyPath="stroke.color"
                action={bindingAction('stroke.color')}
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
                propertyPath="stroke.widthMm"
                action={bindingAction('stroke.widthMm')}
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
              propertyPath="cornerRadii.topLeftMm"
              action={bindingAction('cornerRadii.topLeftMm')}
            />
          ) : null}
          {isImage ? (
            <>
              <BindablePropertyRow path="fit" action={bindingAction('fit')}>
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
              </BindablePropertyRow>
              <BindablePropertyRow path="sampling" action={bindingAction('sampling')}>
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
              </BindablePropertyRow>
            </>
          ) : null}
          {node.kind === 'path' ? (
            <BindablePropertyRow path="fillRule" action={bindingAction('fillRule')}>
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
            </BindablePropertyRow>
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
                propertyPath="foregroundColor"
                action={bindingAction('foregroundColor')}
              />
              <CommitInput
                label="背景颜色"
                initialValue={stringValue(value.backgroundColor)}
                placeholder="#RRGGBBAA"
                disabled={disabled}
                parse={parseColor}
                isUnchanged={(next) => next === value.backgroundColor}
                onCommit={(next) => command('backgroundColor', next)}
                propertyPath="backgroundColor"
                action={bindingAction('backgroundColor')}
              />
            </div>
          ) : null}
          {isQrCode ? (
            <BindablePropertyRow path="errorCorrectionLevel" action={bindingAction('errorCorrectionLevel')}>
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
            </BindablePropertyRow>
          ) : null}
          {isBarcode ? (
            <BindablePropertyRow path="format" action={bindingAction('format')}>
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
            </BindablePropertyRow>
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

function BindablePropertyRow({
  path,
  action,
  children,
}: {
  path: string;
  action?: ReactNode;
  children: ReactNode;
}) {
  return (
    <div className="te-node-inspector-property-row" data-template-property-path={path} tabIndex={-1}>
      {children}
      {action}
    </div>
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
  propertyPath,
  action,
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
  propertyPath?: string;
  action?: ReactNode;
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
    <div
      className="te-node-inspector-property-row"
      data-template-property-path={propertyPath}
      tabIndex={propertyPath ? -1 : undefined}
    >
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
      {action}
    </div>
  );
}

type GeometryKey = 'xMm' | 'yMm' | 'widthMm' | 'heightMm';
type GeometryDraft = Record<GeometryKey, string>;

function GeometryFields({
  nodeId,
  placement,
  disabled,
  onCommand,
  bindingAction,
}: {
  nodeId: string;
  placement: Record<string, unknown>;
  disabled: boolean;
  onCommand: (intent: TemplateEditorCommandIntent) => void;
  bindingAction: (path: string) => ReactNode;
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
        const propertyPath = `placement.${field.key}`;
        return (
          <BindablePropertyRow key={field.key} path={propertyPath} action={bindingAction(propertyPath)}>
            <label className="te-node-inspector-field" htmlFor={inputId}>
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
          </BindablePropertyRow>
        );
      })}
    </div>
  );
}

function ManagedPlacementFields({
  nodeId,
  nodeKind,
  projectedSizeMm,
  placement,
  disabled,
  onCommand,
  bindingAction,
}: {
  nodeId: string;
  nodeKind: string;
  projectedSizeMm?: Readonly<{ widthMm: number; heightMm: number }>;
  placement: Record<string, unknown>;
  disabled: boolean;
  onCommand: (intent: TemplateEditorCommandIntent) => void;
  bindingAction: (path: string) => ReactNode;
}) {
  const command = (
    property: Extract<TemplateEditorCommandIntent, { operation: 'set-property' }>['property'],
    value: unknown,
  ) => onCommand({ operation: 'set-property', nodeId, property, value });
  return (
    <>
      <PlacementSizeConstraintFields
        nodeId={nodeId}
        nodeKind={nodeKind}
        projectedSizeMm={projectedSizeMm}
        placement={placement}
        disabled={disabled}
        onCommand={onCommand}
        bindingAction={bindingAction}
        allowMinMax={nodeKind !== 'group'}
        lockHugModes={nodeKind === 'group'}
      />
      <div className="te-node-inspector-field-grid">
        {([
          ['marginTopMm', '上外边距'],
          ['marginRightMm', '右外边距'],
          ['marginBottomMm', '下外边距'],
          ['marginLeftMm', '左外边距'],
        ] as const).map(([property, label]) => (
          <CommitInput
            key={property}
            label={label}
            suffix="mm"
            inputMode="decimal"
            initialValue={templateNumberDraft(placement[property] ?? 0)}
            disabled={disabled}
            parse={(draft) => parseOptionalFiniteNumber(draft, `${label}必须是有限数值。`)}
            isUnchanged={(next) => next === null
              ? placement[property] === undefined
              : sameTemplateNumber(placement[property] ?? 0, next)}
            onCommit={(next) => command(property, next)}
            propertyPath={`placement.${property}`}
            action={bindingAction(`placement.${property}`)}
          />
        ))}
      </div>
      {placement.type === 'STACK' ? (
        <>
          <BindablePropertyRow path="placement.alignSelf" action={bindingAction('placement.alignSelf')}>
            <label className="te-node-inspector-field">
              <span>堆叠内对齐</span>
              <SelectField
                ariaLabel="堆叠内对齐"
                value={optionalSelfAlignment(placement.alignSelf)}
                disabled={disabled}
                options={OPTIONAL_SELF_ALIGNMENT_OPTIONS}
                onChange={(next) => command(
                  'alignSelf',
                  next === '' ? null : next,
                )}
              />
            </label>
          </BindablePropertyRow>
          {nodeKind !== 'group' ? (
            <CommitInput
              label="主轴填充权重"
              inputMode="decimal"
              initialValue={templateNumberDraft(placement.fillWeight ?? 1)}
              disabled={disabled}
              parse={(draft) => parseOptionalNumber(
                draft, true, '主轴填充权重必须大于 0。',
              )}
              isUnchanged={(next) => next === null
                ? placement.fillWeight === undefined
                : sameTemplateNumber(placement.fillWeight ?? 1, next)}
              onCommit={(next) => command('fillWeight', next)}
              propertyPath="placement.fillWeight"
              action={bindingAction('placement.fillWeight')}
            />
          ) : null}
        </>
      ) : (
        <>
          <div className="te-node-inspector-field-grid">
            <CommitInput
              label="网格行"
              inputMode="decimal"
              initialValue={templateNumberDraft(placement.row)}
              disabled={disabled}
              parse={(draft) => parseInteger(draft, 0, '网格行必须是非负整数。')}
              isUnchanged={(next) => sameTemplateNumber(placement.row, next)}
              onCommit={(next) => command('row', next)}
              propertyPath="placement.row"
              action={bindingAction('placement.row')}
            />
            <CommitInput
              label="网格列"
              inputMode="decimal"
              initialValue={templateNumberDraft(placement.column)}
              disabled={disabled}
              parse={(draft) => parseInteger(draft, 0, '网格列必须是非负整数。')}
              isUnchanged={(next) => sameTemplateNumber(placement.column, next)}
              onCommit={(next) => command('column', next)}
              propertyPath="placement.column"
              action={bindingAction('placement.column')}
            />
            <CommitInput
              label="跨行"
              inputMode="decimal"
              initialValue={templateNumberDraft(placement.rowSpan ?? 1)}
              disabled={disabled}
              parse={(draft) => parseOptionalInteger(draft, 1, '跨行必须是正整数。')}
              isUnchanged={(next) => next === null
                ? placement.rowSpan === undefined
                : sameTemplateNumber(placement.rowSpan ?? 1, next)}
              onCommit={(next) => command('rowSpan', next)}
              propertyPath="placement.rowSpan"
              action={bindingAction('placement.rowSpan')}
            />
            <CommitInput
              label="跨列"
              inputMode="decimal"
              initialValue={templateNumberDraft(placement.columnSpan ?? 1)}
              disabled={disabled}
              parse={(draft) => parseOptionalInteger(draft, 1, '跨列必须是正整数。')}
              isUnchanged={(next) => next === null
                ? placement.columnSpan === undefined
                : sameTemplateNumber(placement.columnSpan ?? 1, next)}
              onCommit={(next) => command('columnSpan', next)}
              propertyPath="placement.columnSpan"
              action={bindingAction('placement.columnSpan')}
            />
          </div>
          <BindablePropertyRow
            path="placement.horizontalAlignSelf"
            action={bindingAction('placement.horizontalAlignSelf')}
          >
            <label className="te-node-inspector-field">
              <span>单元内水平对齐</span>
              <SelectField
                ariaLabel="单元内水平对齐"
                value={optionalSelfAlignment(placement.horizontalAlignSelf)}
                disabled={disabled}
                options={OPTIONAL_SELF_ALIGNMENT_OPTIONS}
                onChange={(next) => command(
                  'horizontalAlignSelf',
                  next === '' ? null : next,
                )}
              />
            </label>
          </BindablePropertyRow>
          <BindablePropertyRow
            path="placement.verticalAlignSelf"
            action={bindingAction('placement.verticalAlignSelf')}
          >
            <label className="te-node-inspector-field">
              <span>单元内垂直对齐</span>
              <SelectField
                ariaLabel="单元内垂直对齐"
                value={optionalSelfAlignment(placement.verticalAlignSelf)}
                disabled={disabled}
                options={OPTIONAL_SELF_ALIGNMENT_OPTIONS}
                onChange={(next) => command(
                  'verticalAlignSelf',
                  next === '' ? null : next,
                )}
              />
            </label>
          </BindablePropertyRow>
        </>
      )}
    </>
  );
}

function PlacementSizeConstraintFields({
  nodeId,
  nodeKind,
  projectedSizeMm,
  placement,
  disabled,
  onCommand,
  bindingAction,
  includeFixedSizes = true,
  allowMinMax = true,
  lockHugModes = false,
}: {
  nodeId: string;
  nodeKind: string;
  projectedSizeMm?: Readonly<{ widthMm: number; heightMm: number }>;
  placement: Record<string, unknown>;
  disabled: boolean;
  onCommand: (intent: TemplateEditorCommandIntent) => void;
  bindingAction: (path: string) => ReactNode;
  includeFixedSizes?: boolean;
  allowMinMax?: boolean;
  lockHugModes?: boolean;
}) {
  const command = (
    property: Extract<TemplateEditorCommandIntent, { operation: 'set-property' }>['property'],
    value: unknown,
  ) => onCommand({ operation: 'set-property', nodeId, property, value });
  const widthMode = sizeMode(placement.widthMode);
  const heightMode = sizeMode(placement.heightMode);
  const allowedModes = (['FIXED', 'HUG_CONTENT', 'FILL'] as const)
    .filter((mode) => isTemplateNodeSizeModeAllowed(nodeKind, mode));
  const projectedWidth = positiveProjectedSize(projectedSizeMm?.widthMm);
  const projectedHeight = positiveProjectedSize(projectedSizeMm?.heightMm);
  const fixedModeAllowed = allowedModes.includes('FIXED');
  const changeMode = (
    axis: 'width' | 'height',
    current: 'FIXED' | 'HUG_CONTENT' | 'FILL',
    next: 'FIXED' | 'HUG_CONTENT' | 'FILL',
  ) => {
    if (next === 'FIXED' && current !== 'FIXED') {
      const projected = axis === 'width' ? projectedWidth : projectedHeight;
      if (projected !== null) command(axis === 'width' ? 'widthMm' : 'heightMm', projected);
      return;
    }
    command(axis === 'width' ? 'widthMode' : 'heightMode', next);
  };

  return (
    <div className="te-node-inspector-field-grid">
      <PlacementModeField
        label="宽度模式"
        value={widthMode}
        allowedModes={allowedModes}
        fixedUnavailable={widthMode !== 'FIXED' && projectedWidth === null}
        disabled={disabled || lockHugModes}
        onChange={(value) => changeMode('width', widthMode, value)}
      />
      <PlacementModeField
        label="高度模式"
        value={heightMode}
        allowedModes={allowedModes}
        fixedUnavailable={heightMode !== 'FIXED' && projectedHeight === null}
        disabled={disabled || lockHugModes}
        onChange={(value) => changeMode('height', heightMode, value)}
      />
      {fixedModeAllowed && widthMode !== 'FIXED' && projectedWidth === null ? (
        <CommitInput
          label="固定宽度"
          suffix="mm"
          inputMode="decimal"
          initialValue=""
          placeholder="输入后切换"
          disabled={disabled}
          parse={(draft) => parseNumber(draft, true, '固定宽度必须大于 0。')}
          isUnchanged={() => false}
          onCommit={(next) => command('widthMm', next)}
          propertyPath="placement.widthMm"
          action={bindingAction('placement.widthMm')}
        />
      ) : null}
      {fixedModeAllowed && heightMode !== 'FIXED' && projectedHeight === null ? (
        <CommitInput
          label="固定高度"
          suffix="mm"
          inputMode="decimal"
          initialValue=""
          placeholder="输入后切换"
          disabled={disabled}
          parse={(draft) => parseNumber(draft, true, '固定高度必须大于 0。')}
          isUnchanged={() => false}
          onCommit={(next) => command('heightMm', next)}
          propertyPath="placement.heightMm"
          action={bindingAction('placement.heightMm')}
        />
      ) : null}
      {includeFixedSizes && widthMode === 'FIXED' ? (
        <CommitInput
          label="宽度"
          suffix="mm"
          inputMode="decimal"
          initialValue={templateNumberDraft(placement.widthMm)}
          disabled={disabled}
          parse={(draft) => parseNumber(draft, true, '宽度必须大于 0。')}
          isUnchanged={(next) => sameTemplateNumber(placement.widthMm, next)}
          onCommit={(next) => command('widthMm', next)}
          propertyPath="placement.widthMm"
          action={bindingAction('placement.widthMm')}
        />
      ) : null}
      {includeFixedSizes && heightMode === 'FIXED' ? (
        <CommitInput
          label="高度"
          suffix="mm"
          inputMode="decimal"
          initialValue={templateNumberDraft(placement.heightMm)}
          disabled={disabled}
          parse={(draft) => parseNumber(draft, true, '高度必须大于 0。')}
          isUnchanged={(next) => sameTemplateNumber(placement.heightMm, next)}
          onCommit={(next) => command('heightMm', next)}
          propertyPath="placement.heightMm"
          action={bindingAction('placement.heightMm')}
        />
      ) : null}
      {allowMinMax ? (
        <>
          <PlacementLimitField
            label="最小宽度"
            property="minWidthMm"
            value={placement.minWidthMm}
            disabled={disabled}
            positive={false}
            onCommit={command}
            bindingAction={bindingAction}
          />
          <PlacementLimitField
            label="最小高度"
            property="minHeightMm"
            value={placement.minHeightMm}
            disabled={disabled}
            positive={false}
            onCommit={command}
            bindingAction={bindingAction}
          />
          <PlacementLimitField
            label="最大宽度"
            property="maxWidthMm"
            value={placement.maxWidthMm}
            disabled={disabled}
            positive
            onCommit={command}
            bindingAction={bindingAction}
          />
          <PlacementLimitField
            label="最大高度"
            property="maxHeightMm"
            value={placement.maxHeightMm}
            disabled={disabled}
            positive
            onCommit={command}
            bindingAction={bindingAction}
          />
        </>
      ) : null}
    </div>
  );
}

function PlacementModeField({
  label,
  value,
  allowedModes,
  fixedUnavailable,
  disabled,
  onChange,
}: {
  label: string;
  value: 'FIXED' | 'HUG_CONTENT' | 'FILL';
  allowedModes: readonly ('FIXED' | 'HUG_CONTENT' | 'FILL')[];
  fixedUnavailable: boolean;
  disabled: boolean;
  onChange: (value: 'FIXED' | 'HUG_CONTENT' | 'FILL') => void;
}) {
  return (
    <label className="te-node-inspector-field">
      <span>{label}</span>
      <SelectField
        ariaLabel={label}
        value={value}
        disabled={disabled}
        options={allowedModes.map((mode) => ({
          value: mode,
          label: SIZE_MODE_LABELS[mode],
          disabled: mode === 'FIXED' && fixedUnavailable,
        }))}
        onChange={(next) => onChange(next as typeof value)}
      />
    </label>
  );
}

const SIZE_MODE_LABELS = {
  FIXED: '固定',
  HUG_CONTENT: '适应内容',
  FILL: '填充可用空间',
} as const;

function PlacementLimitField({
  label,
  property,
  value,
  disabled,
  positive,
  onCommit,
  bindingAction,
}: {
  label: string;
  property: 'minWidthMm' | 'minHeightMm' | 'maxWidthMm' | 'maxHeightMm';
  value: unknown;
  disabled: boolean;
  positive: boolean;
  onCommit: (
    property: Extract<TemplateEditorCommandIntent, { operation: 'set-property' }>['property'],
    value: unknown,
  ) => void;
  bindingAction: (path: string) => ReactNode;
}) {
  return (
    <CommitInput
      label={label}
      suffix="mm"
      inputMode="decimal"
      initialValue={templateNumberDraft(value)}
      disabled={disabled}
      parse={(draft) => parseOptionalNumber(
        draft,
        positive,
        `${label}${positive ? '必须大于 0' : '必须是非负有限数值'}。`,
      )}
      isUnchanged={(next) => next === null
        ? value === undefined
        : sameTemplateNumber(value, next)}
      onCommit={(next) => onCommit(property, next)}
      propertyPath={`placement.${property}`}
      action={bindingAction(`placement.${property}`)}
    />
  );
}

function BindingEditorDialog({
  property,
  designDsl,
  staticSchema,
  staticSchemaTransport,
  nodeId,
  onClose,
  onSubmit,
}: {
  property: TemplateBindableProperty;
  designDsl: Readonly<Record<string, unknown>>;
  staticSchema: StaticSnapshot;
  staticSchemaTransport?: TemplateStaticSchemaTransport;
  nodeId: string;
  onClose: () => void;
  onSubmit: (source: TemplateBindingSource, staticSchemas: readonly StaticSnapshot[]) => void;
}) {
  const [schemas, setSchemas] = useState<readonly StaticSnapshot[]>(
    () => Object.freeze([staticSchema]),
  );
  const [referenceStates, setReferenceStates] = useState<Readonly<Record<string, 'loading' | 'error'>>>({});
  const referenceRequests = useRef(new Map<string, AbortController>());
  useEffect(() => {
    const requests = referenceRequests.current;
    return () => {
      requests.forEach((controller) => controller.abort());
      requests.clear();
    };
  }, []);
  const pendingReferences = projectPendingTemplateStaticSchemaReferences(schemas);
  const loadReference = (reference: TemplateStaticSchemaPendingReference) => {
    const key = bindingSchemaIdentityKey(reference.identity);
    if (referenceRequests.current.has(key)) return;
    if (!staticSchemaTransport) {
      setReferenceStates((current) => Object.freeze({ ...current, [key]: 'error' }));
      return;
    }
    const controller = new AbortController();
    referenceRequests.current.set(key, controller);
    setReferenceStates((current) => Object.freeze({ ...current, [key]: 'loading' }));
    void loadExactTemplateStaticSchema(
      reference.identity,
      staticSchemaTransport,
      controller.signal,
    ).then(
      (snapshot) => {
        if (controller.signal.aborted) return;
        setSchemas((current) => current.some((candidate) => (
          bindingSchemaIdentityKey(candidate) === key
        )) ? current : Object.freeze([...current, snapshot]));
        setReferenceStates((current) => {
          const next = { ...current };
          delete next[key];
          return Object.freeze(next);
        });
      },
      () => {
        if (!controller.signal.aborted) {
          setReferenceStates((current) => Object.freeze({ ...current, [key]: 'error' }));
        }
      },
    ).finally(() => {
      referenceRequests.current.delete(key);
    });
  };
  const sources = projectBindingSources(
    designDsl,
    staticSchema,
    nodeId,
    property.valueType,
    schemas,
  );
  const [selectedId, setSelectedId] = useState('');
  const selected = sources.find((source) => source.id === selectedId && source.state === 'available');
  const groups = [
    { id: 'system' as const, label: '系统数据源' },
    { id: 'definition' as const, label: '定义数据源' },
    { id: 'loop' as const, label: '循环域' },
  ];
  return (
    <Dialog.Root open onOpenChange={(open) => { if (!open) onClose(); }}>
      <Dialog.Portal>
        <Dialog.Overlay className="te-dialog-overlay" />
        <Dialog.Content className="te-dialog-content te-binding-dialog">
          <header>
            <div>
              <Dialog.Title>绑定{property.label}</Dialog.Title>
              <Dialog.Description>
                {property.propertyPath} · 目标类型 {bindingValueTypeLabel(property.valueType)}
              </Dialog.Description>
            </div>
            <Dialog.Close asChild>
              <button type="button" aria-label="关闭绑定设置"><X aria-hidden="true" size={17} /></button>
            </Dialog.Close>
          </header>
          <div className="te-binding-source-groups">
            {pendingReferences.length > 0 ? (
              <fieldset>
                <legend>引用字段</legend>
                {pendingReferences.map((reference) => {
                  const key = bindingSchemaIdentityKey(reference.identity);
                  const state = referenceStates[key];
                  const unavailable = state === 'error' || !staticSchemaTransport;
                  return (
                    <button
                      key={key}
                      type="button"
                      className="te-binding-reference-disclosure"
                      aria-label={`展开${reference.label}引用字段`}
                      disabled={state === 'loading' || unavailable}
                      onClick={() => loadReference(reference)}
                    >
                      <strong>{reference.label}</strong>
                      <small>{reference.identity.schemaKey}@{reference.identity.versionTag}</small>
                      {state === 'loading' ? <span role="status">加载中</span> : null}
                      {unavailable ? <span role="alert">引用暂不可读取或身份核验失败</span> : null}
                    </button>
                  );
                })}
              </fieldset>
            ) : null}
            {groups.map((group) => {
              const options = sources.filter((source) => source.group === group.id);
              if (options.length === 0) return null;
              return (
                <fieldset key={group.id}>
                  <legend>{group.label}</legend>
                  {options.map((option) => (
                    <label key={option.id} className={`te-binding-source${option.state === 'available' ? '' : ' is-unavailable'}`}>
                      <input
                        type="radio"
                        name="template-binding-source"
                        value={option.id}
                        checked={selectedId === option.id}
                        disabled={option.state !== 'available'}
                        onChange={() => setSelectedId(option.id)}
                      />
                      <span>
                        <strong>{option.label}</strong>
                        <small>{option.detail}</small>
                        {option.reason ? <em>{option.reason}</em> : null}
                      </span>
                    </label>
                  ))}
                </fieldset>
              );
            })}
          </div>
          <footer>
            <button type="button" onClick={onClose}>取消</button>
            <button
              type="button"
              className="is-primary"
              disabled={!selected}
              onClick={() => {
                if (selected) {
                  onSubmit(selected.source, schemas);
                }
              }}
            >
              创建绑定
            </button>
          </footer>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}

function BindingsPanel({
  bindings,
  disabled,
  onRemove,
}: {
  bindings: readonly Record<string, unknown>[];
  disabled: boolean;
  onRemove?: (bindingId: string) => void;
}) {
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
          <li
            key={`${bindingId}-${index}`}
            data-template-binding-id={bindingId}
            data-template-property-path={targetPropertyLabel(binding.targetPropertyRef)}
            tabIndex={-1}
          >
            <div>
              <span>目标属性</span>
              <strong>{targetPropertyLabel(binding.targetPropertyRef)}</strong>
            </div>
            <div>
              <span>数据来源</span>
              <strong>{sourceLabel(binding.source)}</strong>
            </div>
            <code title={bindingId}>{bindingId}</code>
            {onRemove ? (
              <button
                type="button"
                disabled={disabled}
                aria-label={`移除绑定 ${targetPropertyLabel(binding.targetPropertyRef)}`}
                onClick={() => onRemove(bindingId)}
              >
                <Trash2 aria-hidden="true" size={13} />移除
              </button>
            ) : null}
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
  return (geometry.xMm === undefined || sameTemplateNumber(placement.xMm, geometry.xMm))
    && (geometry.yMm === undefined || sameTemplateNumber(placement.yMm, geometry.yMm))
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

function parseGridTrackDraft(draft: string): DraftParse<readonly TemplateGridTrack[]> {
  const parsed = parseTemplateGridTracks(draft);
  return parsed.state === 'parsed'
    ? { ok: true, value: parsed.tracks }
    : { ok: false, problem: parsed.message };
}

function gridTrackDraft(value: unknown): string {
  return isTemplateGridTrackList(value) ? formatTemplateGridTracks(value) : '';
}

function sameGridTracks(value: unknown, next: readonly TemplateGridTrack[]): boolean {
  if (!isTemplateGridTrackList(value) || value.length !== next.length) return false;
  return value.every((track, index) => {
    const candidate = next[index];
    if (!candidate || track.type !== candidate.type) return false;
    if (track.type === 'AUTO' || candidate.type === 'AUTO') return true;
    return track.type === 'FIXED' && candidate.type === 'FIXED'
      ? sameTemplateNumericValue(track.valueMm, candidate.valueMm)
      : track.type === 'FRACTION' && candidate.type === 'FRACTION'
        && sameTemplateNumericValue(track.weight, candidate.weight);
  });
}

function sameTemplateNumericValue(value: unknown, candidate: unknown): boolean {
  const authored = templateNumberDraft(value);
  const next = templateNumberDraft(candidate);
  if (!authored || !next) return false;
  try {
    return canonicalTemplateDecimal(authored) === canonicalTemplateDecimal(next);
  } catch {
    return false;
  }
}

function parseNumber(draft: string, positive: boolean, problem: string): DraftParse<number> {
  const value = finiteDraft(draft);
  if (value === null || (positive ? value <= 0 : value < 0)) return { ok: false, problem };
  return { ok: true, value };
}

function parseOptionalNumber(
  draft: string,
  positive: boolean,
  problem: string,
): DraftParse<number | null> {
  if (draft.trim().length === 0) return { ok: true, value: null };
  return parseNumber(draft, positive, problem);
}

function parseOptionalFiniteNumber(
  draft: string,
  problem: string,
): DraftParse<number | null> {
  if (draft.trim().length === 0) return { ok: true, value: null };
  return parseFiniteNumber(draft, problem);
}

function parseFiniteNumber(draft: string, problem: string): DraftParse<number> {
  const value = finiteDraft(draft);
  return value === null ? { ok: false, problem } : { ok: true, value };
}

function parseInteger(draft: string, minimum: number, problem: string): DraftParse<number> {
  const value = finiteDraft(draft);
  return value === null || !Number.isSafeInteger(value) || value < minimum
    ? { ok: false, problem }
    : { ok: true, value };
}

function parseOptionalInteger(
  draft: string,
  minimum: number,
  problem: string,
): DraftParse<number | null> {
  if (draft.trim().length === 0) return { ok: true, value: null };
  return parseInteger(draft, minimum, problem);
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

function positiveProjectedSize(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) && value > 0 ? value : null;
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
  return decodeTemplateTargetPropertyRef(value)?.propertyPath ?? '未知目标';
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

function bindingSchemaIdentityKey(identity: { readonly schemaKey: string; readonly versionTag: string }): string {
  return `${identity.schemaKey.length}:${identity.schemaKey}${identity.versionTag.length}:${identity.versionTag}`;
}

function stringValue(value: unknown, fallback = ''): string {
  return typeof value === 'string' ? value : fallback;
}

function stackJustification(value: unknown): string {
  return value === 'CENTER' || value === 'END' || value === 'SPACE_BETWEEN'
    || value === 'SPACE_AROUND' || value === 'SPACE_EVENLY'
    ? value
    : 'START';
}

function selfAlignment(value: unknown): string {
  return value === 'CENTER' || value === 'END' ? value : 'START';
}

function optionalSelfAlignment(value: unknown): string {
  return value === 'START' || value === 'CENTER' || value === 'END' ? value : '';
}

function sizeMode(value: unknown): 'FIXED' | 'HUG_CONTENT' | 'FILL' {
  return value === 'HUG_CONTENT' || value === 'FILL' ? value : 'FIXED';
}
