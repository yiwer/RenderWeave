// @vitest-environment happy-dom

import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { createSessionFromBaseline, type StructuredEditorSession } from './template-editor-model';
import { applyTemplateDisplayName } from './template-editor-session';
import { structuredBaseline } from './template-editor-test-support';
import {
  buildTemplateRecoveryRecord,
  persistTemplateRecovery,
  type TemplateRecoveryStorage,
} from './template-recovery';
import { TemplateEditorShell } from './TemplateEditorShell';
import type { TemplateSaveTransport } from './template-save';

const NOW = Date.parse('2026-08-21T00:00:01.000Z');
const EDIT_STATE = {
  entry: 'assets' as const,
  selectedNodeId: 'rect-id',
  navigatorOpen: false,
  inspectorOpen: true,
};

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe('Template Editor E7 recovery UI', () => {
  it('offers restore/export/discard without automatically adopting a normal recovery draft', async () => {
    const storage = new MemoryStorage();
    const draft = dirtySessionAt('7', '待恢复草稿');
    persistTemplateRecovery(
      storage,
      await buildTemplateRecoveryRecord(
        draft, EDIT_STATE, '2026-08-21T00:00:00.000Z',
      ),
    );

    render(<TemplateEditorShell
      session={cleanSessionAt('7')}
      recoveryStorage={storage}
      recoveryNow={() => NOW}
    />);

    expect(await screen.findByRole('heading', { name: '发现此设备上的本地恢复草稿' })).toBeTruthy();
    expect(screen.getByRole('heading', { level: 1, name: '门店价签' })).toBeTruthy();
    expect(screen.queryByRole('heading', { level: 1, name: '待恢复草稿' })).toBeNull();
    expect(screen.getByRole('button', { name: '恢复本地草稿' })).toBeTruthy();
    expect(screen.getByRole('button', { name: '导出本地恢复草稿' })).toBeTruthy();
    expect(screen.getByRole('button', { name: '放弃本地恢复草稿' })).toBeTruthy();
    expect(screen.getByRole('textbox', { name: 'Template 名称' }).hasAttribute('disabled')).toBe(true);

    fireEvent.click(screen.getByRole('button', { name: '恢复本地草稿' }));
    expect(screen.getByRole('heading', { level: 1, name: '待恢复草稿' })).toBeTruthy();
    expect(screen.getByText('已恢复此设备上的本地草稿')).toBeTruthy();
    expect(screen.getByRole('button', { name: '放弃已恢复草稿' })).toBeTruthy();
  });

  it('shows baseline drift before restore and requires the existing overwrite confirmation before any PUT', async () => {
    const storage = new MemoryStorage();
    const oldDraft = dirtySessionAt('7', '旧基线草稿');
    persistTemplateRecovery(
      storage,
      await buildTemplateRecoveryRecord(
        oldDraft, EDIT_STATE, '2026-08-21T00:00:00.000Z',
      ),
    );
    const putCurrent = vi.fn();
    const getCurrent = vi.fn();

    render(<TemplateEditorShell
      session={cleanSessionAt('8')}
      saveTransport={saveTransport({ getCurrent, putCurrent })}
      recoveryStorage={storage}
      recoveryNow={() => NOW}
    />);

    expect(await screen.findByText(/本地草稿基于 revision 7.*trusted current 为 revision 8/)).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '确认恢复旧基线草稿' }));
    expect(screen.getByRole('heading', { level: 1, name: '旧基线草稿' })).toBeTruthy();

    const saveButton = screen.getByRole('button', { name: '保存 canonical 本地草稿' });
    await waitFor(() => expect(saveButton.hasAttribute('disabled')).toBe(false));
    fireEvent.click(saveButton);
    expect(await screen.findByText('保存冲突')).toBeTruthy();
    expect(screen.getByRole('button', { name: '重读并覆盖 revision 8' })).toBeTruthy();
    expect(getCurrent).not.toHaveBeenCalled();
    expect(putCurrent).not.toHaveBeenCalled();
  });

  it('explicitly discards the recovery record and keeps the trusted current', async () => {
    const storage = new MemoryStorage();
    persistTemplateRecovery(
      storage,
      await buildTemplateRecoveryRecord(
        dirtySessionAt('7', '不要恢复'), EDIT_STATE, '2026-08-21T00:00:00.000Z',
      ),
    );
    render(<TemplateEditorShell
      session={cleanSessionAt('7')}
      recoveryStorage={storage}
      recoveryNow={() => NOW}
    />);

    fireEvent.click(await screen.findByRole('button', { name: '放弃本地恢复草稿' }));
    expect(screen.queryByRole('heading', { name: '发现此设备上的本地恢复草稿' })).toBeNull();
    expect(screen.getByRole('heading', { level: 1, name: '门店价签' })).toBeTruthy();
    expect(storage.values.size).toBe(0);
  });

  it('replaces the one device record only after the bounded debounce and clears it when undo returns clean', async () => {
    const storage = new MemoryStorage();
    render(<TemplateEditorShell
      session={cleanSessionAt('7')}
      recoveryStorage={storage}
      recoveryNow={() => NOW}
    />);
    const name = screen.getByRole('textbox', { name: 'Template 名称' });
    await waitFor(() => expect(name.hasAttribute('disabled')).toBe(false));

    fireEvent.change(name, { target: { value: 'debounced recovery' } });
    fireEvent.click(screen.getByRole('button', { name: '应用本地名称' }));
    expect(storage.values.size).toBe(0);
    await waitFor(() => expect(storage.onlyValue()).toContain('debounced recovery'), {
      timeout: 1_500,
    });
    expect(storage.values.size).toBe(1);

    fireEvent.click(screen.getByRole('button', { name: '撤销本地编辑' }));
    await waitFor(() => expect(storage.values.size).toBe(0));
  });

  it('uses beforeunload only as a synchronous best-effort flush of the latest validated record', async () => {
    const storage = new MemoryStorage();
    render(<TemplateEditorShell
      session={cleanSessionAt('7')}
      recoveryStorage={storage}
      recoveryNow={() => NOW}
    />);
    const name = screen.getByRole('textbox', { name: 'Template 名称' });
    await waitFor(() => expect(name.hasAttribute('disabled')).toBe(false));
    fireEvent.change(name, { target: { value: 'unload recovery' } });
    fireEvent.click(screen.getByRole('button', { name: '应用本地名称' }));
    await new Promise((resolve) => setTimeout(resolve, 0));

    const event = new Event('beforeunload', { cancelable: true }) as BeforeUnloadEvent;
    window.dispatchEvent(event);
    expect(event.defaultPrevented).toBe(true);
    expect(storage.onlyValue()).toContain('unload recovery');
  });

  it('clears recovery only after a verified save adopts a clean canonical baseline', async () => {
    const storage = new MemoryStorage();
    const draft = dirtySessionAt('7', '保存后清理');
    persistTemplateRecovery(
      storage,
      await buildTemplateRecoveryRecord(
        draft, EDIT_STATE, '2026-08-21T00:00:00.000Z',
      ),
    );
    const putCurrent = vi.fn().mockResolvedValue({
      status: 200,
      body: await saveResponse(draft, '8'),
    });
    render(<TemplateEditorShell
      session={cleanSessionAt('7')}
      saveTransport={saveTransport({ putCurrent })}
      recoveryStorage={storage}
      recoveryNow={() => NOW}
    />);

    fireEvent.click(await screen.findByRole('button', { name: '恢复本地草稿' }));
    fireEvent.click(screen.getByRole('button', { name: '保存 canonical 本地草稿' }));
    await waitFor(() => expect(screen.getByText('revision 8')).toBeTruthy());
    expect(storage.values.size).toBe(0);
  });

  it('makes the exact reconciliation attempt durable before issuing the PUT', async () => {
    const storage = new MemoryStorage();
    const draft = dirtySessionAt('7', '写前冻结 recovery');
    let attemptWasDurableBeforePut = false;
    const putCurrent = vi.fn().mockImplementation(() => {
      attemptWasDurableBeforePut = storage.onlyValue().includes('unknownAttempt');
      return Promise.resolve({
        status: 400,
        body: '{"code":"DESIGN_INVALID"}',
      });
    });
    render(<TemplateEditorShell
      session={draft}
      saveTransport={saveTransport({ putCurrent })}
      recoveryStorage={storage}
      recoveryNow={() => NOW}
    />);

    const save = screen.getByRole('button', { name: '保存 canonical 本地草稿' });
    await waitFor(() => expect(save.hasAttribute('disabled')).toBe(false));
    fireEvent.click(save);
    await waitFor(() => expect(putCurrent).toHaveBeenCalledTimes(1));
    expect(attemptWasDurableBeforePut).toBe(true);
  });

  it('persists an unknown attempt immediately and resumes read-only reconciliation after remount', async () => {
    const storage = new MemoryStorage();
    const draft = dirtySessionAt('7', '刷新后继续核验');
    const firstTransport = saveTransport({
      putCurrent: vi.fn().mockRejectedValue(new TypeError('response lost')),
      getCurrent: vi.fn().mockRejectedValue(new TypeError('offline')),
    });
    const first = render(<TemplateEditorShell
      session={draft}
      saveTransport={firstTransport}
      recoveryStorage={storage}
      recoveryNow={() => NOW}
    />);

    const firstSave = screen.getByRole('button', { name: '保存 canonical 本地草稿' });
    await waitFor(() => expect(firstSave.hasAttribute('disabled')).toBe(false));
    fireEvent.click(firstSave);
    expect(await screen.findByText('保存结果仍未知')).toBeTruthy();
    await waitFor(() => expect(storage.onlyValue()).toContain('unknownAttempt'));
    first.unmount();

    const secondPut = vi.fn();
    const secondGet = vi.fn().mockResolvedValue(currentResponse(draft.baseline));
    render(<TemplateEditorShell
      session={cleanSessionAt('7')}
      saveTransport={saveTransport({ getCurrent: secondGet, putCurrent: secondPut })}
      recoveryStorage={storage}
      recoveryNow={() => NOW + 1}
    />);

    expect(await screen.findByText('可以显式重试')).toBeTruthy();
    expect(screen.getByRole('heading', { level: 1, name: '刷新后继续核验' })).toBeTruthy();
    expect(screen.getByRole('textbox', { name: 'Template 名称' }).hasAttribute('disabled')).toBe(true);
    expect(secondGet).toHaveBeenCalledTimes(1);
    expect(secondPut).not.toHaveBeenCalled();
  });
});

class MemoryStorage implements TemplateRecoveryStorage {
  readonly values = new Map<string, string>();
  getItem(key: string): string | null { return this.values.get(key) ?? null; }
  setItem(key: string, value: string): void { this.values.set(key, value); }
  removeItem(key: string): void { this.values.delete(key); }
  onlyValue(): string {
    const value = [...this.values.values()][0];
    if (value === undefined) throw new Error('expected one recovery value');
    return value;
  }
}

function cleanSessionAt(revision: string): StructuredEditorSession {
  const baseline = structuredBaseline();
  baseline.revision = revision;
  const session = createSessionFromBaseline(baseline, { state: 'checked', value: 'READY' });
  if (session.mode !== 'structured') throw new Error('expected Structured Editor');
  return session;
}

function dirtySessionAt(revision: string, name: string): StructuredEditorSession {
  const result = applyTemplateDisplayName(cleanSessionAt(revision), name);
  if (result.state !== 'applied') throw new Error(`expected applied, got ${result.state}`);
  return result.session;
}

function saveTransport(overrides: Partial<TemplateSaveTransport>): TemplateSaveTransport {
  return {
    getCurrent: vi.fn().mockRejectedValue(new Error('unexpected GET')),
    putCurrent: vi.fn().mockRejectedValue(new Error('unexpected PUT')),
    ...overrides,
  };
}

async function saveResponse(session: StructuredEditorSession, revision: string): Promise<string> {
  const canonical = session.workingCopy.canonicalDesignDsl;
  const bytes = new TextEncoder().encode('renderweave-design-content/1\0' + canonical);
  const digest = new Uint8Array(await crypto.subtle.digest('SHA-256', bytes));
  const hash = `sha256:${Array.from(
    digest, (byte) => byte.toString(16).padStart(2, '0'),
  ).join('')}`;
  return currentResponse({
    ...session.baseline,
    revision,
    contentHash: hash,
    persistedReadiness: 'READY',
    canonicalDesignDsl: canonical,
    designDsl: session.workingCopy.designDsl,
  });
}

function currentResponse(baseline: StructuredEditorSession['baseline']): string {
  return `{"templateId":"${baseline.templateId}","disclosure":"READABLE","revision":${baseline.revision},`
    + `"staticSchema":{"schemaKey":"${baseline.staticSchema.schemaKey}","versionTag":"${baseline.staticSchema.versionTag}"},`
    + `"contentHash":"${baseline.contentHash}","readiness":"${baseline.persistedReadiness}",`
    + `"designDsl":${baseline.canonicalDesignDsl}}`;
}
