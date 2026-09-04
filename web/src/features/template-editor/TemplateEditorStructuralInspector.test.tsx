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
