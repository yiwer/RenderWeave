// @vitest-environment happy-dom

import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { createSessionFromBaseline, type StructuredEditorSession } from './template-editor-model';
import { applyTemplateDisplayName } from './template-editor-session';
import type {
  TemplatePreviewHttpResponse,
  TemplatePreviewObjectUrlFactory,
  TemplatePreviewTransport,
} from './template-preview';
import { structuredBaseline } from './template-editor-test-support';
import { TemplateEditorShell } from './TemplateEditorShell';
import type { TemplateSaveTransport } from './template-save';

const OPERATION_ID = '123e4567-e89b-42d3-a456-426614174000';
const IMAGE = new Uint8Array([137, 80, 78, 71, 13, 10, 26, 10, 1]);

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe('Template Editor E6 Preview', () => {
  it('keeps the candidate mode visibly non-certified through pending and verified result states', async () => {
    let resolvePreview!: (value: TemplatePreviewHttpResponse) => void;
    const postPreview = vi.fn(() => new Promise<TemplatePreviewHttpResponse>((resolve) => {
      resolvePreview = resolve;
    }));
    render(<TemplateEditorShell
      session={cleanSession()}
      previewTransport={candidateTransport(postPreview)}
      previewObjectUrls={objectUrlFactory()}
    />);

    fireEvent.click(screen.getByRole('button', { name: '打开候选预览（NOT_CERTIFIED）' }));
    expect(screen.getByRole('heading', { name: '候选预览' })).toBeTruthy();
    expect(screen.getByText('NOT_CERTIFIED', { selector: 'strong' })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '生成候选预览（NOT_CERTIFIED）' }));
    expect(await screen.findByText('正在生成 NOT_CERTIFIED 候选预览')).toBeTruthy();

    resolvePreview(await renderedResponse(true));
    const image = await screen.findByRole('img', {
      name: '门店价签的候选预览（NOT_CERTIFIED）',
    });
    expect(image.getAttribute('src')).toBe('blob:preview-1');
    expect(screen.getByText('NOT_CERTIFIED · 完整结果已核验')).toBeTruthy();
  });

  it('identifies candidate errors as non-certified and focuses the recovery summary', async () => {
    render(<TemplateEditorShell
      session={cleanSession()}
      previewTransport={candidateTransport(vi.fn().mockResolvedValue(
        problemResponse('RENDERER_UNAVAILABLE', 'REQUEST_ADMISSION', true),
      ))}
      previewObjectUrls={objectUrlFactory()}
    />);

    fireEvent.click(screen.getByRole('button', { name: '打开候选预览（NOT_CERTIFIED）' }));
    fireEvent.click(screen.getByRole('button', { name: '生成候选预览（NOT_CERTIFIED）' }));

    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toContain('NOT_CERTIFIED 候选预览未生成');
    expect(document.activeElement).toBe(alert);
  });

  it('withdraws a verified result instead of relabelling it when assurance changes', async () => {
    const objectUrls = objectUrlFactory();
    const view = render(<TemplateEditorShell
      session={cleanSession()}
      previewTransport={transport(vi.fn().mockResolvedValue(await renderedResponse()))}
      previewObjectUrls={objectUrls}
    />);
    fireEvent.click(screen.getByRole('button', { name: '打开权威预览' }));
    fireEvent.click(screen.getByRole('button', { name: '生成权威预览' }));
    await screen.findByRole('img', { name: '门店价签的权威预览' });

    view.rerender(<TemplateEditorShell
      session={cleanSession()}
      previewTransport={candidateTransport(vi.fn())}
      previewObjectUrls={objectUrls}
    />);

    expect(screen.queryByRole('img')).toBeNull();
    expect(screen.getByText('预览 assurance 已变化；旧图片已撤下，请重新生成。'))
      .toBeTruthy();
    expect(objectUrls.revoke).toHaveBeenCalledWith('blob:preview-1');
  });

  it('renders a complete verified image and withdraws it when a basis parameter changes', async () => {
    const previewTransport = transport(vi.fn().mockResolvedValue(await renderedResponse()));
    const objectUrls = objectUrlFactory();
    render(<TemplateEditorShell
      session={cleanSession()}
      previewTransport={previewTransport}
      previewObjectUrls={objectUrls}
    />);

    const openPreview = screen.getByRole('button', { name: '打开权威预览' });
    expect(openPreview.hasAttribute('aria-controls')).toBe(false);
    fireEvent.click(openPreview);
    expect(document.querySelector(
      'button[aria-controls="template-authoritative-preview-panel"]',
    )?.getAttribute('aria-label')).toBe('关闭权威预览');
    expect(document.getElementById('template-authoritative-preview-panel')).toBeTruthy();
    expect(screen.getByRole('heading', { name: '权威预览' })).toBeTruthy();
    expect((screen.getByRole('textbox', { name: 'RenderInput JSON' }) as HTMLTextAreaElement).value)
      .toBe('{"rootDocument":{}}');

    fireEvent.click(screen.getByRole('button', { name: '生成权威预览' }));
    const image = await screen.findByRole('img', { name: '门店价签的权威预览' });
    expect(image.getAttribute('src')).toBe('blob:preview-1');
    expect(screen.getByText('794 × 1123 px')).toBeTruthy();
    expect(screen.getByText('renderweave-renderer/test-certified')).toBeTruthy();
    expect(previewTransport.postPreview).toHaveBeenCalledWith(
      structuredBaseline().templateId,
      { inputJson: '{"rootDocument":{}}', format: 'PNG', dpi: 96 },
      expect.any(AbortSignal),
    );

    fireEvent.change(screen.getByRole('spinbutton', { name: 'DPI' }), { target: { value: '144' } });
    expect(screen.queryByRole('img', { name: /权威预览/ })).toBeNull();
    expect(objectUrls.revoke).toHaveBeenCalledWith('blob:preview-1');
    expect(screen.getByText(/输出参数已变化/)).toBeTruthy();

    fireEvent.change(screen.getByRole('spinbutton', { name: 'DPI' }), { target: { value: '96' } });
    fireEvent.click(screen.getByRole('button', { name: '生成权威预览' }));
    await screen.findByRole('img', { name: /权威预览/ });
    fireEvent.change(screen.getByRole('textbox', { name: 'RenderInput JSON' }), {
      target: { value: '{"rootDocument":{"store":"north"}}' },
    });
    expect(screen.queryByRole('img', { name: /权威预览/ })).toBeNull();
    expect(objectUrls.revoke).toHaveBeenCalledTimes(2);
    expect(screen.getByText(/输入样例已变化/)).toBeTruthy();
  });

  it('withdraws old success before a failed request and focuses the preview problem summary', async () => {
    const postPreview = vi.fn()
      .mockResolvedValueOnce(await renderedResponse())
      .mockResolvedValueOnce(problemResponse('RENDERER_UNAVAILABLE', 'ENGINE'));
    const objectUrls = objectUrlFactory();
    render(<TemplateEditorShell
      session={cleanSession()}
      previewTransport={transport(postPreview)}
      previewObjectUrls={objectUrls}
    />);

    fireEvent.click(screen.getByRole('button', { name: '打开权威预览' }));
    fireEvent.click(screen.getByRole('button', { name: '生成权威预览' }));
    await screen.findByRole('img', { name: /权威预览/ });
    fireEvent.click(screen.getByRole('button', { name: '生成权威预览' }));
    expect(screen.queryByRole('img', { name: /权威预览/ })).toBeNull();
    expect(objectUrls.revoke).toHaveBeenCalledWith('blob:preview-1');

    const alert = await screen.findByRole('alert');
    await waitFor(() => expect(document.activeElement).toBe(alert));
    expect(alert.textContent).toContain('RENDERER_UNAVAILABLE');
    expect(alert.textContent).toContain('ENGINE');
    expect(alert.textContent).toContain(OPERATION_ID);
  });

  it('runs dirty save-and-preview sequentially and reports preview failure separately', async () => {
    const session = dirtySession('已保存但预览失败');
    let resolveSave!: (value: { status: number; body: string }) => void;
    const putCurrent = vi.fn().mockReturnValue(new Promise((resolve) => {
      resolveSave = resolve;
    }));
    const postPreview = vi.fn().mockResolvedValue(problemResponse('RENDERER_UNAVAILABLE', 'ENGINE'));
    render(<TemplateEditorShell
      session={session}
      saveTransport={saveTransport({ putCurrent })}
      previewTransport={transport(postPreview)}
    />);

    fireEvent.click(screen.getByRole('button', { name: '打开权威预览' }));
    fireEvent.click(screen.getByRole('button', { name: '保存并生成权威预览' }));
    await waitFor(() => expect(putCurrent).toHaveBeenCalledTimes(1));
    expect(postPreview).not.toHaveBeenCalled();

    resolveSave({ status: 200, body: await saveResponse(session, '8') });
    await waitFor(() => expect(postPreview).toHaveBeenCalledTimes(1));
    expect(screen.getByText('revision 8')).toBeTruthy();
    expect(screen.getByText(/revision 8 已保存/)).toBeTruthy();
    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toContain('RENDERER_UNAVAILABLE');
    expect(alert.textContent).toContain('保存不会回滚');
  });

  it('retains save-and-preview intent through unknown reconciliation and an explicit exact retry', async () => {
    const session = dirtySession('未知结果后预览');
    const putCurrent = vi.fn()
      .mockRejectedValueOnce(new TypeError('response lost'))
      .mockResolvedValueOnce({ status: 200, body: await saveResponse(session, '8') });
    const getCurrent = vi.fn().mockResolvedValue(await saveResponse(
      session,
      '7',
      'READY',
      session.baseline.canonicalDesignDsl,
    ));
    const postPreview = vi.fn().mockResolvedValue(problemResponse('RENDERER_UNAVAILABLE', 'ENGINE'));
    render(<TemplateEditorShell
      session={session}
      saveTransport={saveTransport({ putCurrent, getCurrent })}
      previewTransport={transport(postPreview)}
    />);

    fireEvent.click(screen.getByRole('button', { name: '打开权威预览' }));
    fireEvent.click(screen.getByRole('button', { name: '保存并生成权威预览' }));
    expect(await screen.findByText('可以显式重试')).toBeTruthy();
    expect(postPreview).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole('button', { name: '显式重试原保存' }));
    await waitFor(() => expect(postPreview).toHaveBeenCalledTimes(1));
    expect(screen.getByText('revision 8')).toBeTruthy();
    expect((await screen.findByRole('alert')).textContent).toContain('保存不会回滚');
  });

  it('carries a save-and-preview intent through explicit conflict overwrite', async () => {
    const session = dirtySession('冲突后预览');
    const remoteBaseline = structuredBaseline();
    remoteBaseline.revision = '8';
    const remote = createSessionFromBaseline(
      remoteBaseline,
      { state: 'checked', value: 'READY' },
    );
    if (remote.mode !== 'structured') throw new Error('expected structured');
    const putCurrent = vi.fn()
      .mockResolvedValueOnce({ status: 409, body: '{"code":"TEMPLATE_REVISION_CONFLICT","currentRevision":8}' })
      .mockResolvedValueOnce({ status: 200, body: await saveResponse(session, '9') });
    const postPreview = vi.fn().mockResolvedValue(problemResponse('RENDERER_UNAVAILABLE', 'ENGINE'));
    render(<TemplateEditorShell
      session={session}
      saveTransport={saveTransport({
        putCurrent,
        getCurrent: vi.fn().mockResolvedValue(await saveResponse(remote, '8', 'READY', remote.baseline.canonicalDesignDsl)),
      })}
      previewTransport={transport(postPreview)}
    />);

    fireEvent.click(screen.getByRole('button', { name: '打开权威预览' }));
    fireEvent.click(screen.getByRole('button', { name: '保存并生成权威预览' }));
    const overwrite = await screen.findByRole('button', { name: '重读并覆盖 revision 8' });
    expect(postPreview).not.toHaveBeenCalled();
    fireEvent.click(overwrite);
    await waitFor(() => expect(postPreview).toHaveBeenCalledTimes(1));
    expect(screen.getByText('revision 9')).toBeTruthy();
  });

  it('does not preview a confirmed INVALID commit', async () => {
    const session = dirtySession('仍保存为 INVALID');
    const token = 'a'.repeat(64);
    const putCurrent = vi.fn()
      .mockResolvedValueOnce({ status: 422, body: await invalidSaveProblem(session, token) })
      .mockResolvedValueOnce({ status: 200, body: await saveResponse(session, '8', 'INVALID') });
    const postPreview = vi.fn();
    render(<TemplateEditorShell
      session={session}
      saveTransport={saveTransport({ putCurrent })}
      previewTransport={transport(postPreview)}
    />);

    fireEvent.click(screen.getByRole('button', { name: '打开权威预览' }));
    fireEvent.click(screen.getByRole('button', { name: '保存并生成权威预览' }));
    fireEvent.click(await screen.findByRole('button', { name: '仍保存为 INVALID' }));
    await waitFor(() => expect(screen.getByText('revision 8')).toBeTruthy());

    expect(postPreview).not.toHaveBeenCalled();
    const alert = screen.getByRole('alert');
    expect(alert.textContent).toContain('已保存为 INVALID');
    expect(alert.textContent).toContain('未启动权威预览');
  });

  it('stops waiting locally, revokes display eligibility, and discards a late success', async () => {
    let resolvePreview!: (value: TemplatePreviewHttpResponse) => void;
    let capturedSignal: AbortSignal | undefined;
    const postPreview = vi.fn((
      _templateId: string,
      _request: unknown,
      signal?: AbortSignal,
    ) => {
      capturedSignal = signal;
      return new Promise<TemplatePreviewHttpResponse>((resolve) => {
        resolvePreview = resolve;
      });
    });
    const objectUrls = objectUrlFactory();
    render(<TemplateEditorShell
      session={cleanSession()}
      previewTransport={transport(postPreview)}
      previewObjectUrls={objectUrls}
    />);

    fireEvent.click(screen.getByRole('button', { name: '打开权威预览' }));
    fireEvent.click(screen.getByRole('button', { name: '生成权威预览' }));
    fireEvent.click(await screen.findByRole('button', { name: '停止等待' }));
    expect(capturedSignal?.aborted).toBe(true);
    expect(screen.getByText(/服务端 operation 可能继续/)).toBeTruthy();

    resolvePreview(await renderedResponse());
    await Promise.resolve();
    expect(screen.queryByRole('img', { name: /权威预览/ })).toBeNull();
    expect(objectUrls.create).not.toHaveBeenCalled();
  });

  it('withdraws a result on authored edit and does not expose preview in non-structured modes', async () => {
    const objectUrls = objectUrlFactory();
    const view = render(<TemplateEditorShell
      session={cleanSession()}
      previewTransport={transport(vi.fn().mockResolvedValue(await renderedResponse()))}
      previewObjectUrls={objectUrls}
    />);
    fireEvent.click(screen.getByRole('button', { name: '打开权威预览' }));
    fireEvent.click(screen.getByRole('button', { name: '生成权威预览' }));
    await screen.findByRole('img', { name: /权威预览/ });

    fireEvent.change(screen.getByRole('textbox', { name: 'Template 名称' }), {
      target: { value: '编辑后撤图' },
    });
    fireEvent.click(screen.getByRole('button', { name: '应用本地名称' }));
    expect(screen.queryByRole('img', { name: /权威预览/ })).toBeNull();
    expect(objectUrls.revoke).toHaveBeenCalledWith('blob:preview-1');

    const compatibilityBaseline = structuredBaseline();
    compatibilityBaseline.designDsl.dslVersion = 'renderweave-design/2.0';
    view.rerender(<TemplateEditorShell
      session={createSessionFromBaseline(compatibilityBaseline, { state: 'checked', value: 'READY' })}
      previewTransport={transport(vi.fn())}
    />);
    expect(screen.queryByRole('button', { name: /权威预览/ })).toBeNull();
  });

  it('withdraws a rendered result when trusted readiness changes outside the panel', async () => {
    const baseline = structuredBaseline();
    const ready = createSessionFromBaseline(baseline, { state: 'checked', value: 'READY' });
    const unavailable = createSessionFromBaseline(baseline, {
      state: 'unavailable',
      message: 'trusted readiness 已失效',
    });
    if (ready.mode !== 'structured' || unavailable.mode !== 'structured') {
      throw new Error('expected structured');
    }
    const objectUrls = objectUrlFactory();
    const previewTransport = transport(vi.fn().mockResolvedValue(await renderedResponse()));
    const view = render(<TemplateEditorShell
      session={ready}
      previewTransport={previewTransport}
      previewObjectUrls={objectUrls}
    />);
    fireEvent.click(screen.getByRole('button', { name: '打开权威预览' }));
    fireEvent.click(screen.getByRole('button', { name: '生成权威预览' }));
    await screen.findByRole('img', { name: /权威预览/ });

    view.rerender(<TemplateEditorShell
      session={unavailable}
      previewTransport={previewTransport}
      previewObjectUrls={objectUrls}
    />);

    await waitFor(() => expect(screen.queryByRole('img', { name: /权威预览/ })).toBeNull());
    expect(objectUrls.revoke).toHaveBeenCalledWith('blob:preview-1');
    expect(screen.getByText(/readiness.*已变化/)).toBeTruthy();
  });

  it('never writes the local RenderInput sample into Local recovery', async () => {
    const storage = {
      getItem: vi.fn().mockReturnValue(null),
      setItem: vi.fn(),
      removeItem: vi.fn(),
    };
    render(<TemplateEditorShell
      session={cleanSession()}
      previewTransport={transport(vi.fn())}
      recoveryStorage={storage}
    />);
    await waitFor(() => expect(storage.getItem).toHaveBeenCalled());
    fireEvent.click(screen.getByRole('button', { name: '打开权威预览' }));
    fireEvent.change(screen.getByRole('textbox', { name: 'RenderInput JSON' }), {
      target: { value: '{"rootDocument":{"privateSample":"must-not-persist"}}' },
    });
    fireEvent.change(screen.getByRole('textbox', { name: 'Template 名称' }), {
      target: { value: '触发 recovery 写入' },
    });
    fireEvent.click(screen.getByRole('button', { name: '应用本地名称' }));

    await waitFor(() => expect(storage.setItem).toHaveBeenCalled());
    for (const call of storage.setItem.mock.calls) {
      expect(call[1]).not.toContain('privateSample');
      expect(call[1]).not.toContain('must-not-persist');
    }
  });
});

function transport(postPreview: TemplatePreviewTransport['postPreview']): TemplatePreviewTransport {
  return { postPreview };
}

function candidateTransport(
  postPreview: TemplatePreviewTransport['postPreview'],
): TemplatePreviewTransport {
  return { assurance: 'candidate', postPreview };
}

function objectUrlFactory(): TemplatePreviewObjectUrlFactory {
  return {
    create: vi.fn().mockReturnValue('blob:preview-1'),
    revoke: vi.fn(),
  };
}

function cleanSession(): StructuredEditorSession {
  const session = createSessionFromBaseline(
    structuredBaseline(),
    { state: 'checked', value: 'READY' },
  );
  if (session.mode !== 'structured') throw new Error('expected structured');
  return session;
}

function dirtySession(name: string): StructuredEditorSession {
  const result = applyTemplateDisplayName(cleanSession(), name);
  if (result.state !== 'applied') throw new Error('expected applied');
  return result.session;
}

function saveTransport(overrides: Partial<TemplateSaveTransport>): TemplateSaveTransport {
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
  const bytes = new TextEncoder().encode(`renderweave-design-content/1\0${canonical}`);
  const digest = new Uint8Array(await crypto.subtle.digest('SHA-256', bytes));
  const hash = `sha256:${Array.from(digest, (byte) => byte.toString(16).padStart(2, '0')).join('')}`;
  const { templateId, staticSchema } = session.baseline;
  return `{"templateId":"${templateId}","disclosure":"READABLE","revision":${revision},`
    + `"staticSchema":{"schemaKey":"${staticSchema.schemaKey}","versionTag":"${staticSchema.versionTag}"},`
    + `"contentHash":"${hash}","readiness":"${readiness}","designDsl":${canonical}}`;
}

async function invalidSaveProblem(session: StructuredEditorSession, confirmationToken: string) {
  const bytes = new TextEncoder().encode(
    `renderweave-design-content/1\0${session.workingCopy.canonicalDesignDsl}`,
  );
  const digest = new Uint8Array(await crypto.subtle.digest('SHA-256', bytes));
  return JSON.stringify({
    code: 'TEMPLATE_DEPENDENCY_CONFIRMATION_REQUIRED',
    proposedContentHash: `sha256:${Array.from(digest, (byte) => byte.toString(16).padStart(2, '0')).join('')}`,
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

function problemResponse(
  code: string,
  stage: string,
  candidate = false,
): TemplatePreviewHttpResponse {
  return {
    status: 503,
    headers: new Headers({
      'Content-Type': 'application/vnd.renderweave.render-problem+json;version=1.0',
      ...(candidate ? { 'RenderWeave-Candidate-Status': 'NOT_CERTIFIED' } : {}),
    }),
    body: new TextEncoder().encode(JSON.stringify({
      contractVersion: 'renderweave-render-problem/1.0',
      renderOperationId: OPERATION_ID,
      code,
      stage,
      parameters: {},
    })),
  };
}

async function renderedResponse(candidate = false): Promise<TemplatePreviewHttpResponse> {
  const digest = new Uint8Array(await crypto.subtle.digest('SHA-256', IMAGE));
  return {
    status: 200,
    headers: new Headers({
      'Content-Type': 'image/png',
      'Content-Length': String(IMAGE.byteLength),
      'Content-Digest': `sha-256=:${btoa(String.fromCharCode(...digest))}:`,
      'RenderWeave-Result-Version': 'renderweave-render-result/1.0',
      'RenderWeave-Request-Id': OPERATION_ID,
      'RenderWeave-Renderer-Profile': 'renderweave-renderer/test-certified',
      'RenderWeave-DSL-Version': 'renderweave-render/1.0',
      'RenderWeave-Layout-Profile': 'renderweave-layout/1.0',
      'RenderWeave-Output-Profile': 'renderweave-output-png/1.0',
      'RenderWeave-Format': 'PNG',
      'RenderWeave-Width-Px': '794',
      'RenderWeave-Height-Px': '1123',
      'RenderWeave-DPI': '96',
      ...(candidate ? { 'RenderWeave-Candidate-Status': 'NOT_CERTIFIED' } : {}),
    }),
    body: IMAGE,
  };
}
