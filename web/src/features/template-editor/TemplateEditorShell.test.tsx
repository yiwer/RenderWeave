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

describe('Template Editor E1/E2 Product shell', () => {
  it('renders the approved Canvas Focus workbench with only real behavior', () => {
    const session = createSessionFromBaseline(
      structuredBaseline(),
      { state: 'checked', value: 'READY' },
    );
    render(<TemplateEditorShell session={session} />);

    expect(screen.getByRole('main', { name: 'Template 编辑工作区' })).toBeTruthy();
    expect(screen.getByRole('navigation', { name: '编辑器入口' })).toBeTruthy();
    expect(screen.getAllByRole('button', { name: /^(结构|节点|资产|定义|交换)$/ })).toHaveLength(5);
    expect(screen.getByText('本地草稿投影 · 非权威')).toBeTruthy();
    expect(screen.getByText('revision 7')).toBeTruthy();
    expect(screen.getByText('READY')).toBeTruthy();
    expect(screen.getByRole('tree', { name: 'DesignDSL 结构' })).toBeTruthy();

    fireEvent.click(screen.getByRole('treeitem', { name: /底色/ }));
    expect(screen.getByRole('heading', { name: '底色' })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '定义' }));
    expect(screen.getByText('0 个定义')).toBeTruthy();

    expect(screen.queryByRole('button', { name: /保存|预览|导入|恢复/ })).toBeNull();
  });

  it('edits the local canonical name and exposes real undo/redo without save or preview actions', () => {
    const session = createSessionFromBaseline(
      structuredBaseline(),
      { state: 'checked', value: 'READY' },
    );
    render(<TemplateEditorShell session={session} />);

    const name = screen.getByRole('textbox', { name: 'Template 名称' });
    fireEvent.change(name, { target: { value: ' \t新价签 😀\r\n' } });
    fireEvent.click(screen.getByRole('button', { name: '应用本地名称' }));

    expect(screen.getByRole('heading', { level: 1, name: '新价签 😀' })).toBeTruthy();
    expect(screen.getByText('Canonical 本地草稿')).toBeTruthy();
    expect(screen.getByText(/本地草稿尚未成为 current/)).toBeTruthy();
    expect(screen.getByRole('button', { name: '撤销本地编辑' }).hasAttribute('disabled')).toBe(false);
    expect(screen.getByRole('button', { name: '重做本地编辑' }).hasAttribute('disabled')).toBe(true);

    fireEvent.click(screen.getByRole('button', { name: '撤销本地编辑' }));
    expect(screen.getByRole('heading', { level: 1, name: '门店价签' })).toBeTruthy();
    expect(screen.getByText('Canonical current')).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: '重做本地编辑' }));
    expect(screen.getByRole('heading', { level: 1, name: '新价签 😀' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: /保存|预览|导入|恢复/ })).toBeNull();
  });

  it('validates the local name without changing canonical state', () => {
    const session = createSessionFromBaseline(
      structuredBaseline(),
      { state: 'checked', value: 'READY' },
    );
    render(<TemplateEditorShell session={session} />);

    fireEvent.change(screen.getByRole('textbox', { name: 'Template 名称' }), {
      target: { value: '   ' },
    });
    fireEvent.click(screen.getByRole('button', { name: '应用本地名称' }));

    expect(screen.getByRole('alert').textContent).toContain('不能为空');
    expect(screen.getByRole('heading', { level: 1, name: '门店价签' })).toBeTruthy();
    expect(screen.getByText('Canonical current')).toBeTruthy();
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
    fireEvent.change(screen.getByRole('textbox', { name: 'Template 名称' }), {
      target: { value: '重检期间草稿' },
    });
    fireEvent.click(screen.getByRole('button', { name: '应用本地名称' }));
    resolveRecheck(recheckResponse('7', 'READY'));
    await waitFor(() => expect(screen.getByText('READY')).toBeTruthy());
    expect(screen.getByRole('heading', { level: 1, name: '重检期间草稿' })).toBeTruthy();
    expect(screen.getByText('Canonical 本地草稿')).toBeTruthy();
  });

  it('does not let an unavailable-readiness retry replace a dirty local draft', () => {
    const onRetryReadiness = vi.fn();
    const baseline = structuredBaseline();
    const view = render(<TemplateEditorShell
      session={createSessionFromBaseline(baseline, {
        state: 'unavailable', message: 'offline',
      })}
      onRetryReadiness={onRetryReadiness}
    />);

    expect(screen.getByRole('button', { name: '重试权威重检' })).toBeTruthy();
    fireEvent.change(screen.getByRole('textbox', { name: 'Template 名称' }), {
      target: { value: '离线草稿' },
    });
    fireEvent.click(screen.getByRole('button', { name: '应用本地名称' }));
    expect(screen.queryByRole('button', { name: '重试权威重检' })).toBeNull();
    expect(screen.getByText(/本地草稿已保留/)).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: '撤销本地编辑' }));
    expect(screen.getByRole('button', { name: '重试权威重检' })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '重做本地编辑' }));

    view.rerender(<TemplateEditorShell
      session={createSessionFromBaseline(structuredBaseline(), {
        state: 'checked', value: 'INVALID',
      })}
      onRetryReadiness={onRetryReadiness}
    />);
    expect(screen.getByRole('heading', { level: 1, name: '离线草稿' })).toBeTruthy();
    expect(screen.getByText('INVALID')).toBeTruthy();
    expect(onRetryReadiness).not.toHaveBeenCalled();
  });

  it('clears the local working copy and history only when baseline identity advances', () => {
    const first = createSessionFromBaseline(
      structuredBaseline(),
      { state: 'checked', value: 'READY' },
    );
    const view = render(<TemplateEditorShell session={first} />);
    fireEvent.change(screen.getByRole('textbox', { name: 'Template 名称' }), {
      target: { value: '旧 revision 草稿' },
    });
    fireEvent.click(screen.getByRole('button', { name: '应用本地名称' }));

    const advanced = structuredBaseline();
    advanced.revision = '8';
    advanced.contentHash = 'sha256:' + 'b'.repeat(64);
    advanced.designDsl.displayName = '服务器新 revision';
    advanced.canonicalDesignDsl = advanced.canonicalDesignDsl.replace(
      '"displayName":"门店价签"',
      '"displayName":"服务器新 revision"',
    );
    view.rerender(<TemplateEditorShell session={createSessionFromBaseline(
      advanced,
      { state: 'checked', value: 'READY' },
    )} />);

    expect(screen.getByRole('heading', { level: 1, name: '服务器新 revision' })).toBeTruthy();
    expect(screen.getByText('revision 8')).toBeTruthy();
    expect(screen.getByText('Canonical current')).toBeTruthy();
    expect(screen.getByRole('button', { name: '撤销本地编辑' }).hasAttribute('disabled')).toBe(true);
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
