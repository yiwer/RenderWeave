// @vitest-environment happy-dom

import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { parse } from 'lossless-json';
import { afterEach, describe, expect, it, vi } from 'vitest';

import type { AssetReadableResponse } from '../../api/generated';
import type { StaticSnapshot } from '../schema-studio/lossless-api';
import type { EditorNodeProjection } from './template-editor-model';
import { TemplateEditorInspector } from './TemplateEditorInspector';
import {
  TemplateAssetRequestError,
  type TemplateEditorAssetTransport,
} from './template-editor-assets';

afterEach(cleanup);

describe('Template Editor Inspector', () => {
  it('shows only relevant property groups in the professional authored order', () => {
    const view = renderInspector(rectNode());

    expect(groupOrder(view.container)).toEqual([
      'content', 'position-size', 'appearance', 'advanced',
    ]);
    expect(screen.getByLabelText('名称')).toBeTruthy();
    expect(screen.getByLabelText('X 坐标')).toBeTruthy();
    expect(screen.getByLabelText('宽度')).toBeTruthy();
    expect(screen.getByLabelText('填充颜色')).toBeTruthy();
    expect(screen.queryByLabelText('排列方向')).toBeNull();

    view.rerender(<TemplateEditorInspector
      node={canvasNode()}
      disabled={false}
      onCommand={vi.fn()}
    />);
    expect(groupOrder(view.container)).toEqual(['position-size', 'appearance', 'advanced']);
    expect(screen.queryByLabelText('名称')).toBeNull();
    expect(screen.getByLabelText('画布宽度')).toBeTruthy();
    expect(screen.getByLabelText('画布高度')).toBeTruthy();
    expect(screen.getByLabelText('背景颜色')).toBeTruthy();
  });

  it('keeps invalid local drafts and emits rename or one atomic fixed geometry command', () => {
    const onCommand = vi.fn();
    renderInspector(rectNode(), { onCommand });

    const name = screen.getByLabelText('名称');
    fireEvent.change(name, { target: { value: '   ' } });
    fireEvent.blur(name);
    expect(name.getAttribute('aria-invalid')).toBe('true');
    expect((name as HTMLInputElement).value).toBe('   ');
    expect(screen.getByText('名称必须是 1–128 个有效字符。')).toBeTruthy();
    expect(onCommand).not.toHaveBeenCalled();

    fireEvent.change(name, { target: { value: '  主背景  ' } });
    fireEvent.keyDown(name, { key: 'Enter' });
    expect(onCommand).toHaveBeenLastCalledWith({
      operation: 'rename', nodeId: 'rect', displayName: '主背景',
    });

    const width = screen.getByLabelText('宽度');
    fireEvent.change(width, { target: { value: '0' } });
    fireEvent.blur(width);
    expect((width as HTMLInputElement).value).toBe('0');
    expect(width.getAttribute('aria-invalid')).toBe('true');
    expect(onCommand).toHaveBeenCalledTimes(1);

    fireEvent.change(width, { target: { value: '120.5' } });
    fireEvent.keyDown(width, { key: 'Enter' });
    expect(onCommand).toHaveBeenLastCalledWith({
      operation: 'set-geometry',
      nodeId: 'rect',
      geometry: { xMm: 12, yMm: 18, widthMm: 120.5, heightMm: 40 },
    });
    expect(onCommand).toHaveBeenCalledTimes(2);
  });

  it('shares Java trim and Unicode-scalar name semantics while keeping concise Chinese feedback', () => {
    const onCommand = vi.fn();
    renderInspector(rectNode(), { onCommand });
    const name = screen.getByLabelText('名称');

    fireEvent.change(name, { target: { value: '\ud800' } });
    fireEvent.blur(name);
    expect(screen.getByText('名称必须是 1–128 个有效字符。')).toBeTruthy();
    expect(onCommand).not.toHaveBeenCalled();

    fireEvent.change(name, { target: { value: '\u00a0名称\u00a0' } });
    fireEvent.keyDown(name, { key: 'Enter' });
    expect(onCommand).toHaveBeenCalledWith({
      operation: 'rename', nodeId: 'rect', displayName: '\u00a0名称\u00a0',
    });
  });

  it('edits Stack layout facts without presenting absolute geometry', () => {
    const onCommand = vi.fn();
    const view = renderInspector(stackNode(), { onCommand });

    expect(groupOrder(view.container)).toEqual([
      'content', 'layout-constraints', 'appearance', 'advanced',
    ]);
    expect(screen.queryByLabelText('X 坐标')).toBeNull();

    fireEvent.change(screen.getByLabelText('排列方向'), { target: { value: 'ROW' } });
    expect(onCommand).toHaveBeenLastCalledWith({
      operation: 'set-property', nodeId: 'stack', property: 'direction', value: 'ROW',
    });

    fireEvent.click(screen.getByRole('checkbox', { name: /裁剪溢出内容/ }));
    expect(onCommand).toHaveBeenLastCalledWith({
      operation: 'set-property', nodeId: 'stack', property: 'clipContent', value: true,
    });

    const gap = screen.getByLabelText('间距');
    fireEvent.change(gap, { target: { value: '6.25' } });
    fireEvent.blur(gap);
    expect(onCommand).toHaveBeenLastCalledWith({
      operation: 'set-property', nodeId: 'stack', property: 'gapMm', value: 6.25,
    });
  });

  it('shows exact lossless numeric tokens without emitting unchanged authored commands', () => {
    const onCommand = vi.fn();
    const value = parse(JSON.stringify({
      nodeId: 'rect',
      kind: 'rect',
      displayName: '精确矩形',
      bindings: [],
      fill: { color: '#2563EBFF' },
      placement: {
        type: 'ABSOLUTE', xMm: 12.5, yMm: 18,
        widthMode: 'FIXED', widthMm: 80.25,
        heightMode: 'FIXED', heightMm: 40,
      },
    })) as Record<string, unknown>;
    renderInspector(node('rect', '精确矩形', value), { onCommand });

    expect((screen.getByLabelText('X 坐标') as HTMLInputElement).value).toBe('12.5');
    expect((screen.getByLabelText('宽度') as HTMLInputElement).value).toBe('80.25');
    fireEvent.blur(screen.getByLabelText('X 坐标'));
    fireEvent.blur(screen.getByLabelText('宽度'));
    expect(onCommand).not.toHaveBeenCalled();
  });

  it('lists only existing authored bindings on the Bindings tab', () => {
    const node = rectNode({
      bindings: [
        {
          bindingId: 'binding-opacity',
          targetPropertyRef: { rootPropertyId: 'opacity', selectors: [] },
          source: { kind: 'definition', definitionId: 'price-color' },
        },
        {
          bindingId: 'binding-fill',
          targetPropertyRef: {
            rootPropertyId: 'fill',
            selectors: [{ kind: 'member', name: 'color' }],
          },
          source: { kind: 'context', domain: 'invocation', pointer: '/theme/accent' },
        },
      ],
    });
    renderInspector(node);

    const propertiesTab = screen.getByRole('tab', { name: '属性' });
    propertiesTab.focus();
    fireEvent.keyDown(propertiesTab, { key: 'ArrowRight' });
    expect(screen.getByRole('tab', { name: /绑定/ }).getAttribute('aria-selected')).toBe('true');
    const list = screen.getByRole('list', { name: '已有绑定' });
    expect(within(list).getAllByRole('listitem')).toHaveLength(2);
    expect(within(list).getByText('opacity')).toBeTruthy();
    expect(within(list).getByText('fill.color')).toBeTruthy();
    expect(within(list).getByText('定义 · price-color')).toBeTruthy();
    expect(within(list).getByText('上下文 · invocation / /theme/accent')).toBeTruthy();
    expect(screen.queryByText(/可绑定属性/)).toBeNull();
    expect(within(list).queryAllByRole('button')).toHaveLength(0);
  });

  it('shows member-then-index wire selectors using the canonical target path', () => {
    const authored = textNode([
      textRun('静态标题', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'),
    ]);
    authored.value.bindings = [parse(JSON.stringify({
      bindingId: '30000000-0000-4000-8000-000000000019',
      targetPropertyRef: {
        rootPropertyId: 'runs',
        selectors: [{ kind: 'member', name: 'text' }, { kind: 'index', index: 0 }],
      },
      source: { kind: 'context', domain: 'invocation', pointer: '/title' },
    })) as Record<string, unknown>];
    renderInspector(authored);

    fireEvent.click(screen.getByRole('tab', { name: /绑定/ }));

    expect(screen.getByText('runs[0].text')).toBeTruthy();
    expect(screen.queryByText('runs.text[0]')).toBeNull();
  });

  it('starts binding from the concrete Text property row and emits one exact intent', () => {
    const onDataIntent = vi.fn();
    renderInspector(textNode([
      textRun('静态标题', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'),
    ]), {
      designDsl: { definitions: [] },
      staticSchema: { state: 'ready', snapshot: bindingSchemaSnapshot() },
      onDataIntent,
    });

    fireEvent.click(screen.getByRole('button', { name: '绑定文本值' }));
    expect(screen.getByRole('dialog', { name: '绑定文本值' })).toBeTruthy();
    fireEvent.click(screen.getByRole('radio', { name: /商品标题.*\/title.*文本/ }));
    fireEvent.click(screen.getByRole('button', { name: '创建绑定' }));

    expect(onDataIntent).toHaveBeenCalledWith({
      operation: 'create-binding',
      nodeId: 'text',
      propertyPath: 'runs[0].text',
      source: { kind: 'context', domain: 'invocation', pointer: '/title' },
    });
  });

  it('keeps root scalar sources available without fetching an unrelated referenced schema', () => {
    const root = bindingSchemaSnapshot();
    root.definition.fields.push({
      fieldKey: 'brokenDetails', displayName: '不可用详情', required: true,
      value: { type: 'reference', ref: { schemaKey: 'missing-details', versionTag: 'v1' } },
    });
    const getStaticSchema = vi.fn(async () => {
      throw new Error('reference unavailable');
    });
    const onDataIntent = vi.fn();
    renderInspector(textNode([
      textRun('静态标题', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'),
    ]), {
      designDsl: { definitions: [] },
      staticSchema: { state: 'ready', snapshot: root },
      staticSchemaTransport: { getStaticSchema },
      onDataIntent,
    });

    fireEvent.click(screen.getByRole('button', { name: '绑定文本值' }));

    fireEvent.click(screen.getByRole('radio', { name: /商品标题.*\/title.*文本/ }));
    fireEvent.click(screen.getByRole('button', { name: '创建绑定' }));
    expect(onDataIntent).toHaveBeenCalledWith({
      operation: 'create-binding',
      nodeId: 'text',
      propertyPath: 'runs[0].text',
      source: { kind: 'context', domain: 'invocation', pointer: '/title' },
    });
    expect(getStaticSchema).not.toHaveBeenCalled();
  });

  it('offers exact scalar leaves behind a referenced StaticSchema boundary', async () => {
    const onDataIntent = vi.fn();
    const root = bindingSchemaSnapshot();
    root.definition.fields = [{
      fieldKey: 'brand', displayName: '品牌', required: true,
      value: { type: 'reference', ref: { schemaKey: 'catalog-brand', versionTag: 'v2' } },
    }];
    const brand: StaticSnapshot = {
      ...bindingSchemaSnapshot(),
      schemaKey: 'catalog-brand',
      versionTag: 'v2',
      definition: {
        ...bindingSchemaSnapshot().definition,
        displayName: '品牌信息',
        fields: [{
          fieldKey: 'name', displayName: '名称', required: true, value: { type: 'text' },
        }],
      },
    };
    const getStaticSchema = vi.fn(async () => brand);
    renderInspector(textNode([
      textRun('静态标题', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'),
    ]), {
      designDsl: { definitions: [] },
      staticSchema: { state: 'ready', snapshot: root },
      staticSchemaTransport: { getStaticSchema },
      onDataIntent,
    });

    fireEvent.click(screen.getByRole('button', { name: '绑定文本值' }));
    expect(getStaticSchema).not.toHaveBeenCalled();
    expect(screen.queryByRole('radio', { name: /品牌 \/ 名称.*\/brand\/name.*文本/ })).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: '展开品牌引用字段' }));
    fireEvent.click(await screen.findByRole('radio', { name: /品牌 \/ 名称.*\/brand\/name.*文本/ }));
    fireEvent.click(screen.getByRole('button', { name: '创建绑定' }));

    expect(getStaticSchema).toHaveBeenCalledOnce();
    expect(getStaticSchema).toHaveBeenCalledWith(
      { schemaKey: 'catalog-brand', versionTag: 'v2' },
      expect.any(AbortSignal),
    );
    expect(onDataIntent).toHaveBeenCalledWith(
      {
        operation: 'create-binding',
        nodeId: 'text',
        propertyPath: 'runs[0].text',
        source: { kind: 'context', domain: 'invocation', pointer: '/brand/name' },
      },
      { staticSchemas: [root, brand] },
    );
  });

  it('isolates an exact reference failure to that branch while other sources stay usable', async () => {
    const root = bindingSchemaSnapshot();
    root.definition.fields.push(
      {
        fieldKey: 'brokenDetails', displayName: '不可用详情', required: true,
        value: { type: 'reference', ref: { schemaKey: 'missing-details', versionTag: 'v1' } },
      },
      {
        fieldKey: 'brand', displayName: '品牌', required: true,
        value: { type: 'reference', ref: { schemaKey: 'catalog-brand', versionTag: 'v2' } },
      },
    );
    const brand: StaticSnapshot = {
      ...bindingSchemaSnapshot(),
      schemaKey: 'catalog-brand',
      versionTag: 'v2',
      definition: {
        ...bindingSchemaSnapshot().definition,
        fields: [{ fieldKey: 'name', displayName: '名称', required: true, value: { type: 'text' } }],
      },
    };
    const getStaticSchema = vi.fn(async (identity: { schemaKey: string }) => {
      if (identity.schemaKey === 'catalog-brand') return brand;
      throw new Error('reference unavailable');
    });
    renderInspector(textNode([
      textRun('静态标题', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'),
    ]), {
      designDsl: { definitions: [] },
      staticSchema: { state: 'ready', snapshot: root },
      staticSchemaTransport: { getStaticSchema },
      onDataIntent: vi.fn(),
    });

    fireEvent.click(screen.getByRole('button', { name: '绑定文本值' }));
    fireEvent.click(screen.getByRole('button', { name: '展开不可用详情引用字段' }));

    await screen.findByText('引用暂不可读取或身份核验失败');
    expect(screen.getByRole('button', { name: '展开不可用详情引用字段' }).hasAttribute('disabled')).toBe(true);
    expect(screen.getByRole('radio', { name: /商品标题.*\/title.*文本/ }).hasAttribute('disabled')).toBe(false);
    expect(screen.getByRole('button', { name: '展开品牌引用字段' }).hasAttribute('disabled')).toBe(false);

    fireEvent.click(screen.getByRole('button', { name: '展开品牌引用字段' }));
    expect((await screen.findByRole('radio', { name: /品牌 \/ 名称.*\/brand\/name.*文本/ })).hasAttribute('disabled')).toBe(false);
    expect(getStaticSchema).toHaveBeenCalledTimes(2);
  });

  it('aborts an in-flight exact reference request when the Binding dialog closes', () => {
    const root = bindingSchemaSnapshot();
    root.definition.fields.push({
      fieldKey: 'brand', displayName: '品牌', required: true,
      value: { type: 'reference', ref: { schemaKey: 'catalog-brand', versionTag: 'v2' } },
    });
    const getStaticSchema = vi.fn((
      identity: { schemaKey: string; versionTag: string },
      signal?: AbortSignal,
    ) => {
      void identity;
      void signal;
      return new Promise<StaticSnapshot>(() => undefined);
    });
    renderInspector(textNode([
      textRun('静态标题', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'),
    ]), {
      designDsl: { definitions: [] },
      staticSchema: { state: 'ready', snapshot: root },
      staticSchemaTransport: { getStaticSchema },
      onDataIntent: vi.fn(),
    });

    fireEvent.click(screen.getByRole('button', { name: '绑定文本值' }));
    fireEvent.click(screen.getByRole('button', { name: '展开品牌引用字段' }));
    const signal = getStaticSchema.mock.calls[0]?.[1];
    expect(signal?.aborted).toBe(false);

    fireEvent.click(screen.getByRole('button', { name: '关闭绑定设置' }));
    expect(signal?.aborted).toBe(true);
  });

  it('removes a configured Binding only from the dedicated Bindings tab', () => {
    const onDataIntent = vi.fn();
    const authored = textNode([
      textRun('静态标题', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'),
    ]);
    authored.value.bindings = [{
      bindingId: '30000000-0000-4000-8000-000000000009',
      targetPropertyRef: {
        rootPropertyId: 'runs',
        selectors: [{ kind: 'index', index: 0 }, { kind: 'member', name: 'text' }],
      },
      source: { kind: 'context', domain: 'invocation', pointer: '/title' },
    }];
    renderInspector(authored, {
      designDsl: { definitions: [] },
      staticSchema: { state: 'ready', snapshot: bindingSchemaSnapshot() },
      onDataIntent,
    });

    expect(screen.queryByRole('button', { name: /移除绑定/ })).toBeNull();
    fireEvent.click(screen.getByRole('tab', { name: /绑定/ }));
    fireEvent.click(screen.getByRole('button', { name: '移除绑定 runs[0].text' }));

    expect(onDataIntent).toHaveBeenCalledWith({
      operation: 'remove-binding',
      nodeId: 'text',
      bindingId: '30000000-0000-4000-8000-000000000009',
    });
  });

  it('fails closed when an exact problem focus binding is duplicated or missing', async () => {
    const bindingId = '30000000-0000-4000-8000-000000000010';
    const targetPropertyRef = {
      rootPropertyId: 'runs',
      selectors: [{ kind: 'index' as const, index: 0 }, { kind: 'member' as const, name: 'text' }],
    };
    const authored = textNode([
      textRun('静态标题', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'),
    ]);
    authored.value.bindings = [0, 1].map(() => ({
      bindingId,
      targetPropertyRef,
      source: { kind: 'context', domain: 'invocation', pointer: '/title' },
    }));
    const onProblemFocusResult = vi.fn();
    const view = renderInspector(authored, {
      problemFocus: {
        requestId: 1,
        nodeId: authored.nodeId,
        mode: 'binding',
        focus: { kind: 'binding', bindingId, propertyPath: 'runs[0].text', targetPropertyRef },
      },
      onProblemFocusResult,
    });

    await waitFor(() => expect(onProblemFocusResult).toHaveBeenCalledWith(1, false));
    expect(document.activeElement?.getAttribute('data-template-binding-id')).toBeNull();

    view.rerender(<TemplateEditorInspector
      node={authored}
      disabled={false}
      onCommand={vi.fn()}
      problemFocus={{
        requestId: 2,
        nodeId: authored.nodeId,
        mode: 'binding',
        focus: {
          kind: 'binding',
          bindingId: 'missing-binding',
          propertyPath: 'runs[0].text',
          targetPropertyRef,
        },
      }}
      onProblemFocusResult={onProblemFocusResult}
    />);
    await waitFor(() => expect(onProblemFocusResult).toHaveBeenCalledWith(2, false));
    expect(document.activeElement?.getAttribute('data-template-binding-id')).toBeNull();
  });

  it('keeps multi-Run Text explicitly read-only instead of flattening authored runs', () => {
    const onCommand = vi.fn();
    renderInspector(textNode([
      textRun('第一段', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'),
      textRun('第二段', 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'),
    ]), { onCommand });

    expect(screen.getByText('多 Run 内容保持只读')).toBeTruthy();
    expect(screen.getByText(/不会合并、拍平或覆盖/)).toBeTruthy();
    expect(screen.queryByLabelText('文本值')).toBeNull();
    expect(screen.queryByRole('button', { name: /字体 Asset/ })).toBeNull();
    expect(onCommand).not.toHaveBeenCalled();
  });

  it('shows a deleted Asset dependency without clearing or replacing its AssetRef', async () => {
    const assetId = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
    const assetTransport: TemplateEditorAssetTransport = {
      listAssets: vi.fn(async () => ({ items: [] })),
      getCurrent: vi.fn(async () => {
        throw new TemplateAssetRequestError(410, 'ASSET_DELETED');
      }),
      previewCurrent: vi.fn(async () => new Blob()),
    };
    renderInspector(imageNode(assetId), { assetTransport });

    expect(await screen.findByText('图片 Asset 已删除；引用已保留')).toBeTruthy();
    expect(screen.getByText(assetId)).toBeTruthy();
    expect(screen.getByRole('button', { name: '更换图片 Asset' })).toBeTruthy();
  });

  it('keeps an ACTIVE Asset current while attributing STALE to the Template dependency snapshot', async () => {
    const assetId = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
    const assetTransport: TemplateEditorAssetTransport = {
      listAssets: vi.fn(async () => ({ items: [] })),
      getCurrent: vi.fn(async (): Promise<AssetReadableResponse> => ({
        assetId,
        disclosure: 'READABLE',
        kind: 'IMAGE',
        lifecycle: 'ACTIVE',
        assetRevision: 3,
        currentContentVersion: 2,
        displayName: '当前商品图',
        tags: [],
        sourceFileName: 'current.png',
        mediaType: 'image/png',
        byteLength: 4,
        sha256: 'a'.repeat(64),
        descriptor: {
          encodedWidthPx: 1,
          encodedHeightPx: 1,
          orientation: 'IDENTITY',
          logicalWidthPx: 1,
          logicalHeightPx: 1,
          frameCount: 1,
          colorEncoding: 'SRGB_8BIT',
        },
        createdAt: '2026-09-03T00:00:00Z',
        updatedAt: '2026-09-03T00:00:00Z',
      })),
      previewCurrent: vi.fn(async () => new Blob()),
    };
    renderInspector(imageNode(assetId), {
      assetTransport,
      dependencyStaleMessage: 'Template 打开时依赖快照 STALE；本次权威重检 READY',
    });

    expect(await screen.findByText(
      '当前商品图 · 当前 Asset ACTIVE；Template 打开时依赖快照 STALE；本次权威重检 READY',
    )).toBeTruthy();
    expect(screen.getByText(assetId)).toBeTruthy();
    expect(screen.queryByText('当前商品图 · STALE')).toBeNull();
  });

  it('renders an honest empty selection and disables every authored control', () => {
    const onCommand = vi.fn();
    const view = render(<TemplateEditorInspector
      disabled={false}
      onCommand={onCommand}
    />);
    expect(screen.getByText('未选择元素')).toBeTruthy();

    view.rerender(<TemplateEditorInspector
      node={stackNode()}
      disabled
      onCommand={onCommand}
    />);
    const controls = view.container.querySelectorAll<HTMLInputElement | HTMLSelectElement>(
      'input, select',
    );
    expect(controls.length).toBeGreaterThan(0);
    expect([...controls].every((control) => control.disabled)).toBe(true);
  });
});

function renderInspector(
  node: EditorNodeProjection,
  overrides: Partial<Parameters<typeof TemplateEditorInspector>[0]> = {},
) {
  return render(<TemplateEditorInspector
    node={node}
    disabled={false}
    onCommand={vi.fn()}
    {...overrides}
  />);
}

function groupOrder(container: HTMLElement): string[] {
  return Array.from(container.querySelectorAll<HTMLElement>('[data-inspector-group]'))
    .map((group) => group.dataset.inspectorGroup ?? '');
}

function rectNode(members: Record<string, unknown> = {}): EditorNodeProjection {
  return node('rect', '背景矩形', {
    displayName: '背景矩形',
    bindings: [],
    fill: { color: '#2563EBFF' },
    placement: {
      type: 'ABSOLUTE', xMm: 12, yMm: 18,
      widthMode: 'FIXED', widthMm: 80,
      heightMode: 'FIXED', heightMm: 40,
    },
    ...members,
  });
}

function stackNode(): EditorNodeProjection {
  return node('stack', '价格堆叠', {
    displayName: '价格堆叠',
    bindings: [],
    children: [],
    direction: 'COLUMN',
    gapMm: 4,
    clipContent: false,
    fill: { color: '#FFFFFFFF' },
    placement: {
      type: 'STACK',
      widthMode: 'FIXED', widthMm: 80,
      heightMode: 'FIXED', heightMm: 40,
    },
  });
}

function textNode(runs: Record<string, unknown>[]): EditorNodeProjection {
  return node('text', '售价文本', {
    displayName: '售价文本',
    bindings: [],
    runs,
    placement: {
      type: 'ABSOLUTE', xMm: 12, yMm: 18,
      widthMode: 'FIXED', widthMm: 80,
      heightMode: 'FIXED', heightMm: 20,
    },
  });
}

function textRun(text: string, assetId: string): Record<string, unknown> {
  return {
    text,
    fontRef: { assetId },
    fontSizePt: 12,
    color: '#000000FF',
    decoration: 'NONE',
  };
}

function imageNode(assetId: string): EditorNodeProjection {
  return node('image', '商品图', {
    displayName: '商品图',
    bindings: [],
    imageRef: { assetId },
    fit: 'CONTAIN',
    sampling: 'LINEAR',
    placement: {
      type: 'ABSOLUTE', xMm: 12, yMm: 18,
      widthMode: 'FIXED', widthMm: 80,
      heightMode: 'FIXED', heightMm: 40,
    },
  });
}

function canvasNode(): EditorNodeProjection {
  return node('canvas', '画布', {
    displayName: '画布',
    bindings: [],
    children: [],
    widthMm: 210,
    heightMm: 297,
    backgroundColor: '#FFFFFFFF',
  }, 1);
}

function bindingSchemaSnapshot(): StaticSnapshot {
  return {
    schemaKey: 'catalog-product',
    versionTag: 'v1',
    origin: 'SYSTEM',
    sourceDraftRevision: null,
    compilerVersion: 'schema-compiler/1',
    releaseNote: null,
    referenceDepth: 0,
    publishedAt: '2026-09-03T00:00:00Z',
    definition: {
      dslVersion: 'renderweave-schema/1.0',
      displayName: '商品数据',
      fields: [
        { fieldKey: 'title', displayName: '商品标题', required: true, value: { type: 'text' } },
        { fieldKey: 'subtitle', displayName: '副标题', required: false, value: { type: 'text' } },
      ],
    },
  };
}

function node(
  kind: string,
  displayName: string,
  value: Record<string, unknown>,
  childCount = 0,
): EditorNodeProjection {
  return {
    nodeId: kind,
    kind,
    displayName,
    depth: kind === 'canvas' ? 0 : 1,
    childCount,
    value: { nodeId: kind, kind, ...value },
  };
}
