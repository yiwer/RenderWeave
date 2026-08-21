// @vitest-environment happy-dom

import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import {
  createRawRepairSession,
  createSessionFromBaseline,
} from './template-editor-model';
import { TemplateEditorShell, TemplateEditorSurface } from './TemplateEditorShell';
import type { TemplateEditorTransport } from './template-open';
import {
  currentResponse,
  recheckResponse,
  structuredBaseline,
} from './template-editor-test-support';

afterEach(cleanup);

describe('Template Editor E1 Product shell', () => {
  it('renders the approved Canvas Focus workbench with only real E1 behavior', () => {
    const session = createSessionFromBaseline(
      structuredBaseline(),
      { state: 'checked', value: 'READY' },
    );
    render(<TemplateEditorShell session={session} />);

    expect(screen.getByRole('main', { name: 'Template 编辑工作区' })).toBeTruthy();
    expect(screen.getByRole('navigation', { name: '编辑器入口' })).toBeTruthy();
    expect(screen.getAllByRole('button', { name: /^(结构|节点|资产|定义|交换)$/ })).toHaveLength(5);
    expect(screen.getByText('浏览器只读投影 · 非权威')).toBeTruthy();
    expect(screen.getByText('revision 7')).toBeTruthy();
    expect(screen.getByText('READY')).toBeTruthy();
    expect(screen.getByRole('tree', { name: 'DesignDSL 结构' })).toBeTruthy();

    fireEvent.click(screen.getByRole('treeitem', { name: /底色/ }));
    expect(screen.getByRole('heading', { name: '底色' })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '定义' }));
    expect(screen.getByText('0 个定义')).toBeTruthy();

    expect(screen.queryByRole('button', { name: /保存|预览|导入|恢复/ })).toBeNull();
  });

  it('does not guess a canvas in Compatibility Read-only or a server baseline in Raw Repair', () => {
    const compatibilityBaseline = structuredBaseline();
    compatibilityBaseline.designDsl.dslVersion = 'renderweave-design/2.0';
    const { rerender } = render(<TemplateEditorShell session={createSessionFromBaseline(
      compatibilityBaseline,
      { state: 'checked', value: 'READY' },
    )} />);

    expect(screen.getByRole('heading', { name: 'Compatibility Read-only' })).toBeTruthy();
    expect(screen.queryByRole('tree')).toBeNull();
    expect(screen.getByText(/不会被部分重序列化/)).toBeTruthy();

    rerender(<TemplateEditorShell session={createRawRepairSession(
      '{"dslVersion":',
      'JSON 未闭合',
    )} />);
    expect(screen.getByRole('heading', { name: 'Raw Repair' })).toBeTruthy();
    expect(screen.getByText('JSON 未闭合')).toBeTruthy();
    expect(screen.queryByText('revision 7')).toBeNull();
  });

  it('shows a trusted baseline while recheck is pending, then announces the bound result', async () => {
    let resolveRecheck!: (value: string) => void;
    const transport: TemplateEditorTransport = {
      getCurrent: vi.fn().mockResolvedValue(currentResponse('7')),
      recheckCurrent: vi.fn().mockReturnValue(new Promise<string>((resolve) => {
        resolveRecheck = resolve;
      })),
    };
    render(<TemplateEditorSurface
      templateId="9034a1da-5a76-469c-8de0-516eebf2c742"
      transport={transport}
    />);

    expect(await screen.findByText('权威重检中')).toBeTruthy();
    expect(screen.getByText('revision 7')).toBeTruthy();
    resolveRecheck(recheckResponse('7', 'READY'));
    await waitFor(() => expect(screen.getByText('READY')).toBeTruthy());
  });

  it('offers a keyboard-reachable retry when the trusted current cannot be read', async () => {
    const transport: TemplateEditorTransport = {
      getCurrent: vi.fn()
        .mockRejectedValueOnce(new Error('offline'))
        .mockResolvedValueOnce(currentResponse('7')),
      recheckCurrent: vi.fn().mockResolvedValue(recheckResponse('7', 'READY')),
    };
    render(<TemplateEditorSurface
      templateId="9034a1da-5a76-469c-8de0-516eebf2c742"
      transport={transport}
    />);

    const retry = await screen.findByRole('button', { name: '重试打开 Template' });
    fireEvent.click(retry);

    await waitFor(() => expect(screen.getByText('READY')).toBeTruthy());
    expect(transport.getCurrent).toHaveBeenCalledTimes(2);
  });

  it('ignores an obsolete open generation when the requested Template changes', async () => {
    const firstTemplateId = 'first-template';
    const secondTemplateId = 'second-template';
    let resolveFirst!: (value: string) => void;
    const transport: TemplateEditorTransport = {
      getCurrent: vi.fn()
        .mockReturnValueOnce(new Promise<string>((resolve) => {
          resolveFirst = resolve;
        }))
        .mockResolvedValueOnce(currentResponse('8', secondTemplateId)),
      recheckCurrent: vi.fn().mockResolvedValue(
        recheckResponse('8', 'READY', secondTemplateId),
      ),
    };
    const view = render(<TemplateEditorSurface
      templateId={firstTemplateId}
      transport={transport}
    />);

    view.rerender(<TemplateEditorSurface
      templateId={secondTemplateId}
      transport={transport}
    />);
    expect(await screen.findByText('revision 8')).toBeTruthy();

    resolveFirst(currentResponse('7', firstTemplateId));
    await Promise.resolve();
    expect(screen.getByText('revision 8')).toBeTruthy();
    expect(screen.queryByText('revision 7')).toBeNull();
  });
});
