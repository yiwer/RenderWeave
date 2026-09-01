import AxeBuilder from '@axe-core/playwright';
import { createHash } from 'node:crypto';
import { mkdirSync, writeFileSync } from 'node:fs';
import path from 'node:path';

import { expect, test, type Page, type Response } from '@playwright/test';

const LIVE = process.env.RENDERWEAVE_TEMPLATE_CANDIDATE_LIVE === '1';
const EVIDENCE_DIR = process.env.RENDERWEAVE_EVIDENCE_DIR;

declare global {
  interface Window {
    __renderWeaveCandidatePendingSeen?: boolean;
    __renderWeaveCandidatePendingObserver?: MutationObserver;
  }
}

test.describe('local Template Candidate Preview', () => {
  test.skip(!LIVE, 'requires the explicit local Candidate Preview canary environment');

  test('creates, saves and verifies real PNG/JPEG while Authoritative Preview stays closed', async ({ page }) => {
    test.setTimeout(90_000);
    const browserErrors = captureBrowserErrors(page);
    const displayName = `Candidate canary ${Date.now()}`;
    await page.setViewportSize({ width: 1280, height: 720 });

    await page.goto('/templates/new', { waitUntil: 'domcontentloaded' });
    await expect(page.getByRole('heading', { name: '新建模板' })).toBeVisible();
    await page.getByRole('button', { name: 'StaticSchema' }).click();
    await page.getByRole('option', { name: /system-empty@v1/ }).click();
    await page.getByRole('textbox', { name: 'Template 名称' }).fill(displayName);
    await page.getByRole('button', { name: '创建并打开' }).click();

    await expect(page).toHaveURL(/\/templates\/[0-9a-f-]+$/);
    const templateId = new URL(page.url()).pathname.split('/').at(-1);
    expect(templateId).toMatch(/^[0-9a-f-]{36}$/);
    await page.goto(`/templates/${templateId}?candidatePreview=local`, {
      waitUntil: 'domcontentloaded',
    });
    await expect(page.getByRole('main', { name: 'Template 编辑工作区' })).toBeVisible();

    await page.getByRole('button', { name: '节点' }).click();
    await page.getByRole('button', { name: '添加矩形' }).click();
    await expect(page.getByRole('treeitem', { name: /矩形 1/ })).toBeVisible();
    await page.getByRole('button', { name: '保存 canonical 本地草稿' }).click();
    await expect(page.getByText('revision 1')).toBeVisible();
    await expect(page.getByText('Canonical current')).toBeVisible();

    await page.getByRole('button', { name: '打开候选预览（NOT_CERTIFIED）' }).click();
    await expect(page.getByRole('heading', { name: '候选预览' })).toBeVisible();
    await expect(page.getByText('NOT_CERTIFIED', { exact: true })).toBeVisible();
    await installPendingObserver(page);

    const pngResponse = page.waitForResponse(candidateResponse('PNG'));
    await page.getByRole('button', { name: '生成候选预览（NOT_CERTIFIED）' }).click();
    const png = await verifyCandidateResponse(await pngResponse, 'PNG');
    await expect(page.getByRole('img', {
      name: `${displayName}的候选预览（NOT_CERTIFIED）`,
    })).toBeVisible();
    await expect(page.getByText('NOT_CERTIFIED · 完整结果已核验')).toBeVisible();
    expect(await candidatePendingWasSeen(page)).toBe(true);

    await page.getByRole('combobox', { name: '输出格式' }).selectOption('JPEG');
    await expect(page.getByRole('img')).toHaveCount(0);
    await installPendingObserver(page);
    const jpegResponse = page.waitForResponse(candidateResponse('JPEG'));
    await page.getByRole('button', { name: '生成候选预览（NOT_CERTIFIED）' }).click();
    const jpeg = await verifyCandidateResponse(await jpegResponse, 'JPEG');
    await expect(page.getByRole('img', {
      name: `${displayName}的候选预览（NOT_CERTIFIED）`,
    })).toBeVisible();
    await expect(page.getByText('Quality', { exact: true }).locator('..')).toContainText('90');
    expect(await candidatePendingWasSeen(page)).toBe(true);

    await expectNoSeriousOrCriticalAxe(page, '.template-editor-root');
    expect(browserErrors).toEqual([]);

    await page.goto(`/templates/${templateId}`, { waitUntil: 'domcontentloaded' });
    await page.getByRole('button', { name: '打开权威预览' }).click();
    const authoritativeResponse = page.waitForResponse((response) => {
      const url = new URL(response.url());
      return response.request().method() === 'POST'
        && url.pathname === `/api/v1/templates/${templateId}/authoritative-preview`;
    });
    await page.getByRole('button', { name: '生成权威预览' }).click();
    const authoritative = await authoritativeResponse;
    expect(authoritative.status()).toBe(503);
    expect(authoritative.headers()['renderweave-candidate-status']).toBeUndefined();
    const authoritativeProblem = await authoritative.json() as { code?: string };
    expect(authoritativeProblem.code).toBe('RENDERER_UNAVAILABLE');
    const alert = page.getByRole('alert');
    await expect(alert).toContainText('权威预览未生成');
    await expect(alert).toContainText('RENDERER_UNAVAILABLE');
    await expect(alert).toBeFocused();

    expect(browserErrors.filter((message) =>
      !message.includes('server responded with a status of 503'))).toEqual([]);
    writeEvidence({
      contractVersion: 'renderweave-template-candidate-preview-validation/1.0',
      assurance: 'NOT_CERTIFIED',
      templateId,
      authoredChange: 'RECT_ADDED_AND_SAVED_AT_REVISION_1',
      png,
      jpeg,
      authoritativePreview: {
        status: authoritative.status(),
        code: authoritativeProblem.code,
        candidateStatusHeaderPresent: false,
      },
      accessibility: { seriousOrCritical: 0 },
      asyncFeedback: { candidatePendingObserved: true, authoritativeErrorFocused: true },
    });
  });
});

function candidateResponse(format: 'PNG' | 'JPEG') {
  return (response: Response): boolean => {
    const url = new URL(response.url());
    return response.request().method() === 'POST'
      && url.pathname.startsWith('/internal/candidate-preview/templates/')
      && url.searchParams.get('format') === format;
  };
}

async function verifyCandidateResponse(response: Response, format: 'PNG' | 'JPEG') {
  expect(response.status()).toBe(200);
  const headers = response.headers();
  const bytes = await response.body();
  const digest = `sha-256=:${createHash('sha256').update(bytes).digest('base64')}:`;
  expect(headers['renderweave-candidate-status']).toBe('NOT_CERTIFIED');
  expect(headers['cache-control']).toContain('no-store');
  expect(headers['content-length']).toBe(String(bytes.byteLength));
  expect(headers['content-digest']).toBe(digest);
  expect(headers['renderweave-result-version']).toBe('renderweave-render-result/1.0');
  expect(headers['renderweave-renderer-profile']).toBe('renderweave-renderer/1.0');
  expect(headers['renderweave-dsl-version']).toBe('renderweave-render/1.0');
  expect(headers['renderweave-layout-profile']).toBe('renderweave-layout/1.0');
  expect(headers['renderweave-format']).toBe(format);
  expect(Number(headers['renderweave-width-px'])).toBeGreaterThan(0);
  expect(Number(headers['renderweave-height-px'])).toBeGreaterThan(0);
  expect(headers['renderweave-dpi']).toBe('96');
  if (format === 'PNG') {
    expect(headers['content-type']).toContain('image/png');
    expect(headers['renderweave-output-profile']).toBe('renderweave-output-png/1.0');
    expect(headers['renderweave-quality']).toBeUndefined();
    expect([...bytes.subarray(0, 8)]).toEqual([137, 80, 78, 71, 13, 10, 26, 10]);
  } else {
    expect(headers['content-type']).toContain('image/jpeg');
    expect(headers['renderweave-output-profile']).toBe('renderweave-output-jpeg/1.0');
    expect(headers['renderweave-quality']).toBe('90');
    expect([...bytes.subarray(0, 2)]).toEqual([255, 216]);
    expect([...bytes.subarray(-2)]).toEqual([255, 217]);
  }
  return {
    format,
    byteLength: bytes.byteLength,
    contentDigest: digest,
    rendererProfile: headers['renderweave-renderer-profile'],
    layoutProfile: headers['renderweave-layout-profile'],
    outputProfile: headers['renderweave-output-profile'],
    widthPx: Number(headers['renderweave-width-px']),
    heightPx: Number(headers['renderweave-height-px']),
    dpi: Number(headers['renderweave-dpi']),
    ...(format === 'JPEG' ? { quality: Number(headers['renderweave-quality']) } : {}),
  };
}

async function installPendingObserver(page: Page): Promise<void> {
  await page.evaluate(() => {
    window.__renderWeaveCandidatePendingSeen = document.body.textContent
      ?.includes('正在生成 NOT_CERTIFIED 候选预览') ?? false;
    window.__renderWeaveCandidatePendingObserver?.disconnect();
    window.__renderWeaveCandidatePendingObserver = new MutationObserver(() => {
      if (document.body.textContent?.includes('正在生成 NOT_CERTIFIED 候选预览')) {
        window.__renderWeaveCandidatePendingSeen = true;
        window.__renderWeaveCandidatePendingObserver?.disconnect();
      }
    });
    window.__renderWeaveCandidatePendingObserver.observe(document.body, {
      childList: true,
      subtree: true,
      characterData: true,
    });
  });
}

async function candidatePendingWasSeen(page: Page): Promise<boolean> {
  return page.evaluate(() => {
    window.__renderWeaveCandidatePendingObserver?.disconnect();
    return window.__renderWeaveCandidatePendingSeen === true;
  });
}

async function expectNoSeriousOrCriticalAxe(page: Page, include: string): Promise<void> {
  const accessibility = await new AxeBuilder({ page })
    .include(include)
    .withTags(['wcag2a', 'wcag2aa', 'wcag21aa', 'wcag22aa'])
    .analyze();
  expect(accessibility.violations.filter((violation) =>
    violation.impact === 'serious' || violation.impact === 'critical')).toEqual([]);
}

function captureBrowserErrors(page: Page): string[] {
  const errors: string[] = [];
  page.on('console', (message) => {
    if (message.type() === 'error') errors.push(message.text());
  });
  page.on('pageerror', (error) => errors.push(error.message));
  return errors;
}

function writeEvidence(value: unknown): void {
  if (!EVIDENCE_DIR) return;
  mkdirSync(EVIDENCE_DIR, { recursive: true });
  writeFileSync(
    path.join(EVIDENCE_DIR, 'candidate-preview-summary.json'),
    `${JSON.stringify(value, null, 2)}\n`,
    'utf8',
  );
}
