// @vitest-environment happy-dom

import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import {
  createSessionFromBaseline,
  type CanonicalTemplateBaseline,
  type StructuredEditorSession,
} from './template-editor-model';
import { applyTemplateDisplayName } from './template-editor-session';
import { structuredBaseline } from './template-editor-test-support';
import { TemplateEditorShell } from './TemplateEditorShell';
import type { TemplateSaveTransport } from './template-save';

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe('Template Editor E9 accessibility flow', () => {
  it('focuses the invalid summary and locates only strict, stable UI targets', async () => {
    const session = dirtySession(structuredBaseline(), '需定位的草稿');
    const transport = saveTransport(await invalidProblemResponse(session, [
      ['/displayName', 'TEMPLATE_DISPLAY_NAME_INVALID'],
      ['/designRoot/children/0/fills/0/source', 'TEMPLATE_USE_FILL_TYPE_MISMATCH'],
      ['/definitions/0/source', 'DEFINITION_SOURCE_INVALID'],
      ['/unsupported/value', 'UNSUPPORTED_POINTER'],
    ]));
    render(<TemplateEditorShell session={session} saveTransport={transport} />);

    fireEvent.click(screen.getByRole('button', { name: '保存 canonical 本地草稿' }));
    const heading = await screen.findByRole('heading', { name: '确认仍保存为 INVALID' });
    const summary = heading.closest('section');
    expect(summary).not.toBeNull();
    await waitFor(() => expect(document.activeElement).toBe(summary));
    expect(summary?.getAttribute('tabindex')).toBe('-1');
    expect(document.querySelector('.te-entry-panel')?.hasAttribute('aria-live')).toBe(false);

    fireEvent.click(screen.getByRole('button', { name: /定位到 Template 名称/ }));
    await waitFor(() => expect(document.activeElement).toBe(
      screen.getByRole('textbox', { name: 'Template 名称' }),
    ));

    fireEvent.click(screen.getByRole('button', { name: /定位到节点“内容区”/ }));
    const frame = await screen.findByRole('treeitem', { name: /内容区/ });
    await waitFor(() => expect(document.activeElement).toBe(frame));
    expect(frame.getAttribute('aria-selected')).toBe('true');
    expect(document.querySelector('[data-template-editor-announcer]')?.textContent)
      .toContain('具体属性没有独立表单控件');

    fireEvent.click(screen.getByRole('button', { name: /定位到定义面板/ }));
    const definitions = await screen.findByRole('heading', { name: '定义' });
    await waitFor(() => expect(document.activeElement).toBe(definitions.closest('header')));
    expect(document.querySelector('[data-template-editor-announcer]')?.textContent).toContain('定义面板');

    const unsupportedProblem = screen.getByText('UNSUPPORTED_POINTER').closest('li');
    expect(unsupportedProblem).not.toBeNull();
    expect(within(unsupportedProblem as HTMLElement).queryByRole('button')).toBeNull();
    expect(within(unsupportedProblem as HTMLElement).getByText('只能在问题摘要中查看')).toBeTruthy();
  });

  it('uses one roving tree tab stop with ArrowUp/Down, Home and End navigation', () => {
    render(<TemplateEditorShell session={cleanSession(structuredBaseline())} />);

    const treeItems = screen.getAllByRole('treeitem');
    expect(treeItems.map((item) => item.tabIndex)).toEqual([0, -1, -1]);

    treeItems[0]?.focus();
    fireEvent.keyDown(treeItems[0] as HTMLElement, { key: 'ArrowDown' });
    expect(document.activeElement).toBe(treeItems[1]);
    expect(treeItems[1]?.getAttribute('aria-selected')).toBe('true');

    fireEvent.keyDown(treeItems[1] as HTMLElement, { key: 'End' });
    expect(document.activeElement).toBe(treeItems[2]);
    expect(treeItems[2]?.getAttribute('aria-selected')).toBe('true');

    fireEvent.keyDown(treeItems[2] as HTMLElement, { key: 'Home' });
    expect(document.activeElement).toBe(treeItems[0]);
    expect(treeItems[0]?.getAttribute('aria-selected')).toBe('true');

    fireEvent.keyDown(treeItems[0] as HTMLElement, { key: 'ArrowUp' });
    expect(document.activeElement).toBe(treeItems[0]);
    expect(screen.getAllByRole('treeitem').filter((item) => item.tabIndex === 0)).toHaveLength(1);
  });

  it('exposes real hierarchy disclosure, left/right navigation and ancestor-preserving search', () => {
    render(<TemplateEditorShell session={cleanSession(structuredBaseline())} />);

    const frame = screen.getByRole('treeitem', { name: /内容区/ });
    expect(frame.getAttribute('aria-expanded')).toBe('true');
    const collapse = within(frame).getByRole('button', { name: '折叠内容区子级' });
    fireEvent.click(collapse);
    expect(frame.getAttribute('aria-expanded')).toBe('false');
    expect(screen.queryByRole('treeitem', { name: /底色/ })).toBeNull();

    frame.focus();
    fireEvent.keyDown(frame, { key: 'ArrowRight' });
    expect(frame.getAttribute('aria-expanded')).toBe('true');
    fireEvent.keyDown(frame, { key: 'ArrowRight' });
    const rect = screen.getByRole('treeitem', { name: /底色/ });
    expect(document.activeElement).toBe(rect);
    expect(rect.getAttribute('aria-selected')).toBe('true');

    fireEvent.keyDown(rect, { key: 'ArrowLeft' });
    expect(document.activeElement).toBe(frame);

    fireEvent.click(within(frame).getByRole('button', { name: '折叠内容区子级' }));
    expect(frame.getAttribute('aria-expanded')).toBe('false');

    fireEvent.change(screen.getByRole('searchbox', { name: '搜索 DesignDSL 结构' }), {
      target: { value: '底色' },
    });
    expect(screen.getByRole('treeitem', { name: /画布/ })).toBeTruthy();
    expect(screen.getByRole('treeitem', { name: /内容区/ }).getAttribute('aria-expanded')).toBe('true');
    expect(screen.getByRole('treeitem', { name: /底色/ })).toBeTruthy();
  });

  it('moves keyboard focus to a virtualized row outside the mounted window', async () => {
    const baseline = await largeBaseline(55);
    render(<TemplateEditorShell session={cleanSession(baseline)} />);

    const root = screen.getByRole('treeitem', { name: /画布/ });
    root.focus();
    fireEvent.keyDown(root, { key: 'End' });

    const target = await screen.findByRole('treeitem', { name: /节点 55/ });
    await waitFor(() => expect(document.activeElement).toBe(target));
    expect(target.getAttribute('aria-selected')).toBe('true');
  });

  it('expands the bounded tree window before focusing a problem target past the first 50 nodes', async () => {
    const baseline = await largeBaseline(55);
    const session = dirtySession(baseline, '大型结构定位');
    const transport = saveTransport(await invalidProblemResponse(session, [[
      '/designRoot/children/54/displayName',
      'LATE_NODE_PROBLEM',
    ]]));
    render(<TemplateEditorShell session={session} saveTransport={transport} />);

    fireEvent.click(screen.getByRole('button', { name: '保存 canonical 本地草稿' }));
    await screen.findByRole('heading', { name: '确认仍保存为 INVALID' });
    expect(screen.queryByRole('treeitem', { name: /节点 55/ })).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: /定位到节点“节点 55”/ }));
    const target = await screen.findByRole('treeitem', { name: /节点 55/ });
    await waitFor(() => expect(document.activeElement).toBe(target));
    expect(target.getAttribute('aria-selected')).toBe('true');
  });
});

function cleanSession(baseline: CanonicalTemplateBaseline): StructuredEditorSession {
  const session = createSessionFromBaseline(baseline, { state: 'checked', value: 'READY' });
  if (session.mode !== 'structured') throw new Error('expected Structured Editor');
  return session;
}

function dirtySession(
  baseline: CanonicalTemplateBaseline,
  name: string,
): StructuredEditorSession {
  const result = applyTemplateDisplayName(cleanSession(baseline), name);
  if (result.state !== 'applied') throw new Error(`expected applied, got ${result.state}`);
  return result.session;
}

function saveTransport(body: string): TemplateSaveTransport {
  return {
    getCurrent: vi.fn().mockRejectedValue(new Error('unexpected GET')),
    putCurrent: vi.fn().mockResolvedValue({ status: 422, body }),
  };
}

async function invalidProblemResponse(
  session: StructuredEditorSession,
  problems: ReadonlyArray<readonly [pointer: string, code: string]>,
): Promise<string> {
  const proposedContentHash = await contentHash(session.workingCopy.canonicalDesignDsl);
  return JSON.stringify({
    code: 'TEMPLATE_DEPENDENCY_CONFIRMATION_REQUIRED',
    proposedContentHash,
    confirmationToken: 'a'.repeat(64),
    expiresAt: '2099-01-01T00:00:00Z',
    problems: problems.map(([canonicalPointer, code]) => ({
      code,
      category: 'DEPENDENCY',
      severity: 'ERROR',
      canonicalPointer,
      messageArgs: [],
    })),
    truncated: false,
  });
}

async function largeBaseline(childCount: number): Promise<CanonicalTemplateBaseline> {
  const baseline = structuredBaseline();
  const canvas = baseline.designDsl.designRoot as Record<string, unknown>;
  canvas.children = Array.from({ length: childCount }, (_, index) => ({
    nodeId: `node-${index + 1}`,
    kind: 'frame',
    displayName: `节点 ${index + 1}`,
    bindings: [],
    children: [],
  }));
  baseline.canonicalDesignDsl = JSON.stringify(baseline.designDsl);
  baseline.contentHash = await contentHash(baseline.canonicalDesignDsl);
  return baseline;
}

async function contentHash(canonical: string): Promise<string> {
  const bytes = new TextEncoder().encode(`renderweave-design-content/1\0${canonical}`);
  const digest = new Uint8Array(await crypto.subtle.digest('SHA-256', bytes));
  return `sha256:${Array.from(
    digest,
    (byte) => byte.toString(16).padStart(2, '0'),
  ).join('')}`;
}
