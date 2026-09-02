// @vitest-environment happy-dom

import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import {
  createRawRepairSession,
  createSessionFromBaseline,
  type StructuredEditorSession,
} from './template-editor-model';
import { applyTemplateDisplayName } from './template-editor-session';
import { TemplateEditorShell, TemplateEditorSurface } from './TemplateEditorShell';
import { TemplateRequestError, type TemplateEditorTransport } from './template-open';
import type { TemplateSaveTransport } from './template-save';
import {
  currentResponse,
  recheckResponse,
  structuredBaseline,
} from './template-editor-test-support';

afterEach(() => {
  cleanup();
  localStorage.clear();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe('Template Editor E1/E2 Product shell', () => {
  it('authors a Rect through the formal node library and synchronizes every working-copy projection', () => {
    vi.spyOn(globalThis.crypto, 'randomUUID')
      .mockReturnValue('11111111-1111-4111-8111-111111111111');
    const session = createSessionFromBaseline(
      structuredBaseline(),
      { state: 'checked', value: 'READY' },
    );
    render(<TemplateEditorShell session={session} saveTransport={saveTransport({})} />);

    fireEvent.click(screen.getByRole('button', { name: '元素' }));
    const addRect = screen.getByRole('button', { name: '添加矩形' });
    addRect.focus();
    expect(document.activeElement).toBe(addRect);
    fireEvent.click(addRect);

    expect(screen.getByRole('button', { name: '结构' }).getAttribute('aria-current')).toBe('page');
    expect(screen.getByRole('treeitem', { name: /矩形 2/ }).getAttribute('aria-selected')).toBe('true');
    expect(screen.getByRole('heading', { name: '矩形 2' })).toBeTruthy();
    expect(screen.getAllByText('矩形 2').length).toBeGreaterThanOrEqual(2);
    const authoredRect = document.querySelector<HTMLElement>(
      '[data-template-canvas-node-id="11111111-1111-4111-8111-111111111111"]',
    );
    expect(authoredRect).not.toBeNull();
    expect(authoredRect?.dataset.templateCanvasNodeKind).toBe('rect');
    expect(authoredRect?.style.left).toBe('101.6px');
    expect(authoredRect?.style.top).toBe('101.6px');
    expect(authoredRect?.style.width).toBe('101.6px');
    expect(authoredRect?.style.height).toBe('101.6px');

    fireEvent.click(screen.getByRole('treeitem', { name: /画布/ }));
    expect(screen.getByRole('heading', { name: '画布' })).toBeTruthy();
    fireEvent.click(authoredRect as HTMLElement);
    expect(screen.getByRole('treeitem', { name: /矩形 2/ }).getAttribute('aria-selected')).toBe('true');
    expect(screen.getByRole('heading', { name: '矩形 2' })).toBeTruthy();

    const viewport = document.querySelector<HTMLElement>('[data-template-canvas-viewport]');
    expect(viewport).not.toBeNull();
    const beforeScale = Number(viewport?.dataset.canvasScale);
    fireEvent.wheel(viewport as HTMLElement, {
      deltaY: -120,
      clientX: 240,
      clientY: 180,
    });
    expect(Number(viewport?.dataset.canvasScale)).toBeGreaterThan(beforeScale);
    expect(screen.getByRole('button', { name: '适合画板' })).toBeTruthy();
    expect(screen.getByRole('button', { name: '重置画布缩放到 100%' })).toBeTruthy();
    expect(screen.getByText('Canonical 本地草稿')).toBeTruthy();
    expect(screen.getByRole('button', { name: '保存 canonical 本地草稿' }).hasAttribute('disabled')).toBe(false);

    fireEvent.click(screen.getByRole('button', { name: '撤销本地编辑' }));
    expect(screen.queryByRole('treeitem', { name: /矩形 2/ })).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: '重做本地编辑' }));
    expect(screen.getByRole('treeitem', { name: /矩形 2/ }).getAttribute('aria-selected')).toBe('true');
  });

  it('routes core container creation and tree rename through one canonical history', () => {
    vi.spyOn(globalThis.crypto, 'randomUUID')
      .mockReturnValueOnce('11111111-1111-4111-8111-111111111111')
      .mockReturnValueOnce('22222222-2222-4222-8222-222222222222')
      .mockReturnValueOnce('33333333-3333-4333-8333-333333333333');
    const session = createSessionFromBaseline(
      structuredBaseline(),
      { state: 'checked', value: 'READY' },
    );
    render(<TemplateEditorShell session={session} saveTransport={saveTransport({})} />);

    fireEvent.click(screen.getByRole('button', { name: '容器' }));
    fireEvent.click(screen.getByRole('button', { name: '添加框架' }));
    expect(screen.getByRole('treeitem', { name: /框架 2/ }).getAttribute('aria-selected'))
      .toBe('true');

    fireEvent.click(screen.getByRole('button', { name: '容器' }));
    fireEvent.click(screen.getByRole('button', { name: '添加堆叠容器' }));
    expect(screen.getByRole('treeitem', { name: /堆叠 1/ }).getAttribute('aria-level')).toBe('3');

    fireEvent.click(screen.getByRole('button', { name: '元素' }));
    fireEvent.click(screen.getByRole('button', { name: '添加矩形' }));
    const rect = screen.getByRole('treeitem', { name: /矩形 2/ });
    expect(rect.getAttribute('aria-level')).toBe('4');
    expect(document.querySelector('[data-template-canvas-editor-overlay]')).not.toBeNull();

    fireEvent.keyDown(rect, { key: 'F2' });
    const rename = screen.getByRole('textbox', { name: '重命名 矩形 2' });
    fireEvent.change(rename, { target: { value: '售价底板' } });
    fireEvent.keyDown(rename, { key: 'Enter' });
    expect(screen.getByRole('treeitem', { name: /售价底板/ })).toBeTruthy();
    expect(screen.getByText('Canonical 本地草稿')).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: '撤销本地编辑' }));
    expect(screen.getByRole('treeitem', { name: /矩形 2/ })).toBeTruthy();
  });

  it('renders the approved Canvas Focus workbench with only real behavior', () => {
    const session = createSessionFromBaseline(
      structuredBaseline(),
      { state: 'checked', value: 'READY' },
    );
    render(<TemplateEditorShell session={session} />);

    expect(screen.getByRole('main', { name: 'Template 编辑工作区' })).toBeTruthy();
    expect(screen.getByRole('navigation', { name: '编辑器入口' })).toBeTruthy();
    expect(screen.getAllByRole('button', { name: /^(元素|容器|资产|定义|结构)$/ }))
      .toHaveLength(5);
    expect(screen.getByRole('complementary', { name: '结构、资产与定义面板' })).toBeTruthy();
    expect(screen.getByText('本地草稿投影 · 非权威')).toBeTruthy();
    expect(screen.getByText('revision 7')).toBeTruthy();
    expect(screen.getByText('READY')).toBeTruthy();
    expect(screen.getByRole('tree', { name: 'DesignDSL 结构' })).toBeTruthy();
    expect(screen.getByRole('link', { name: '返回模板目录' }).getAttribute('href'))
      .toBe('/templates');

    fireEvent.click(screen.getByRole('treeitem', { name: /底色/ }));
    expect(screen.getByRole('heading', { name: '底色' })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '定义' }));
    expect(screen.getByRole('heading', { name: '定义' })).toBeTruthy();
    expect(screen.getByText('0 个定义')).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: '资产' }));
    expect(screen.getByRole('heading', { name: '资产' })).toBeTruthy();
    expect(screen.getByText('0 个已引用资产')).toBeTruthy();

    expect(screen.queryByRole('button', { name: /保存|预览|恢复/ })).toBeNull();
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
    expect(screen.queryByRole('button', { name: /保存|预览|恢复/ })).toBeNull();
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

  it('locks local controls while saving and adopts the verified next baseline', async () => {
    const session = dirtySession('待保存版本');
    let resolvePut!: (value: { status: number; body: string }) => void;
    const putCurrent = vi.fn().mockReturnValue(new Promise((resolve) => {
      resolvePut = resolve;
    }));
    const onSessionCommitted = vi.fn();
    render(<TemplateEditorShell
      session={session}
      saveTransport={saveTransport({ putCurrent })}
      onSessionCommitted={onSessionCommitted}
    />);

    fireEvent.click(screen.getByRole('button', { name: '保存 canonical 本地草稿' }));
    expect(screen.getByText('保存请求进行中')).toBeTruthy();
    expect(screen.getByRole('textbox', { name: 'Template 名称' }).hasAttribute('disabled')).toBe(true);
    expect(screen.getByRole('button', { name: '撤销本地编辑' }).hasAttribute('disabled')).toBe(true);
    await waitFor(() => expect(putCurrent).toHaveBeenCalledTimes(1));

    resolvePut({ status: 200, body: await saveResponse(session, '8') });
    await waitFor(() => expect(screen.getByText('revision 8')).toBeTruthy());
    expect(screen.getByText('Canonical current')).toBeTruthy();
    expect(screen.queryByRole('button', { name: '保存 canonical 本地草稿' })).toBeNull();
    expect(screen.getByRole('button', { name: '撤销本地编辑' }).hasAttribute('disabled')).toBe(true);
    expect(onSessionCommitted).toHaveBeenCalledTimes(1);
  });

  it('offers and cancels an explicit revision-bound overwrite without losing the draft', async () => {
    const session = dirtySession('冲突草稿');
    const transport = saveTransport({
      putCurrent: vi.fn().mockResolvedValue({
        status: 409,
        body: saveProblem('TEMPLATE_REVISION_CONFLICT', '8'),
      }),
    });
    render(<TemplateEditorShell session={session} saveTransport={transport} />);

    fireEvent.click(screen.getByRole('button', { name: '保存 canonical 本地草稿' }));
    expect(await screen.findByText('保存冲突')).toBeTruthy();
    expect(screen.getByRole('button', { name: '重读并覆盖 revision 8' })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '取消覆盖' }));

    expect(screen.queryByText('保存冲突')).toBeNull();
    expect(screen.getByRole('heading', { level: 1, name: '冲突草稿' })).toBeTruthy();
    expect(screen.getByText('Canonical 本地草稿')).toBeTruthy();
  });

  it('shows the complete dependency set in Canvas Focus and cancels without changing the draft', async () => {
    const session = dirtySession('Dependency draft');
    const transport = saveTransport({
      putCurrent: vi.fn().mockResolvedValue({
        status: 422,
        body: await invalidSaveProblem(session, 'a'.repeat(64)),
      }),
    });
    render(<TemplateEditorShell session={session} saveTransport={transport} />);

    fireEvent.click(screen.getByRole('button', { name: /canonical/ }));
    expect(await screen.findByRole('heading', { name: '确认仍保存为 INVALID' })).toBeTruthy();
    expect(screen.getByText('1 项依赖问题 · 完整未截断')).toBeTruthy();
    expect(screen.getByText('TEMPLATE_USE_FILL_TYPE_MISMATCH')).toBeTruthy();
    expect(screen.getByText('/designRoot/children/0/fills/0/source')).toBeTruthy();
    expect(screen.getByRole('button', { name: '仍保存为 INVALID' })).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: '取消 INVALID 保存' }));
    expect(screen.queryByRole('heading', { name: '确认仍保存为 INVALID' })).toBeNull();
    expect(screen.getByRole('heading', { level: 1, name: 'Dependency draft' })).toBeTruthy();
  });

  it('keeps one mutation lock while confirming and adopts the exact INVALID baseline', async () => {
    const session = dirtySession('Dependency draft');
    const token = 'a'.repeat(64);
    const putCurrent = vi.fn()
      .mockResolvedValueOnce({
        status: 422,
        body: await invalidSaveProblem(session, token),
      })
      .mockResolvedValueOnce({
        status: 200,
        body: await saveResponse(session, '8', 'INVALID'),
      });
    render(<TemplateEditorShell
      session={session}
      saveTransport={saveTransport({ putCurrent })}
    />);

    fireEvent.click(screen.getByRole('button', { name: /canonical/ }));
    fireEvent.click(await screen.findByRole('button', { name: '仍保存为 INVALID' }));
    expect(screen.getByRole('textbox', { name: 'Template 名称' }).hasAttribute('disabled')).toBe(true);

    await waitFor(() => expect(screen.getByText('revision 8')).toBeTruthy());
    expect(screen.getByText('INVALID')).toBeTruthy();
    expect(putCurrent).toHaveBeenNthCalledWith(
      2,
      session.baseline.templateId,
      '7',
      session.workingCopy.canonicalDesignDsl,
      expect.any(AbortSignal),
      token,
    );
  });

  it('forces re-confirmation after a trusted-current drift, then adopts the overwrite', async () => {
    const session = dirtySession('最终覆盖草稿');
    const remoteNine = cleanSessionAt('9');
    const putCurrent = vi.fn()
      .mockResolvedValueOnce({
        status: 409,
        body: saveProblem('TEMPLATE_REVISION_CONFLICT', '8'),
      })
      .mockResolvedValueOnce({ status: 200, body: await saveResponse(session, '10') });
    const getCurrent = vi.fn()
      .mockResolvedValueOnce(await saveResponse(remoteNine, '9'))
      .mockResolvedValueOnce(await saveResponse(remoteNine, '9'));
    render(<TemplateEditorShell
      session={session}
      saveTransport={saveTransport({ getCurrent, putCurrent })}
    />);

    fireEvent.click(screen.getByRole('button', { name: '保存 canonical 本地草稿' }));
    await screen.findByRole('button', { name: '重读并覆盖 revision 8' });
    fireEvent.click(screen.getByRole('button', { name: '重读并覆盖 revision 8' }));
    expect(await screen.findByRole('button', { name: '重读并覆盖 revision 9' })).toBeTruthy();
    expect(screen.getByText(/必须重新确认覆盖/)).toBeTruthy();
    expect(putCurrent).toHaveBeenCalledTimes(1);

    fireEvent.click(screen.getByRole('button', { name: '重读并覆盖 revision 9' }));
    await waitFor(() => expect(screen.getByText('revision 10')).toBeTruthy());
    expect(getCurrent).toHaveBeenCalledTimes(2);
    expect(putCurrent).toHaveBeenCalledTimes(2);
  });

  it('automatically reconciles an ambiguous save and exposes only an explicit exact retry', async () => {
    const session = dirtySession('未知结果草稿');
    const putCurrent = vi.fn()
      .mockRejectedValueOnce(new TypeError('connection lost'))
      .mockResolvedValueOnce({ status: 200, body: await saveResponse(session, '8') });
    const getCurrent = vi.fn().mockResolvedValue(await saveResponse(
      session,
      '7',
      'READY',
      session.baseline.canonicalDesignDsl,
    ));
    render(<TemplateEditorShell
      session={session}
      saveTransport={saveTransport({ getCurrent, putCurrent })}
    />);

    fireEvent.click(screen.getByRole('button', { name: '保存 canonical 本地草稿' }));
    expect(await screen.findByText('可以显式重试')).toBeTruthy();
    expect(getCurrent).toHaveBeenCalledTimes(1);
    expect(putCurrent).toHaveBeenCalledTimes(1);
    expect(screen.getByRole('textbox', { name: 'Template 名称' }).hasAttribute('disabled')).toBe(true);
    expect(screen.getByRole('button', { name: '保存 canonical 本地草稿' }).hasAttribute('disabled')).toBe(true);
    expect(screen.getByRole('button', { name: '显式重试原保存' })).toBeTruthy();
    expect(screen.getByRole('button', { name: '导出 canonical 本地草稿' })).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: '显式重试原保存' }));
    await waitFor(() => expect(screen.getByText('revision 8')).toBeTruthy());
    expect(putCurrent).toHaveBeenCalledTimes(2);
  });

  it('adopts a converged trusted current without attributing it to the ambiguous request', async () => {
    const session = dirtySession('服务器已收敛草稿');
    const transport = saveTransport({
      putCurrent: vi.fn().mockRejectedValue(new TypeError('response lost')),
      getCurrent: vi.fn().mockResolvedValue(await saveResponse(session, '9')),
    });
    const onSessionCommitted = vi.fn((
      committed: StructuredEditorSession,
      saveNotice?: string,
    ) => view.rerender(<TemplateEditorShell
      session={committed}
      saveTransport={transport}
      initialSaveNotice={saveNotice}
      onSessionCommitted={onSessionCommitted}
    />));
    const view = render(<TemplateEditorShell
      session={session}
      saveTransport={transport}
      onSessionCommitted={onSessionCommitted}
    />);

    fireEvent.click(screen.getByRole('button', { name: '保存 canonical 本地草稿' }));
    expect(await screen.findByText('内容已在服务器确认')).toBeTruthy();
    expect(screen.getByText(/不代表具体请求归属/)).toBeTruthy();
    expect(screen.getByText('revision 9')).toBeTruthy();
    expect(screen.getByText('Canonical current')).toBeTruthy();
    expect(screen.getByText('重检暂不可用')).toBeTruthy();
    expect(screen.queryByRole('button', { name: '显式重试原保存' })).toBeNull();
    expect(onSessionCommitted).toHaveBeenCalledTimes(1);
  });

  it('keeps unknown locked while trusted current is unavailable, then permits re-verification', async () => {
    const session = dirtySession('离线核验草稿');
    const getCurrent = vi.fn()
      .mockRejectedValueOnce(new TypeError('offline'))
      .mockResolvedValueOnce(await saveResponse(
        session,
        '7',
        'READY',
        session.baseline.canonicalDesignDsl,
      ));
    render(<TemplateEditorShell
      session={session}
      saveTransport={saveTransport({
        putCurrent: vi.fn().mockRejectedValue(new TypeError('response lost')),
        getCurrent,
      })}
    />);

    fireEvent.click(screen.getByRole('button', { name: '保存 canonical 本地草稿' }));
    expect(await screen.findByText('保存结果仍未知')).toBeTruthy();
    expect(screen.getByRole('button', { name: '重新核验 trusted current' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: '显式重试原保存' })).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: '重新核验 trusted current' }));
    expect(await screen.findByText('可以显式重试')).toBeTruthy();
    expect(getCurrent).toHaveBeenCalledTimes(2);
  });

  it('reuses conflict overwrite after reconciliation finds a later different current', async () => {
    const session = dirtySession('unknown conflict draft');
    const remote = dirtySession('remote different draft');
    render(<TemplateEditorShell
      session={session}
      saveTransport={saveTransport({
        putCurrent: vi.fn().mockRejectedValue(new TypeError('response lost')),
        getCurrent: vi.fn().mockResolvedValue(await saveResponse(remote, '11')),
      })}
    />);

    fireEvent.click(screen.getByRole('button', { name: '保存 canonical 本地草稿' }));
    expect(await screen.findByText('保存冲突')).toBeTruthy();
    expect(screen.getByRole('button', { name: '重读并覆盖 revision 11' })).toBeTruthy();
  });

  it('locks terminal deletion and fail-closed reconciliation while preserving exact draft export', async () => {
    const session = dirtySession('必须保留的草稿');
    const createObjectURL = vi.fn().mockReturnValue('blob:recovery-draft');
    const revokeObjectURL = vi.fn();
    vi.stubGlobal('URL', { createObjectURL, revokeObjectURL });
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined);
    const view = render(<TemplateEditorShell
      session={session}
      saveTransport={saveTransport({
        putCurrent: vi.fn().mockRejectedValue(new TypeError('response lost')),
        getCurrent: vi.fn().mockRejectedValue(
          new TemplateRequestError(410, 'TEMPLATE_DELETED'),
        ),
      })}
    />);

    fireEvent.click(screen.getByRole('button', { name: '保存 canonical 本地草稿' }));
    expect(await screen.findByText('Template 已删除')).toBeTruthy();
    expect(screen.getByRole('textbox', { name: 'Template 名称' }).hasAttribute('disabled')).toBe(true);
    fireEvent.click(screen.getByRole('button', { name: '导出 canonical 本地草稿' }));
    expect(createObjectURL).toHaveBeenCalledTimes(1);
    const blob = createObjectURL.mock.calls[0]?.[0] as Blob;
    expect(await blob.text()).toBe(session.workingCopy.canonicalDesignDsl);
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:recovery-draft');

    view.unmount();
    render(<TemplateEditorShell
      session={session}
      saveTransport={saveTransport({
        putCurrent: vi.fn().mockRejectedValue(new TypeError('response lost')),
        getCurrent: vi.fn().mockResolvedValue('{"not":"trusted current"}'),
      })}
    />);
    fireEvent.click(screen.getByRole('button', { name: '保存 canonical 本地草稿' }));
    expect(await screen.findByText('保存核验失败（fail closed）')).toBeTruthy();
    expect(screen.getByRole('button', { name: '导出 canonical 本地草稿' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: '显式重试原保存' })).toBeNull();
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

function dirtySession(name: string): StructuredEditorSession {
  const result = applyTemplateDisplayName(cleanSessionAt('7'), name);
  if (result.state !== 'applied') throw new Error(`expected applied, got ${result.state}`);
  return result.session;
}

function cleanSessionAt(revision: string): StructuredEditorSession {
  const baseline = structuredBaseline();
  baseline.revision = revision;
  const session = createSessionFromBaseline(baseline, { state: 'checked', value: 'READY' });
  if (session.mode !== 'structured') throw new Error('expected Structured Editor');
  return session;
}

function saveTransport(
  overrides: Partial<TemplateSaveTransport>,
): TemplateSaveTransport {
  return {
    getCurrent: vi.fn().mockRejectedValue(new Error('unexpected GET')),
    putCurrent: vi.fn().mockRejectedValue(new Error('unexpected PUT')),
    ...overrides,
  };
}

async function saveResponse(
  session: StructuredEditorSession,
  revision: string,
  readiness: 'READY' | 'INVALID' = 'READY',
  canonical = session.workingCopy.canonicalDesignDsl,
): Promise<string> {
  const bytes = new TextEncoder().encode('renderweave-design-content/1\0' + canonical);
  const digest = new Uint8Array(await crypto.subtle.digest('SHA-256', bytes));
  const hash = `sha256:${Array.from(
    digest,
    (byte) => byte.toString(16).padStart(2, '0'),
  ).join('')}`;
  const { templateId, staticSchema } = session.baseline;
  return `{"templateId":"${templateId}","disclosure":"READABLE","revision":${revision},`
    + `"staticSchema":{"schemaKey":"${staticSchema.schemaKey}","versionTag":"${staticSchema.versionTag}"},`
    + `"contentHash":"${hash}","readiness":"${readiness}","designDsl":${canonical}}`;
}

function saveProblem(code: string, currentRevision?: string): string {
  return `{"code":"${code}"${currentRevision ? `,"currentRevision":${currentRevision}` : ''}}`;
}

async function invalidSaveProblem(
  session: StructuredEditorSession,
  confirmationToken: string,
): Promise<string> {
  const canonical = session.workingCopy.canonicalDesignDsl;
  const bytes = new TextEncoder().encode('renderweave-design-content/1\0' + canonical);
  const digest = new Uint8Array(await crypto.subtle.digest('SHA-256', bytes));
  const proposedContentHash = `sha256:${Array.from(
    digest,
    (byte) => byte.toString(16).padStart(2, '0'),
  ).join('')}`;
  return JSON.stringify({
    code: 'TEMPLATE_DEPENDENCY_CONFIRMATION_REQUIRED',
    proposedContentHash,
    confirmationToken,
    expiresAt: '2099-01-01T00:00:00Z',
    problems: [{
      code: 'TEMPLATE_USE_FILL_TYPE_MISMATCH',
      category: 'DEPENDENCY',
      severity: 'ERROR',
      canonicalPointer: '/designRoot/children/0/fills/0/source',
      messageArgs: [],
    }],
    truncated: false,
  });
}
