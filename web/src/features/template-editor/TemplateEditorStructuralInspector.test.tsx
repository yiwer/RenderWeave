// @vitest-environment happy-dom

import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import type { EditorNodeProjection } from './template-editor-model';
import type { StaticSnapshot } from '../schema-studio/lossless-api';
import type { TemplateStructuralAuthoringProjection } from './template-editor-structural-authoring';
import { TemplateEditorStructuralInspector } from './TemplateEditorStructuralInspector';

afterEach(cleanup);

describe('Template Editor structural inspector', () => {
  it('commits Repeat workflow fields through shared SelectField controls', () => {
    const onConfigure = vi.fn();
    const repeat = node({
      nodeId: 'repeat', kind: 'repeat', displayName: '循环商品', loopId: 'loop',
      // The canonical working copy may order object members differently from the
      // editor's projected option; source identity must stay semantic.
      items: { pointer: '/tags', domain: 'invocation', kind: 'context' },
      absentPolicy: 'EMPTY',
      itemLayout: { kind: 'STACK', direction: 'COLUMN', gapMm: 0 },
      instanceLayout: { kind: 'STACK', direction: 'COLUMN', gapMm: 0 },
      placement: {}, bindings: [], children: [],
    });
    render(<TemplateEditorStructuralInspector
      node={repeat}
      projection={projection({
        repeatSources: {
          repeat: [
            repeatSource('tags', '标签', '/tags', 'system-basic-text', 'scalar'),
            repeatSource('products', '商品', '/products', 'product', 'reference'),
          ],
        },
      })}
      templateCatalog={[]}
      designDsl={{ definitions: [], designRoot: {} }}
      staticSchema={staticSchema()}
      staticSchemas={[]}
      disabled={false}
      onConfigure={onConfigure}
      onPreviewSample={vi.fn()}
      onCreateLoopTemplate={vi.fn()}
      onSelectTemplateTarget={vi.fn()}
    />);

    expect(screen.getByLabelText('循环列表属性').textContent).toContain('标签');

    fireEvent.click(screen.getByLabelText('循环列表属性'));
    fireEvent.click(screen.getByRole('option', { name: /商品.*\/products/ }));
    expect(onConfigure).toHaveBeenCalledWith(expect.objectContaining({
      kind: 'repeat',
      items: { kind: 'context', domain: 'invocation', pointer: '/products' },
    }));

    fireEvent.click(screen.getByLabelText('循环布局方式'));
    fireEvent.click(screen.getByRole('option', { name: '网格' }));
    expect(onConfigure).toHaveBeenLastCalledWith(expect.objectContaining({
      kind: 'repeat', instanceLayout: { kind: 'GRID', columns: 2, columnGapMm: 0, rowGapMm: 0 },
    }));
  });

  it('edits item and instance STACK or GRID packing independently', () => {
    const onConfigure = vi.fn();
    const repeat = node({
      nodeId: 'repeat', kind: 'repeat', displayName: '循环商品', loopId: 'loop',
      items: { kind: 'context', domain: 'invocation', pointer: '/tags' },
      absentPolicy: 'EMPTY',
      itemLayout: { kind: 'GRID', columns: 3, columnGapMm: 1, rowGapMm: 2 },
      instanceLayout: { kind: 'STACK', direction: 'COLUMN', gapMm: 4 },
      placement: {}, bindings: [], children: [],
    });
    render(<TemplateEditorStructuralInspector
      node={repeat}
      projection={projection({ repeatSources: {
        repeat: [repeatSource('tags', '标签', '/tags', 'system-basic-text', 'scalar')],
      } })}
      templateCatalog={[]}
      designDsl={{ definitions: [], designRoot: {} }}
      staticSchema={staticSchema()}
      staticSchemas={[]}
      disabled={false}
      onConfigure={onConfigure}
      onPreviewSample={vi.fn()}
      onCreateLoopTemplate={vi.fn()}
      onSelectTemplateTarget={vi.fn()}
    />);

    const itemColumns = screen.getByRole('spinbutton', { name: '单项网格列数' });
    fireEvent.change(itemColumns, { target: { value: '5' } });
    fireEvent.blur(itemColumns);
    expect(onConfigure).toHaveBeenLastCalledWith(expect.objectContaining({
      itemLayout: { kind: 'GRID', columns: 5, columnGapMm: 1, rowGapMm: 2 },
      instanceLayout: { kind: 'STACK', direction: 'COLUMN', gapMm: 4 },
    }));

    const itemColumnGap = screen.getByRole('spinbutton', { name: '单项列间距' });
    fireEvent.change(itemColumnGap, { target: { value: '6' } });
    fireEvent.blur(itemColumnGap);
    expect(onConfigure).toHaveBeenLastCalledWith(expect.objectContaining({
      itemLayout: { kind: 'GRID', columns: 3, columnGapMm: 6, rowGapMm: 2 },
    }));

    const itemRowGap = screen.getByRole('spinbutton', { name: '单项行间距' });
    fireEvent.change(itemRowGap, { target: { value: '8' } });
    fireEvent.blur(itemRowGap);
    expect(onConfigure).toHaveBeenLastCalledWith(expect.objectContaining({
      itemLayout: { kind: 'GRID', columns: 3, columnGapMm: 1, rowGapMm: 8 },
    }));

    const instanceGap = screen.getByRole('spinbutton', { name: '循环间距' });
    fireEvent.change(instanceGap, { target: { value: '7' } });
    fireEvent.blur(instanceGap);
    expect(onConfigure).toHaveBeenLastCalledWith(expect.objectContaining({
      itemLayout: { kind: 'GRID', columns: 3, columnGapMm: 1, rowGapMm: 2 },
      instanceLayout: { kind: 'STACK', direction: 'COLUMN', gapMm: 7 },
    }));

    fireEvent.click(screen.getByLabelText('循环排列方向'));
    fireEvent.click(screen.getByRole('option', { name: '横向' }));
    expect(onConfigure).toHaveBeenLastCalledWith(expect.objectContaining({
      instanceLayout: { kind: 'STACK', direction: 'ROW', gapMm: 4 },
    }));

    fireEvent.click(screen.getByLabelText('单项布局方式'));
    fireEvent.click(screen.getByRole('option', { name: '堆叠' }));
    expect(onConfigure).toHaveBeenLastCalledWith(expect.objectContaining({
      itemLayout: { kind: 'STACK', direction: 'COLUMN', gapMm: 0 },
      instanceLayout: { kind: 'STACK', direction: 'COLUMN', gapMm: 4 },
    }));
  });

  it('drives local Repeat VALUES, EMPTY, ABSENT and ERROR projection samples', () => {
    const onPreview = vi.fn();
    const repeat = node({
      nodeId: 'repeat', kind: 'repeat', displayName: '循环标签', loopId: 'loop',
      items: { kind: 'context', domain: 'invocation', pointer: '/tags' },
      absentPolicy: 'EMPTY',
      itemLayout: { kind: 'STACK', direction: 'COLUMN', gapMm: 0 },
      instanceLayout: { kind: 'STACK', direction: 'COLUMN', gapMm: 0 },
      placement: {}, bindings: [], children: [],
    });
    render(<TemplateEditorStructuralInspector
      node={repeat}
      projection={projection({ repeatSources: {
        repeat: [repeatSource('tags', '标签', '/tags', 'system-basic-text', 'scalar')],
      } })}
      templateCatalog={[]}
      designDsl={{ definitions: [], designRoot: {} }}
      staticSchema={staticSchema()}
      staticSchemas={[]}
      disabled={false}
      onConfigure={vi.fn()}
      onPreviewSample={onPreview}
      onCreateLoopTemplate={vi.fn()}
      onSelectTemplateTarget={vi.fn()}
    />);

    for (const state of ['VALUES', 'EMPTY', 'ABSENT', 'ERROR']) {
      fireEvent.click(screen.getByRole('button', { name: state }));
    }
    expect(onPreview).toHaveBeenNthCalledWith(1, { state: 'value', value: ['A', 'B', 'C'] });
    expect(onPreview).toHaveBeenNthCalledWith(2, { state: 'value', value: [] });
    expect(onPreview).toHaveBeenNthCalledWith(3, { state: 'absent' });
    expect(onPreview).toHaveBeenNthCalledWith(4, { state: 'error', code: 'EDITOR_SAMPLE_ERROR' });
    expect(screen.getByRole('group', { name: '循环预览输入' })).toBeTruthy();
  });

  it('announces invalid Repeat numbers and resyncs an externally replaced value', () => {
    const onConfigure = vi.fn();
    const repeat = (gapMm: number) => node({
      nodeId: 'repeat', kind: 'repeat', displayName: '循环商品', loopId: 'loop',
      items: { kind: 'context', domain: 'invocation', pointer: '/tags' },
      absentPolicy: 'EMPTY',
      itemLayout: { kind: 'STACK', direction: 'COLUMN', gapMm },
      instanceLayout: { kind: 'STACK', direction: 'COLUMN', gapMm: 0 },
      placement: {}, bindings: [], children: [],
    });
    const inspector = (gapMm: number) => (
      <TemplateEditorStructuralInspector
        node={repeat(gapMm)}
        projection={projection({})}
        templateCatalog={[]}
        designDsl={{ definitions: [], designRoot: {} }}
        staticSchema={staticSchema()}
        staticSchemas={[]}
        disabled={false}
        onConfigure={onConfigure}
        onPreviewSample={vi.fn()}
        onCreateLoopTemplate={vi.fn()}
        onSelectTemplateTarget={vi.fn()}
      />
    );
    const view = render(inspector(0));
    const input = screen.getByRole('spinbutton', { name: '单项间距' }) as HTMLInputElement;

    fireEvent.change(input, { target: { value: '-1' } });
    fireEvent.blur(input);

    const problem = screen.getByRole('alert');
    expect(input.value).toBe('-1');
    expect(input.getAttribute('aria-invalid')).toBe('true');
    expect(input.getAttribute('aria-describedby')).toBe(problem.id);
    expect(problem.textContent).toContain('不小于 0');
    expect(onConfigure).not.toHaveBeenCalled();

    view.rerender(inspector(6));

    expect(input.value).toBe('6');
    expect(input.getAttribute('aria-invalid')).toBe('false');
    expect(input.getAttribute('aria-describedby')).toBeNull();
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('keeps a canonicalized reference source selected and exposes its exact Template targets', () => {
    const repeat = node({
      nodeId: 'repeat', kind: 'repeat', displayName: '循环商品', loopId: 'loop',
      items: { pointer: '/products', domain: 'invocation', kind: 'context' },
      absentPolicy: 'EMPTY',
      itemLayout: { kind: 'STACK', direction: 'COLUMN', gapMm: 0 },
      instanceLayout: { kind: 'STACK', direction: 'COLUMN', gapMm: 0 },
      placement: {}, bindings: [], children: [],
    });
    render(<TemplateEditorStructuralInspector
      node={repeat}
      projection={projection({
        repeatSources: {
          repeat: [repeatSource('products', '商品', '/products', 'product', 'reference')],
        },
      })}
      templateCatalog={[{
        templateId: '11111111-1111-4111-8111-111111111111',
        displayName: '商品卡片',
        staticSchema: { schemaKey: 'product', versionTag: 'v1' },
        revision: 2,
        readiness: 'READY',
        updatedAt: '2026-09-03T00:00:00Z',
      }]}
      designDsl={{ definitions: [], designRoot: {} }}
      staticSchema={staticSchema()}
      staticSchemas={[]}
      disabled={false}
      onConfigure={vi.fn()}
      onPreviewSample={vi.fn()}
      onCreateLoopTemplate={vi.fn()}
      onSelectTemplateTarget={vi.fn()}
    />);

    expect(screen.getByLabelText('循环列表属性').textContent).toContain('商品');
    expect(screen.getByLabelText('循环单项模板')).toBeTruthy();
  });

  it('keeps Conditional preview local and exposes loaded PUBLIC fill sources', () => {
    const onPreview = vi.fn();
    const conditional = node({
      nodeId: 'conditional', kind: 'conditional', displayName: '显示详情',
      condition: { kind: 'context', domain: 'invocation', pointer: '/showDetails' },
      absentPolicy: 'FALSE', placement: {}, bindings: [], children: [],
    });
    const view = render(<TemplateEditorStructuralInspector
      node={conditional}
      projection={projection({ booleanSources: { conditional: [{
        id: 'flag', label: '显示详情', source: {
          kind: 'context', domain: 'invocation', pointer: '/showDetails',
        }, origin: 'static-schema', presence: 'MAY_BE_ABSENT',
      }] } })}
      templateCatalog={[]}
      designDsl={{ definitions: [], designRoot: {} }}
      staticSchema={staticSchema()}
      staticSchemas={[]}
      disabled={false}
      onConfigure={vi.fn()}
      onPreviewSample={onPreview}
      onCreateLoopTemplate={vi.fn()}
      onSelectTemplateTarget={vi.fn()}
    />);
    fireEvent.click(screen.getByRole('button', { name: 'FALSE' }));
    fireEvent.click(screen.getByRole('button', { name: 'ABSENT' }));
    expect(onPreview).toHaveBeenNthCalledWith(1, { state: 'value', value: false });
    expect(onPreview).toHaveBeenNthCalledWith(2, { state: 'absent' });
    expect(view.getByRole('group', { name: '条件预览输入' })).toBeTruthy();
  });

  it('edits TemplateUse reference context policy and removes an optional list fill', () => {
    const onConfigure = vi.fn();
    const templateId = '11111111-1111-4111-8111-111111111111';
    const fillTargetId = '22222222-2222-4222-8222-222222222222';
    const wholeSelector = {
      kind: 'context', domain: { kind: 'invocation' }, pointer: '', contextAbsentPolicy: 'ERROR',
    };
    const existingFill = {
      targetDefinitionId: fillTargetId,
      source: { kind: 'context', domain: 'invocation', pointer: '/tags' },
    };
    const use = node({
      nodeId: 'use', kind: 'templateUse', displayName: '优惠卡',
      templateRef: { templateId }, contextSelector: wholeSelector, fills: [existingFill],
      placement: {}, bindings: [],
    });
    render(<TemplateEditorStructuralInspector
      node={use}
      projection={projection({
        templateTargets: { use: [{
          templateId,
          displayName: '优惠卡',
          staticSchema: { schemaKey: 'offer', versionTag: 'v1' },
          readiness: 'READY',
          state: 'eligible',
        }] },
        nodeStates: { use: {
          kind: 'templateUse',
          authoringState: 'READY',
          contextOptions: [{
            id: 'whole',
            label: '调用上下文',
            selector: wholeSelector,
            schema: { schemaKey: 'parent', versionTag: 'v1' },
            presence: 'CONCRETE',
          }, {
            id: 'featured-offer',
            label: '调用上下文 · 主推优惠',
            selector: {
              kind: 'context', domain: { kind: 'invocation' }, pointer: '/featuredOffer',
              contextAbsentPolicy: 'ERROR',
            },
            schema: { schemaKey: 'offer', versionTag: 'v1' },
            presence: 'MAY_BE_ABSENT',
          }],
          context: { state: 'READY', schema: { schemaKey: 'parent', versionTag: 'v1' } },
          fillTargets: [{
            definitionId: fillTargetId,
            displayName: '标签列表',
            valueType: { type: 'list', items: 'text' },
            sources: [{
              id: 'tags',
              label: '标签',
              source: existingFill.source,
              valueType: { type: 'list', items: 'text' },
              presence: 'MAY_BE_ABSENT',
            }],
          }],
          fills: [{ targetDefinitionId: fillTargetId, state: 'READY' }],
          problems: [],
        } },
      })}
      templateCatalog={[{
        templateId,
        displayName: '优惠卡',
        staticSchema: { schemaKey: 'offer', versionTag: 'v1' },
        revision: 1,
        readiness: 'READY',
        updatedAt: '2026-09-03T00:00:00Z',
      }]}
      designDsl={{ definitions: [], designRoot: {} }}
      staticSchema={staticSchema()}
      staticSchemas={[]}
      disabled={false}
      onConfigure={onConfigure}
      onPreviewSample={vi.fn()}
      onCreateLoopTemplate={vi.fn()}
      onSelectTemplateTarget={vi.fn()}
    />);

    fireEvent.click(screen.getByLabelText('子模板上下文'));
    fireEvent.click(screen.getByRole('option', { name: /主推优惠.*featuredOffer/ }));
    expect(onConfigure).toHaveBeenLastCalledWith(expect.objectContaining({
      kind: 'templateUse', templateId,
      contextSelector: expect.objectContaining({ pointer: '/featuredOffer', contextAbsentPolicy: 'ERROR' }),
      fills: [existingFill],
    }));

    fireEvent.click(screen.getByLabelText('上下文缺失策略'));
    fireEvent.click(screen.getByRole('option', { name: /SKIP/ }));
    expect(onConfigure).toHaveBeenLastCalledWith(expect.objectContaining({
      contextSelector: expect.objectContaining({ contextAbsentPolicy: 'SKIP' }),
    }));

    expect(screen.getByLabelText('标签列表 来源').textContent).toContain('缺失时使用子模板默认值');
    fireEvent.click(screen.getByLabelText('标签列表 来源'));
    fireEvent.click(screen.getByRole('option', { name: '使用子模板默认值' }));
    expect(onConfigure).toHaveBeenLastCalledWith(expect.objectContaining({ fills: [] }));
  });
});

function node(value: Record<string, unknown>): EditorNodeProjection {
  return {
    nodeId: String(value.nodeId), kind: String(value.kind), displayName: String(value.displayName),
    depth: 1, childCount: Array.isArray(value.children) ? value.children.length : 0, value,
  };
}

function repeatSource(
  id: string,
  label: string,
  pointer: string,
  schemaKey: string,
  itemKind: 'scalar' | 'reference',
) {
  return {
    id, label, source: { kind: 'context', domain: 'invocation' as const, pointer },
    origin: 'static-schema' as const, presence: 'CONCRETE' as const,
    itemContext: { schemaKey, versionTag: 'v1' }, itemKind,
  };
}

function projection(
  overrides: Partial<TemplateStructuralAuthoringProjection>,
): TemplateStructuralAuthoringProjection {
  return {
    repeatSources: {}, booleanSources: {}, templateTargets: {}, loopContexts: [], nodeStates: {},
    ...overrides,
  };
}

function staticSchema(): StaticSnapshot {
  return {
    schemaKey: 'parent', versionTag: 'v1', revision: 1,
    contentHash: 'a'.repeat(64), readiness: 'READY' as const,
    definition: {
      dslVersion: 'renderweave-schema/1.0' as const,
      displayName: '输入',
      fields: [{
        fieldKey: 'showDetails', displayName: '显示详情', required: false,
        value: { type: 'boolean' as const },
      }],
    },
  } as unknown as StaticSnapshot;
}
