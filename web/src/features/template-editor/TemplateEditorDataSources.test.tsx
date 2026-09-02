// @vitest-environment happy-dom

import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { isLosslessNumber } from 'lossless-json';
import { afterEach, describe, expect, it, vi } from 'vitest';

import type { StaticSnapshot } from '../schema-studio/lossless-api';
import { TemplateEditorDataSources } from './TemplateEditorDataSources';

afterEach(() => cleanup());

describe('Template Editor data sources panel', () => {
  it('projects each permanent system field as an independent read-only source card', () => {
    const snapshot = schemaSnapshot();

    render(<TemplateEditorDataSources
      designDsl={{ definitions: [] }}
      staticSchema={{ state: 'ready', snapshot }}
      disabled={false}
      onIntent={vi.fn()}
    />);

    expect(screen.getByRole('heading', { name: '数据源' })).toBeTruthy();
    expect(screen.getByText('价签数据')).toBeTruthy();
    expect(screen.getByText('price-card')).toBeTruthy();
    const fieldCards = screen.getByRole('list', { name: '系统字段' }).querySelectorAll('article');
    expect(fieldCards).toHaveLength(2);
    expect(screen.getByText('商品标题')).toBeTruthy();
    expect(screen.getByText('title')).toBeTruthy();
    expect(screen.getByText('文本')).toBeTruthy();
    expect(screen.getByText('必填 · 最少 1 个字符 · 最多 40 个字符')).toBeTruthy();
    expect(screen.getByText('品牌')).toBeTruthy();
    expect(fieldCards[1]?.querySelector(':scope > div code')?.textContent).toBe('brand');
    expect(fieldCards[1]?.querySelector(':scope > span')?.textContent).toBe('对象引用');
    expect(fieldCards[1]?.querySelector(':scope > p')?.textContent).toBe('brand · v2');
    expect(screen.queryByText('引用 · brand · v2')).toBeNull();
    expect(screen.queryByText(/来自/)).toBeNull();
    expect(screen.queryByText('品牌名称')).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: '查看商品标题' }));

    expect(screen.getByRole('dialog', { name: '商品标题详情' })).toBeTruthy();
    expect(screen.getByText('/title')).toBeTruthy();
    expect(screen.queryByRole('button', { name: /编辑系统数据源/ })).toBeNull();
    expect(screen.queryByRole('button', { name: /删除系统数据源/ })).toBeNull();
  });

  it('resolves an exact referenced schema only from inside the detail dialog', async () => {
    const getStaticSchema = vi.fn().mockResolvedValue(referenceSnapshot());
    render(<TemplateEditorDataSources
      designDsl={{ definitions: [] }}
      staticSchema={{ state: 'ready', snapshot: schemaSnapshot() }}
      referenceTransport={{ getStaticSchema }}
      disabled={false}
      onIntent={vi.fn()}
    />);

    expect(getStaticSchema).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: '查看品牌' }));
    expect(getStaticSchema).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: '查看brand引用结构' }));

    expect(await screen.findByRole('region', { name: '品牌资料引用结构' })).toBeTruthy();
    expect(screen.getByText('品牌名称')).toBeTruthy();
    expect(screen.getByText('必填 · 最少 2 个字符')).toBeTruthy();
    expect(getStaticSchema).toHaveBeenCalledOnce();
    expect(getStaticSchema).toHaveBeenCalledWith(
      { schemaKey: 'brand', versionTag: 'v2' },
      expect.any(AbortSignal),
    );
  });

  it('creates a CustomDefinition from a concise dialog as one semantic intent', () => {
    const onIntent = vi.fn();
    render(<TemplateEditorDataSources
      designDsl={{ definitions: [] }}
      staticSchema={{ state: 'ready', snapshot: schemaSnapshot() }}
      disabled={false}
      onIntent={onIntent}
    />);

    fireEvent.click(screen.getByRole('button', { name: '新建定义数据源' }));
    expect(screen.getByRole('dialog', { name: '新建定义数据源' })).toBeTruthy();

    fireEvent.change(screen.getByLabelText('定义名称'), { target: { value: '门店名称' } });
    fireEvent.change(screen.getByLabelText('可见性'), { target: { value: 'PRIVATE' } });
    fireEvent.change(screen.getByLabelText('默认值'), { target: { value: '默认门店' } });
    fireEvent.click(screen.getByRole('button', { name: '创建定义' }));

    expect(onIntent).toHaveBeenCalledWith({
      operation: 'create-definition',
      definition: {
        kind: 'custom',
        displayName: '门店名称',
        exposure: 'PRIVATE',
        valueType: 'text',
        defaultValue: '默认门店',
      },
    });
    expect(screen.queryByRole('dialog', { name: '新建定义数据源' })).toBeNull();
  });

  it('preserves NBSP in a definition name under the shared Java String.trim contract', () => {
    const onIntent = vi.fn();
    render(<TemplateEditorDataSources
      designDsl={{ definitions: [] }}
      staticSchema={{ state: 'ready', snapshot: schemaSnapshot() }}
      disabled={false}
      onIntent={onIntent}
    />);

    fireEvent.click(screen.getByRole('button', { name: '新建定义数据源' }));
    fireEvent.change(screen.getByLabelText('定义名称'), {
      target: { value: '\u00a0门店名称\u00a0' },
    });
    fireEvent.change(screen.getByLabelText('默认值'), { target: { value: '默认门店' } });
    fireEvent.click(screen.getByRole('button', { name: '创建定义' }));

    expect(onIntent).toHaveBeenCalledWith({
      operation: 'create-definition',
      definition: {
        kind: 'custom',
        displayName: '\u00a0门店名称\u00a0',
        exposure: 'PUBLIC',
        valueType: 'text',
        defaultValue: '默认门店',
      },
    });
  });

  it('submits 128 astral code points through the shared display-name normalizer', () => {
    const onIntent = vi.fn();
    render(<TemplateEditorDataSources
      designDsl={{ definitions: [] }}
      staticSchema={{ state: 'ready', snapshot: schemaSnapshot() }}
      disabled={false}
      onIntent={onIntent}
    />);

    fireEvent.click(screen.getByRole('button', { name: '新建定义数据源' }));
    const name = screen.getByLabelText('定义名称') as HTMLInputElement;
    expect(name.hasAttribute('maxlength')).toBe(false);
    const displayName = '😀'.repeat(128);
    fireEvent.change(name, { target: { value: displayName } });
    fireEvent.change(screen.getByLabelText('默认值'), { target: { value: '值' } });
    fireEvent.click(screen.getByRole('button', { name: '创建定义' }));

    expect(onIntent).toHaveBeenCalledWith(expect.objectContaining({
      definition: expect.objectContaining({ displayName }),
    }));
  });

  it.each([
    {
      valueType: 'date',
      displayName: '营业日期',
      invalid: '2026-9-03',
      valid: '2026-09-03',
      message: '日期格式必须为 YYYY-MM-DD。',
    },
    {
      valueType: 'time',
      displayName: '开门时间',
      invalid: '09:05',
      valid: '09:05:00',
      message: '时间格式必须为 HH:mm:ss。',
    },
  ] as const)('accepts only the canonical $valueType authored literal format', ({
    valueType, displayName, invalid, valid, message,
  }) => {
    const onIntent = vi.fn();
    render(<TemplateEditorDataSources
      designDsl={{ definitions: [] }}
      staticSchema={{ state: 'ready', snapshot: schemaSnapshot() }}
      disabled={false}
      onIntent={onIntent}
    />);

    fireEvent.click(screen.getByRole('button', { name: '新建定义数据源' }));
    fireEvent.change(screen.getByLabelText('定义名称'), { target: { value: displayName } });
    fireEvent.change(screen.getByLabelText('数据类型'), { target: { value: valueType } });
    fireEvent.change(screen.getByLabelText('默认值'), { target: { value: invalid } });
    fireEvent.click(screen.getByRole('button', { name: '创建定义' }));

    expect(screen.getByRole('alert').textContent).toBe(message);
    expect(onIntent).not.toHaveBeenCalled();

    fireEvent.change(screen.getByLabelText('默认值'), { target: { value: valid } });
    fireEvent.click(screen.getByRole('button', { name: '创建定义' }));
    expect(onIntent).toHaveBeenCalledWith({
      operation: 'create-definition',
      definition: {
        kind: 'custom',
        displayName,
        exposure: 'PUBLIC',
        valueType,
        defaultValue: valid,
      },
    });
  });

  it.each([
    ['1.0', '1'],
    ['-0', '0'],
    ['1.25e3', '1250'],
  ])('canonicalizes authored decimal %s before dispatch', (authored, canonical) => {
    const onIntent = vi.fn();
    render(<TemplateEditorDataSources
      designDsl={{ definitions: [] }}
      staticSchema={{ state: 'ready', snapshot: schemaSnapshot() }}
      disabled={false}
      onIntent={onIntent}
    />);

    fireEvent.click(screen.getByRole('button', { name: '新建定义数据源' }));
    fireEvent.change(screen.getByLabelText('定义名称'), { target: { value: '精确数值' } });
    fireEvent.change(screen.getByLabelText('数据类型'), { target: { value: 'decimal' } });
    fireEvent.change(screen.getByLabelText('默认值'), { target: { value: authored } });
    fireEvent.click(screen.getByRole('button', { name: '创建定义' }));

    const value = onIntent.mock.calls[0]?.[0]?.definition?.defaultValue as unknown;
    expect(isLosslessNumber(value)).toBe(true);
    expect(String(value)).toBe(canonical);
  });

  it('rejects an authored decimal whose canonical expansion exceeds the document budget', () => {
    const onIntent = vi.fn();
    render(<TemplateEditorDataSources
      designDsl={{ definitions: [] }}
      staticSchema={{ state: 'ready', snapshot: schemaSnapshot() }}
      disabled={false}
      onIntent={onIntent}
    />);

    fireEvent.click(screen.getByRole('button', { name: '新建定义数据源' }));
    fireEvent.change(screen.getByLabelText('定义名称'), { target: { value: '过大数值' } });
    fireEvent.change(screen.getByLabelText('数据类型'), { target: { value: 'decimal' } });
    fireEvent.change(screen.getByLabelText('默认值'), { target: { value: '1e20000000' } });
    fireEvent.click(screen.getByRole('button', { name: '创建定义' }));

    expect(screen.getByRole('alert').textContent).toContain('数值展开后超过文档容量限制');
    expect(onIntent).not.toHaveBeenCalled();
  });

  it('edits an existing definition without exposing or changing its identity', () => {
    const onIntent = vi.fn();
    render(<TemplateEditorDataSources
      designDsl={{
        definitions: [{
          definitionId: '20000000-0000-4000-8000-000000000009',
          kind: 'custom',
          displayName: '门店名称',
          exposure: 'PUBLIC',
          valueType: 'text',
          defaultValue: '默认门店',
        }],
      }}
      staticSchema={{ state: 'ready', snapshot: schemaSnapshot() }}
      disabled={false}
      onIntent={onIntent}
    />);

    fireEvent.click(screen.getByRole('button', { name: '编辑门店名称' }));
    expect(screen.getByRole('dialog', { name: '编辑门店名称' })).toBeTruthy();
    expect(screen.getByLabelText('定义类型').hasAttribute('disabled')).toBe(true);
    expect(screen.queryByLabelText('definitionId')).toBeNull();
    fireEvent.change(screen.getByLabelText('定义名称'), { target: { value: '门店简称' } });
    fireEvent.change(screen.getByLabelText('可见性'), { target: { value: 'PRIVATE' } });
    fireEvent.click(screen.getByRole('button', { name: '保存定义' }));

    expect(onIntent).toHaveBeenCalledWith({
      operation: 'update-definition',
      definitionId: '20000000-0000-4000-8000-000000000009',
      definition: {
        kind: 'custom', displayName: '门店简称', exposure: 'PRIVATE',
        valueType: 'text', defaultValue: '默认门店',
      },
    });
  });

  it.each([
    {
      name: '列表定义',
      valueType: { type: 'list', items: 'text' },
      defaultValue: ['甲', '乙'],
      typeLabel: '列表<文本>',
      valueLabel: '["甲","乙"]',
    },
    {
      name: '枚举定义',
      valueType: { type: 'enum', catalogId: 'priority' },
      defaultValue: 'HIGH',
      typeLabel: '枚举 · priority',
      valueLabel: '"HIGH"',
    },
  ])('opens an existing non-base $name in a lossless read-only view', ({
    name, valueType, defaultValue, typeLabel, valueLabel,
  }) => {
    const onIntent = vi.fn();
    const definition = {
      definitionId: '20000000-0000-4000-8000-000000000010',
      kind: 'custom',
      displayName: name,
      exposure: 'PRIVATE',
      valueType,
      defaultValue,
    };
    render(<TemplateEditorDataSources
      designDsl={{ definitions: [definition] }}
      staticSchema={{ state: 'ready', snapshot: schemaSnapshot() }}
      disabled={false}
      onIntent={onIntent}
    />);

    expect(screen.queryByRole('button', { name: `编辑${name}` })).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: `查看${name}` }));

    expect(screen.getByRole('dialog', { name: `查看${name}` })).toBeTruthy();
    expect(screen.getByText('当前类型仅支持只读查看；原 DesignDSL 保持不变。')).toBeTruthy();
    expect(screen.getByText(typeLabel)).toBeTruthy();
    expect(screen.getByText(valueLabel)).toBeTruthy();
    expect(screen.queryByRole('button', { name: '保存定义' })).toBeNull();
    expect(onIntent).not.toHaveBeenCalled();
  });

  it('creates a MappingDefinition with an explicit domain, context pointer and fallback', async () => {
    const onIntent = vi.fn();
    const getStaticSchema = vi.fn().mockRejectedValue(new Error('unused reference must not load'));
    render(<TemplateEditorDataSources
      designDsl={{ definitions: [] }}
      staticSchema={{ state: 'ready', snapshot: schemaSnapshot() }}
      referenceTransport={{ getStaticSchema }}
      disabled={false}
      onIntent={onIntent}
    />);

    fireEvent.click(screen.getByRole('button', { name: '新建定义数据源' }));
    fireEvent.change(screen.getByLabelText('定义类型'), { target: { value: 'mapping' } });
    fireEvent.change(screen.getByLabelText('定义名称'), { target: { value: '标题补全' } });
    fireEvent.change(screen.getByLabelText('映射输入指针'), { target: { value: '/title' } });
    fireEvent.change(screen.getByLabelText('分支 1 结果值'), { target: { value: '未命名' } });
    fireEvent.change(screen.getByLabelText('缺省来源指针'), { target: { value: '/title' } });
    fireEvent.click(screen.getByRole('button', { name: '创建定义' }));

    const source = { kind: 'context', domain: 'invocation', pointer: '/title' };
    await waitFor(() => expect(onIntent).toHaveBeenCalledWith({
      operation: 'create-definition',
      definition: {
        kind: 'mapping', displayName: '标题补全', domain: 'invocation', output: 'text',
        input: source,
        cases: [{
          operator: 'IS_ABSENT',
          then: { kind: 'literal', valueType: 'text', value: '未命名' },
        }],
        otherwise: source,
      },
    }));
    expect(getStaticSchema).not.toHaveBeenCalled();
  });

  it('loads only the StaticSchema reference crossed by Mapping context sources', async () => {
    const onIntent = vi.fn();
    const referenced = referenceSnapshot();
    const getStaticSchema = vi.fn().mockResolvedValue(referenced);
    render(<TemplateEditorDataSources
      designDsl={{ definitions: [] }}
      staticSchema={{ state: 'ready', snapshot: schemaSnapshot() }}
      referenceTransport={{ getStaticSchema }}
      disabled={false}
      onIntent={onIntent}
    />);

    fireEvent.click(screen.getByRole('button', { name: '新建定义数据源' }));
    fireEvent.change(screen.getByLabelText('定义类型'), { target: { value: 'mapping' } });
    fireEvent.change(screen.getByLabelText('定义名称'), { target: { value: '品牌名称' } });
    fireEvent.change(screen.getByLabelText('映射输入指针'), { target: { value: '/brand/name' } });
    fireEvent.change(screen.getByLabelText('缺省来源指针'), { target: { value: '/brand/name' } });
    fireEvent.click(screen.getByRole('button', { name: '创建定义' }));

    await waitFor(() => expect(onIntent).toHaveBeenCalledWith(
      expect.objectContaining({ operation: 'create-definition' }),
      { staticSchemas: [referenced] },
    ));
    expect(getStaticSchema).toHaveBeenCalledOnce();
    expect(getStaticSchema).toHaveBeenCalledWith(
      { schemaKey: 'brand', versionTag: 'v2' },
      expect.any(AbortSignal),
    );
  });

  it('does not dispatch a Mapping when its required StaticSchema branch cannot load', async () => {
    const onIntent = vi.fn();
    const getStaticSchema = vi.fn().mockRejectedValue(new Error('引用加载失败'));
    render(<TemplateEditorDataSources
      designDsl={{ definitions: [] }}
      staticSchema={{ state: 'ready', snapshot: schemaSnapshot() }}
      referenceTransport={{ getStaticSchema }}
      disabled={false}
      onIntent={onIntent}
    />);

    fireEvent.click(screen.getByRole('button', { name: '新建定义数据源' }));
    fireEvent.change(screen.getByLabelText('定义类型'), { target: { value: 'mapping' } });
    fireEvent.change(screen.getByLabelText('定义名称'), { target: { value: '品牌名称' } });
    fireEvent.change(screen.getByLabelText('映射输入指针'), { target: { value: '/brand/name' } });
    fireEvent.change(screen.getByLabelText('缺省来源指针'), { target: { value: '/brand/name' } });
    fireEvent.click(screen.getByRole('button', { name: '创建定义' }));

    expect((await screen.findByRole('alert')).textContent).toContain('引用加载失败');
    expect(onIntent).not.toHaveBeenCalled();
  });

  it('combines Repeat items and loop context pointers across Array<StaticSchema>', async () => {
    const onIntent = vi.fn();
    const loopId = '40000000-0000-4000-8000-000000000020';
    const product = productSnapshot();
    const getStaticSchema = vi.fn().mockResolvedValue(product);
    const root = schemaSnapshot();
    root.definition.fields.push({
      fieldKey: 'products', displayName: '商品', required: true,
      value: {
        type: 'array',
        items: { type: 'reference', ref: { schemaKey: 'product', versionTag: 'v3' } },
      },
    });
    render(<TemplateEditorDataSources
      designDsl={{
        definitions: [],
        designRoot: {
          nodeId: '10000000-0000-4000-8000-000000000020', kind: 'canvas',
          children: [{
            nodeId: '10000000-0000-4000-8000-000000000021', kind: 'repeat', loopId,
            items: { kind: 'context', domain: 'invocation', pointer: '/products' },
            children: [],
          }],
        },
      }}
      staticSchema={{ state: 'ready', snapshot: root }}
      referenceTransport={{ getStaticSchema }}
      disabled={false}
      onIntent={onIntent}
    />);

    fireEvent.click(screen.getByRole('button', { name: '新建定义数据源' }));
    fireEvent.change(screen.getByLabelText('定义类型'), { target: { value: 'mapping' } });
    fireEvent.change(screen.getByLabelText('定义名称'), { target: { value: '循环价格' } });
    fireEvent.change(screen.getByLabelText('定义域'), { target: { value: `loop:${loopId}` } });
    fireEvent.change(screen.getByLabelText('输出类型'), { target: { value: 'decimal' } });
    fireEvent.change(screen.getByLabelText('映射输入域'), { target: { value: `loop:${loopId}` } });
    fireEvent.change(screen.getByLabelText('映射输入指针'), { target: { value: '/price' } });
    fireEvent.change(screen.getByLabelText('缺省来源域'), { target: { value: `loop:${loopId}` } });
    fireEvent.change(screen.getByLabelText('缺省来源指针'), { target: { value: '/price' } });
    fireEvent.click(screen.getByRole('button', { name: '创建定义' }));

    await waitFor(() => expect(onIntent).toHaveBeenCalledWith(
      expect.objectContaining({ operation: 'create-definition' }),
      { staticSchemas: [product] },
    ));
    expect(getStaticSchema).toHaveBeenCalledWith(
      { schemaKey: 'product', versionTag: 'v3' },
      expect.any(AbortSignal),
    );
  });

  it('composes nested Repeat context pointers into the exact referenced closure', async () => {
    const onIntent = vi.fn();
    const outerLoopId = '40000000-0000-4000-8000-000000000030';
    const innerLoopId = '40000000-0000-4000-8000-000000000031';
    const group = groupSnapshot();
    const product = productSnapshot();
    const getStaticSchema = vi.fn(async ({ schemaKey }: { schemaKey: string }) => (
      schemaKey === 'group' ? group : product
    ));
    const root = schemaSnapshot();
    root.definition.fields.push({
      fieldKey: 'groups', displayName: '分组', required: true,
      value: {
        type: 'array',
        items: { type: 'reference', ref: { schemaKey: 'group', versionTag: 'v1' } },
      },
    });
    render(<TemplateEditorDataSources
      designDsl={{
        definitions: [],
        designRoot: {
          nodeId: '10000000-0000-4000-8000-000000000030', kind: 'canvas',
          children: [{
            nodeId: '10000000-0000-4000-8000-000000000031', kind: 'repeat',
            loopId: outerLoopId,
            items: { kind: 'context', domain: 'invocation', pointer: '/groups' },
            children: [{
              nodeId: '10000000-0000-4000-8000-000000000032', kind: 'repeat',
              loopId: innerLoopId,
              items: {
                kind: 'context', domain: { kind: 'loop', loopId: outerLoopId },
                pointer: '/products',
              },
              children: [],
            }],
          }],
        },
      }}
      staticSchema={{ state: 'ready', snapshot: root }}
      referenceTransport={{ getStaticSchema }}
      disabled={false}
      onIntent={onIntent}
    />);

    fireEvent.click(screen.getByRole('button', { name: '新建定义数据源' }));
    fireEvent.change(screen.getByLabelText('定义类型'), { target: { value: 'mapping' } });
    fireEvent.change(screen.getByLabelText('定义名称'), { target: { value: '嵌套循环价格' } });
    fireEvent.change(screen.getByLabelText('定义域'), { target: { value: `loop:${innerLoopId}` } });
    fireEvent.change(screen.getByLabelText('输出类型'), { target: { value: 'decimal' } });
    fireEvent.change(screen.getByLabelText('映射输入域'), { target: { value: `loop:${innerLoopId}` } });
    fireEvent.change(screen.getByLabelText('映射输入指针'), { target: { value: '/price' } });
    fireEvent.change(screen.getByLabelText('缺省来源域'), { target: { value: `loop:${innerLoopId}` } });
    fireEvent.change(screen.getByLabelText('缺省来源指针'), { target: { value: '/price' } });
    fireEvent.click(screen.getByRole('button', { name: '创建定义' }));

    await waitFor(() => expect(onIntent).toHaveBeenCalledWith(
      expect.objectContaining({ operation: 'create-definition' }),
      { staticSchemas: [group, product] },
    ));
    expect(getStaticSchema).toHaveBeenCalledTimes(2);
  });

  it('creates an ExpressionDefinition in one selected Repeat domain', () => {
    const onIntent = vi.fn();
    const loopId = '40000000-0000-4000-8000-000000000001';
    render(<TemplateEditorDataSources
      designDsl={{
        definitions: [],
        designRoot: {
          nodeId: '10000000-0000-4000-8000-000000000001', kind: 'canvas',
          children: [{
            nodeId: '10000000-0000-4000-8000-000000000002', kind: 'repeat', loopId,
            children: [],
          }],
        },
      }}
      staticSchema={{ state: 'ready', snapshot: schemaSnapshot() }}
      disabled={false}
      onIntent={onIntent}
    />);

    fireEvent.click(screen.getByRole('button', { name: '新建定义数据源' }));
    fireEvent.change(screen.getByLabelText('定义类型'), { target: { value: 'expression' } });
    fireEvent.change(screen.getByLabelText('定义名称'), { target: { value: '循环标签' } });
    fireEvent.change(screen.getByLabelText('定义域'), { target: { value: `loop:${loopId}` } });
    fireEvent.change(screen.getByLabelText('表达式'), { target: { value: "  '标签'  " } });
    fireEvent.click(screen.getByRole('button', { name: '创建定义' }));

    expect(onIntent).toHaveBeenCalledWith({
      operation: 'create-definition',
      definition: {
        kind: 'expression', displayName: '循环标签',
        domain: { kind: 'loop', loopId }, output: 'text', inputs: [], source: "  '标签'  ",
      },
    });
  });

  it.each(['mapping', 'expression'] as const)(
    'keeps an existing %s definition domain immutable while saving other edits',
    async (kind) => {
      const onIntent = vi.fn();
      const loopId = '40000000-0000-4000-8000-000000000011';
      const alternateLoopId = '40000000-0000-4000-8000-000000000012';
      const domain = { kind: 'loop', loopId } as const;
      const hiddenSource = { kind: 'context', domain, pointer: '/hidden' };
      const definition = kind === 'mapping' ? {
        definitionId: '20000000-0000-4000-8000-000000000011',
        kind,
        displayName: '循环映射',
        domain,
        output: 'text',
        input: { kind: 'context', domain, pointer: '/title' },
        cases: [
          { operator: 'IS_ABSENT', then: { kind: 'literal', valueType: 'text', value: '备用' } },
          { operator: 'IS_PRESENT', then: hiddenSource },
        ],
        otherwise: { kind: 'context', domain, pointer: '/title' },
      } : {
        definitionId: '20000000-0000-4000-8000-000000000012',
        kind,
        displayName: '循环表达式',
        domain,
        output: 'text',
        inputs: [{ alias: 'hidden', source: hiddenSource }],
        source: 'hidden',
      };
      render(<TemplateEditorDataSources
        designDsl={{
          definitions: [definition],
          designRoot: {
            nodeId: '10000000-0000-4000-8000-000000000011',
            kind: 'canvas',
            children: [loopId, alternateLoopId].map((id) => ({
              nodeId: `node-${id}`,
              kind: 'repeat',
              loopId: id,
              children: [],
            })),
          },
        }}
        staticSchema={{ state: 'ready', snapshot: schemaSnapshot() }}
        disabled={false}
        onIntent={onIntent}
      />);

      fireEvent.click(screen.getByRole('button', { name: `编辑${definition.displayName}` }));
      const domainSelect = screen.getByLabelText('定义域') as HTMLSelectElement;
      expect(domainSelect.value).toBe(`loop:${loopId}`);
      expect(domainSelect.disabled).toBe(true);
      fireEvent.change(screen.getByLabelText('定义名称'), {
        target: { value: `${definition.displayName}已改` },
      });
      if (kind === 'mapping') {
        fireEvent.change(screen.getByLabelText('映射输入指针'), { target: { value: '/renamed' } });
      } else {
        fireEvent.change(screen.getByLabelText('表达式'), { target: { value: 'hidden + "!"' } });
      }
      fireEvent.click(screen.getByRole('button', { name: '保存定义' }));

      await waitFor(() => expect(onIntent).toHaveBeenCalled());

      const submitted = onIntent.mock.calls[0]?.[0] as {
        definition: Record<string, unknown>;
      };
      expect(submitted.definition.domain).toEqual(domain);
      if (kind === 'mapping') {
        const cases = submitted.definition.cases as Array<Record<string, unknown>>;
        expect(cases[1]).toEqual({ operator: 'IS_PRESENT', then: hiddenSource });
      } else {
        expect(submitted.definition.inputs).toEqual([{ alias: 'hidden', source: hiddenSource }]);
      }
    },
  );

  it('edits and reorders MappingDefinition sources independently without rewriting untouched sources', async () => {
    const onIntent = vi.fn();
    const definitionId = '20000000-0000-4000-8000-000000000020';
    const fallbackDefinitionId = '20000000-0000-4000-8000-000000000021';
    const definition = {
      definitionId,
      kind: 'mapping',
      displayName: '独立映射',
      domain: 'invocation',
      output: 'text',
      input: { kind: 'context', domain: 'invocation', pointer: '/input' },
      cases: [
        {
          operator: 'IS_ABSENT',
          then: { kind: 'literal', valueType: 'text', value: '缺失' },
        },
        {
          operator: 'EQ',
          operand: { valueType: 'text', value: 'A' },
          then: { kind: 'context', domain: 'invocation', pointer: '/case-result' },
        },
      ],
      otherwise: { kind: 'definition', definitionId: fallbackDefinitionId },
    };
    render(<TemplateEditorDataSources
      designDsl={{ definitions: [definition, {
        definitionId: fallbackDefinitionId,
        kind: 'custom',
        displayName: '后备定义',
        exposure: 'PRIVATE',
        valueType: 'text',
        defaultValue: '后备',
      }] }}
      staticSchema={{ state: 'ready', snapshot: schemaSnapshot() }}
      disabled={false}
      onIntent={onIntent}
    />);

    fireEvent.click(screen.getByRole('button', { name: '编辑独立映射' }));
    fireEvent.change(screen.getByLabelText('映射输入指针'), { target: { value: '/input-edited' } });
    fireEvent.change(screen.getByLabelText('分支 2 结果指针'), { target: { value: '/case-edited' } });
    fireEvent.click(screen.getByRole('button', { name: '上移分支 2' }));
    fireEvent.click(screen.getByRole('button', { name: '保存定义' }));

    await waitFor(() => expect(onIntent).toHaveBeenCalledWith({
      operation: 'update-definition',
      definitionId,
      definition: {
        kind: 'mapping',
        displayName: '独立映射',
        domain: 'invocation',
        output: 'text',
        input: { kind: 'context', domain: 'invocation', pointer: '/input-edited' },
        cases: [
          {
            operator: 'EQ',
            operand: { valueType: 'text', value: 'A' },
            then: { kind: 'context', domain: 'invocation', pointer: '/case-edited' },
          },
          {
            operator: 'IS_ABSENT',
            then: { kind: 'literal', valueType: 'text', value: '缺失' },
          },
        ],
        otherwise: { kind: 'definition', definitionId: fallbackDefinitionId },
      },
    }));
  });

  it('separates Custom, Mapping and Expression definitions into distinct visual groups', () => {
    render(<TemplateEditorDataSources
      designDsl={{
        definitions: [
          {
            definitionId: '20000000-0000-4000-8000-000000000031',
            kind: 'expression', displayName: '表达式标题', domain: 'invocation',
            output: 'text', inputs: [], source: "'标题'",
          },
          {
            definitionId: '20000000-0000-4000-8000-000000000032',
            kind: 'custom', displayName: '自定义标题', exposure: 'PRIVATE',
            valueType: 'text', defaultValue: '标题',
          },
          {
            definitionId: '20000000-0000-4000-8000-000000000033',
            kind: 'mapping', displayName: '映射标题', domain: 'invocation', output: 'text',
            input: { kind: 'context', domain: 'invocation', pointer: '/title' },
            cases: [{
              operator: 'IS_ABSENT',
              then: { kind: 'literal', valueType: 'text', value: '缺失' },
            }],
            otherwise: { kind: 'context', domain: 'invocation', pointer: '/title' },
          },
        ],
      }}
      staticSchema={{ state: 'ready', snapshot: schemaSnapshot() }}
      disabled={false}
      onIntent={vi.fn()}
    />);

    const custom = screen.getByRole('list', { name: '自定义定义' });
    const mapping = screen.getByRole('list', { name: '映射定义' });
    const expression = screen.getByRole('list', { name: '表达式定义' });
    expect(custom.textContent).toContain('自定义标题');
    expect(custom.textContent).not.toContain('映射标题');
    expect(mapping.textContent).toContain('映射标题');
    expect(mapping.textContent).not.toContain('表达式标题');
    expect(expression.textContent).toContain('表达式标题');
    expect(expression.textContent).not.toContain('自定义标题');
  });

  it('keeps a legal MappingDefinition outside the exact adapter explicitly read-only', () => {
    const onIntent = vi.fn();
    render(<TemplateEditorDataSources
      designDsl={{
        definitions: [{
          definitionId: '20000000-0000-4000-8000-000000000041',
          kind: 'mapping', displayName: '列表输入映射', domain: 'invocation', output: 'text',
          input: {
            kind: 'literal', valueType: { type: 'list', items: 'text' }, value: ['A', 'B'],
          },
          cases: [{
            operator: 'IS_PRESENT',
            then: { kind: 'literal', valueType: 'text', value: '存在' },
          }],
          otherwise: { kind: 'literal', valueType: 'text', value: '缺失' },
        }],
      }}
      staticSchema={{ state: 'ready', snapshot: schemaSnapshot() }}
      disabled={false}
      onIntent={onIntent}
    />);

    expect(screen.queryByRole('button', { name: '编辑列表输入映射' })).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: '查看列表输入映射' }));
    expect(screen.getByText('当前类型仅支持只读查看；原 DesignDSL 保持不变。')).toBeTruthy();
    expect(screen.queryByRole('button', { name: '保存定义' })).toBeNull();
    expect(onIntent).not.toHaveBeenCalled();
  });
});

function schemaSnapshot(): StaticSnapshot {
  return {
    schemaKey: 'price-card',
    versionTag: 'v7',
    origin: 'SYSTEM',
    sourceDraftRevision: null,
    compilerVersion: 'schema-compiler/1',
    releaseNote: null,
    referenceDepth: 1,
    publishedAt: '2026-09-03T00:00:00Z',
    definition: {
      dslVersion: 'renderweave-schema/1.0',
      displayName: '价签数据',
      fields: [
        {
          fieldKey: 'title',
          displayName: '商品标题',
          required: true,
          value: {
            type: 'text',
            constraints: { minLength: 1, maxLength: 40 },
          },
        },
        {
          fieldKey: 'brand',
          displayName: '品牌',
          required: true,
          value: {
            type: 'reference',
            ref: { schemaKey: 'brand', versionTag: 'v2' },
          },
        },
      ],
    },
  };
}

function referenceSnapshot(): StaticSnapshot {
  return {
    schemaKey: 'brand',
    versionTag: 'v2',
    origin: 'SYSTEM',
    sourceDraftRevision: null,
    compilerVersion: 'schema-compiler/1',
    releaseNote: null,
    referenceDepth: 0,
    publishedAt: '2026-09-03T00:00:00Z',
    definition: {
      dslVersion: 'renderweave-schema/1.0',
      displayName: '品牌资料',
      fields: [{
        fieldKey: 'name',
        displayName: '品牌名称',
        required: true,
        value: { type: 'text', constraints: { minLength: 2 } },
      }],
    },
  };
}

function productSnapshot(): StaticSnapshot {
  return {
    schemaKey: 'product', versionTag: 'v3', origin: 'SYSTEM',
    sourceDraftRevision: null, compilerVersion: 'schema-compiler/1', releaseNote: null,
    referenceDepth: 0, publishedAt: '2026-09-03T00:00:00Z',
    definition: {
      dslVersion: 'renderweave-schema/1.0', displayName: '商品',
      fields: [{
        fieldKey: 'price', displayName: '价格', required: true,
        value: { type: 'decimal', constraints: {} },
      }],
    },
  };
}

function groupSnapshot(): StaticSnapshot {
  return {
    schemaKey: 'group', versionTag: 'v1', origin: 'SYSTEM',
    sourceDraftRevision: null, compilerVersion: 'schema-compiler/1', releaseNote: null,
    referenceDepth: 1, publishedAt: '2026-09-03T00:00:00Z',
    definition: {
      dslVersion: 'renderweave-schema/1.0', displayName: '分组',
      fields: [{
        fieldKey: 'products', displayName: '商品', required: true,
        value: {
          type: 'array',
          items: { type: 'reference', ref: { schemaKey: 'product', versionTag: 'v3' } },
        },
      }],
    },
  };
}
