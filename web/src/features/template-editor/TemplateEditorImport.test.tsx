// @vitest-environment happy-dom

import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { createSessionFromBaseline, type StructuredEditorSession } from './template-editor-model';
import { applyTemplateDisplayName } from './template-editor-session';
import {
  TemplateEditorShell,
  type TemplateEditorDownload,
  type TemplateEditorDownloadArtifact,
} from './TemplateEditorShell';
import type { TemplateSaveTransport } from './template-save';
import { structuredBaseline } from './template-editor-test-support';
import {
  buildTemplateRecoveryRecord,
  persistTemplateRecovery,
  type TemplateRecoveryStorage,
} from './template-recovery';

const encoder = new TextEncoder();
const decoder = new TextDecoder();

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe('Template Editor E8 import modes and replacement guard', () => {
  it('inspects without mutation, then adopts a clean Structured import locally without PUT or foreign identity adoption', async () => {
    const putCurrent = vi.fn();
    const onSessionCommitted = vi.fn();
    render(<TemplateEditorShell
      session={cleanSession()}
      saveTransport={saveTransport({ putCurrent })}
      onSessionCommitted={onSessionCommitted}
    />);

    await inspectFile(importedDesign('导入价签'));

    expect(await screen.findByText('导入检查通过')).toBeTruthy();
    expect(screen.getByRole('heading', { level: 1, name: '门店价签' })).toBeTruthy();
    expect(screen.getByText('目标仍为当前 Template 与 StaticSchema')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '接受导入为本地草稿' }));

    expect(await screen.findByRole('heading', { level: 1, name: '导入价签' })).toBeTruthy();
    expect(screen.getByText('revision 7')).toBeTruthy();
    expect(screen.getByText('system-empty@v1')).toBeTruthy();
    expect(screen.getByText('Canonical 本地草稿')).toBeTruthy();
    expect(screen.getByRole('button', { name: '撤销本地编辑' }).hasAttribute('disabled')).toBe(true);
    expect(putCurrent).not.toHaveBeenCalled();
    expect(onSessionCommitted).not.toHaveBeenCalled();
  });

  it('keeps a same-working-copy import as an explicit no-op', async () => {
    render(<TemplateEditorShell session={cleanSession()} />);

    await inspectFile(structuredBaseline().canonicalDesignDsl);
    fireEvent.click(await screen.findByRole('button', { name: '接受导入为本地草稿' }));

    expect(await screen.findByText('导入内容与当前工作副本相同；未改变本地状态。')).toBeTruthy();
    expect(screen.getByText('Canonical current')).toBeTruthy();
    expect(screen.getByRole('button', { name: '撤销本地编辑' }).hasAttribute('disabled')).toBe(true);
  });

  it('does not clear history or Local recovery for a dirty same-working-copy no-op', async () => {
    const storage = new MemoryStorage();
    const session = dirtySession('仍需恢复的草稿');
    render(<TemplateEditorShell session={session} recoveryStorage={storage} />);
    await waitFor(() => expect(
      screen.getByRole('textbox', { name: 'Template 名称' }).hasAttribute('disabled'),
    ).toBe(false));

    await inspectFile(session.workingCopy.canonicalDesignDsl);
    fireEvent.click(await screen.findByRole('button', { name: '接受导入为本地草稿' }));

    expect(await screen.findByText('导入内容与当前工作副本相同；未改变本地状态。')).toBeTruthy();
    expect(screen.getByRole('button', { name: '撤销本地编辑' }).hasAttribute('disabled')).toBe(false);
    expect(storage.removedKeys).toHaveLength(0);
  });

  it('treats revision-export identity and StaticSchema as display-only metadata', async () => {
    render(<TemplateEditorShell session={cleanSession()} />);

    await inspectFile(await importedRevisionExport('外来 revision'));

    expect(await screen.findByText('导入检查通过')).toBeTruthy();
    expect(screen.getByText('文件显示身份：00000000…000099 · revision 99')).toBeTruthy();
    expect(screen.getByText('文件显示 Schema：foreign-schema@foreign-v7')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '接受导入为本地草稿' }));

    expect(await screen.findByRole('heading', { level: 1, name: '外来 revision' })).toBeTruthy();
    expect(screen.getByText('revision 7')).toBeTruthy();
    expect(screen.getByText('system-empty@v1')).toBeTruthy();
  });

  it('requires explicit cancel/export/discard choices before replacing a dirty working copy', async () => {
    const artifacts: TemplateEditorDownloadArtifact[] = [];
    const download: TemplateEditorDownload = (artifact) => artifacts.push(artifact);
    render(<TemplateEditorShell session={dirtySession('必须保留的旧草稿')} download={download} />);

    await inspectFile(importedDesign('替换后的导入草稿'));
    fireEvent.click(await screen.findByRole('button', { name: '接受导入为本地草稿' }));
    expect(screen.getByRole('heading', { name: '替换当前本地草稿？' })).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: '取消导入替换' }));
    expect(screen.getByRole('heading', { level: 1, name: '必须保留的旧草稿' })).toBeTruthy();
    expect(screen.getByText('Canonical 本地草稿')).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: '接受导入为本地草稿' }));
    fireEvent.click(screen.getByRole('button', { name: '导出当前草稿并继续导入' }));

    expect(await screen.findByRole('heading', { level: 1, name: '替换后的导入草稿' })).toBeTruthy();
    expect(artifacts).toHaveLength(1);
    expect(artifacts[0]).toEqual(expect.objectContaining({
      mediaType: 'application/vnd.renderweave.design+json',
    }));
    expect(decoder.decode(artifacts[0]?.bytes)).toContain('必须保留的旧草稿');
  });

  it('can explicitly discard the current dirty draft before adopting the import', async () => {
    render(<TemplateEditorShell session={dirtySession('明确放弃的旧草稿')} />);

    await inspectFile(importedDesign('放弃后导入'));
    fireEvent.click(await screen.findByRole('button', { name: '接受导入为本地草稿' }));
    fireEvent.click(screen.getByRole('button', { name: '放弃当前草稿并继续导入' }));

    expect(await screen.findByRole('heading', { level: 1, name: '放弃后导入' })).toBeTruthy();
    expect(screen.queryByRole('heading', { level: 1, name: '明确放弃的旧草稿' })).toBeNull();
  });

  it('can save the current draft through the real coordinator before continuing the import', async () => {
    const current = dirtySession('先保存的旧草稿');
    const putCurrent = vi.fn().mockResolvedValue({
      status: 200,
      body: await saveResponse(current, '8'),
    });
    render(<TemplateEditorShell
      session={current}
      saveTransport={saveTransport({ putCurrent })}
    />);

    await inspectFile(importedDesign('保存后导入'));
    fireEvent.click(await screen.findByRole('button', { name: '接受导入为本地草稿' }));
    fireEvent.click(screen.getByRole('button', { name: '保存当前草稿后继续导入' }));

    await waitFor(() => expect(putCurrent).toHaveBeenCalledTimes(1));
    expect(await screen.findByRole('heading', { level: 1, name: '保存后导入' })).toBeTruthy();
    expect(screen.getByText('revision 8')).toBeTruthy();
    expect(screen.getByText('Canonical 本地草稿')).toBeTruthy();
  });

  it('uses editable Raw Repair for malformed UTF-8 text and re-inspects repaired bytes before adoption', async () => {
    const artifacts: TemplateEditorDownloadArtifact[] = [];
    render(<TemplateEditorShell
      session={cleanSession()}
      download={(artifact) => artifacts.push(artifact)}
    />);

    await inspectFile('{"dslVersion":');

    expect(await screen.findByRole('heading', { name: 'Raw Repair' })).toBeTruthy();
    expect(screen.queryByRole('tree')).toBeNull();
    expect(screen.queryByRole('button', { name: '保存 canonical 本地草稿' })).toBeNull();
    const repair = screen.getByRole('textbox', { name: 'Raw Repair 文本' });
    const repaired = importedDesign('修复后导入');
    fireEvent.change(repair, { target: { value: repaired } });
    fireEvent.click(screen.getByRole('button', { name: '下载当前修复稿' }));
    expect(decoder.decode(artifacts[0]!.bytes)).toBe(repaired);
    expect(artifacts[0]!.mediaType).toBe('application/vnd.renderweave.design+json');
    fireEvent.click(screen.getByRole('button', { name: '重新检查修复文本' }));

    expect(await screen.findByText('导入检查通过')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '接受导入为本地草稿' }));
    expect(await screen.findByRole('heading', { level: 1, name: '修复后导入' })).toBeTruthy();
  });

  it('keeps invalid UTF-8 as byte-only Raw Repair and downloads the exact original bytes', async () => {
    const artifacts: TemplateEditorDownloadArtifact[] = [];
    render(<TemplateEditorShell
      session={cleanSession()}
      download={(artifact) => artifacts.push(artifact)}
    />);

    await inspectFile(new Uint8Array([0xc3, 0x28]));

    expect(await screen.findByRole('heading', { name: 'Raw Repair' })).toBeTruthy();
    expect(screen.queryByRole('textbox', { name: 'Raw Repair 文本' })).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: '下载原始导入字节' }));
    expect([...artifacts[0]!.bytes]).toEqual([0xc3, 0x28]);
    fireEvent.click(screen.getByRole('button', { name: '丢弃导入并返回 Structured' }));
    expect(screen.getByRole('heading', { level: 1, name: '门店价签' })).toBeTruthy();
    expect(screen.getByText('Structured Editor')).toBeTruthy();
  });

  it('keeps unknown exact-pair wire in Compatibility with exact export and no fake migration action', async () => {
    const artifacts: TemplateEditorDownloadArtifact[] = [];
    const unknown = JSON.parse(importedDesign('未来 wire')) as Record<string, unknown>;
    (unknown.designRoot as Record<string, unknown>).futureMember = { opaque: true };
    const raw = JSON.stringify(unknown);
    render(<TemplateEditorShell
      session={cleanSession()}
      download={(artifact) => artifacts.push(artifact)}
    />);

    await inspectFile(raw);

    expect(await screen.findByRole('heading', { name: 'Compatibility Read-only' })).toBeTruthy();
    expect(screen.getByText(/没有已注册的 migration profile/)).toBeTruthy();
    expect(screen.queryByRole('button', { name: /migration|迁移/i })).toBeNull();
    expect(screen.queryByRole('tree')).toBeNull();
    expect(screen.queryByRole('button', { name: '保存 canonical 本地草稿' })).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: '原样导出兼容文件' }));
    expect(decoder.decode(artifacts[0]!.bytes)).toBe(raw);
    expect(screen.getByText('revision 7')).toBeTruthy();
  });

  it('invalidates an inspected candidate when the local generation changes before acceptance', async () => {
    render(<TemplateEditorShell session={cleanSession()} />);

    await inspectFile(importedDesign('会失效的候选'));
    expect(await screen.findByText('导入检查通过')).toBeTruthy();
    fireEvent.change(screen.getByRole('textbox', { name: 'Template 名称' }), {
      target: { value: '候选检查后的编辑' },
    });
    fireEvent.click(screen.getByRole('button', { name: '应用本地名称' }));

    expect(await screen.findByText('导入候选已因本地 generation 变化而失效。')).toBeTruthy();
    expect(screen.queryByRole('button', { name: '接受导入为本地草稿' })).toBeNull();
    expect(screen.getByRole('heading', { level: 1, name: '候选检查后的编辑' })).toBeTruthy();
  });

  it('disables file inspection while a save outcome is unresolved', async () => {
    let settle!: (value: { status: number; body: string }) => void;
    const putCurrent = vi.fn().mockReturnValue(new Promise((resolve) => { settle = resolve; }));
    const session = dirtySession('写入中的草稿');
    render(<TemplateEditorShell
      session={session}
      saveTransport={saveTransport({ putCurrent })}
    />);

    fireEvent.click(screen.getByRole('button', { name: '保存 canonical 本地草稿' }));
    await waitFor(() => expect(putCurrent).toHaveBeenCalledTimes(1));
    fireEvent.click(screen.getByRole('button', { name: '交换' }));
    expect(screen.getByLabelText('选择本地 Template 文件').hasAttribute('disabled')).toBe(true);

    settle({ status: 200, body: await saveResponse(session, '8') });
  });

  it('keeps file inspection locked when an ambiguous save cannot be reconciled', async () => {
    render(<TemplateEditorShell
      session={dirtySession('结果未知的草稿')}
      saveTransport={saveTransport({
        putCurrent: vi.fn().mockRejectedValue(new TypeError('response lost')),
        getCurrent: vi.fn().mockRejectedValue(new TypeError('offline')),
      })}
    />);

    fireEvent.click(screen.getByRole('button', { name: '保存 canonical 本地草稿' }));
    expect(await screen.findByText('保存结果仍未知')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '交换' }));

    expect(screen.getByLabelText('选择本地 Template 文件').hasAttribute('disabled')).toBe(true);
  });

  it('locks file inspection until a pending Local recovery choice is resolved', async () => {
    const storage = new MemoryStorage();
    persistTemplateRecovery(
      storage,
      await buildTemplateRecoveryRecord(
        dirtySession('待处理的恢复草稿'),
        {
          entry: 'structure',
          selectedNodeId: '',
          navigatorOpen: true,
          inspectorOpen: true,
        },
        '2026-08-21T00:00:00.000Z',
      ),
    );
    render(<TemplateEditorShell
      session={cleanSession()}
      recoveryStorage={storage}
      recoveryNow={() => Date.parse('2026-08-21T00:00:01.000Z')}
    />);

    expect(await screen.findByRole('heading', { name: '发现此设备上的本地恢复草稿' })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '交换' }));

    expect(screen.getByLabelText('选择本地 Template 文件').hasAttribute('disabled')).toBe(true);
    expect(screen.getByRole('heading', { level: 1, name: '门店价签' })).toBeTruthy();
  });
});

async function inspectFile(contents: string | Uint8Array) {
  fireEvent.click(screen.getByRole('button', { name: '交换' }));
  const bytes = typeof contents === 'string' ? encoder.encode(contents) : contents;
  const file = new File([copyArrayBuffer(bytes)], 'import.design.json', {
    type: 'application/vnd.renderweave.design+json',
  });
  fireEvent.change(screen.getByLabelText('选择本地 Template 文件'), {
    target: { files: [file] },
  });
  await waitFor(() => expect(screen.queryByText('正在严格检查本地文件…')).toBeNull());
}

function copyArrayBuffer(bytes: Uint8Array): ArrayBuffer {
  const buffer = new ArrayBuffer(bytes.byteLength);
  new Uint8Array(buffer).set(bytes);
  return buffer;
}

function importedDesign(name: string): string {
  return structuredBaseline().canonicalDesignDsl.replace(
    '"displayName":"门店价签"',
    `"displayName":"${name}"`,
  );
}

async function importedRevisionExport(name: string): Promise<string> {
  const canonical = importedDesign(name);
  const digest = new Uint8Array(await crypto.subtle.digest(
    'SHA-256',
    encoder.encode('renderweave-design-content/1\0' + canonical),
  ));
  const contentHash = `sha256:${Array.from(
    digest,
    (byte) => byte.toString(16).padStart(2, '0'),
  ).join('')}`;
  return '{"exportVersion":"renderweave-template-revision-export/1.0",'
    + '"identity":{"kind":"templateRevision","templateId":"00000000-0000-4000-8000-000000000099","revision":99},'
    + '"staticSchemaRef":{"schemaKey":"foreign-schema","versionTag":"foreign-v7"},'
    + `"contentHash":"${contentHash}","designDsl":${canonical}}`;
}

function cleanSession(): StructuredEditorSession {
  const session = createSessionFromBaseline(
    structuredBaseline(),
    { state: 'checked', value: 'READY' },
  );
  if (session.mode !== 'structured') throw new Error('expected Structured Editor');
  return session;
}

function dirtySession(name: string): StructuredEditorSession {
  const result = applyTemplateDisplayName(cleanSession(), name);
  if (result.state !== 'applied') throw new Error(`expected applied, got ${result.state}`);
  return result.session;
}

class MemoryStorage implements TemplateRecoveryStorage {
  private readonly values = new Map<string, string>();
  readonly removedKeys: string[] = [];
  getItem(key: string): string | null { return this.values.get(key) ?? null; }
  setItem(key: string, value: string): void { this.values.set(key, value); }
  removeItem(key: string): void {
    this.removedKeys.push(key);
    this.values.delete(key);
  }
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
  const digest = new Uint8Array(await crypto.subtle.digest(
    'SHA-256',
    encoder.encode('renderweave-design-content/1\0' + canonical),
  ));
  const hash = `sha256:${Array.from(
    digest,
    (byte) => byte.toString(16).padStart(2, '0'),
  ).join('')}`;
  const { templateId, staticSchema } = session.baseline;
  return `{"templateId":"${templateId}","disclosure":"READABLE","revision":${revision},`
    + `"staticSchema":{"schemaKey":"${staticSchema.schemaKey}","versionTag":"${staticSchema.versionTag}"},`
    + `"contentHash":"${hash}","readiness":"READY","designDsl":${canonical}}`;
}
