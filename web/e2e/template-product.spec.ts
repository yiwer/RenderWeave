import AxeBuilder from '@axe-core/playwright';
import { createHash } from 'node:crypto';

import { expect, test, type Page, type Route } from '@playwright/test';

const TEMPLATE_ID = '9034a1da-5a76-469c-8de0-516eebf2c742';
const OPERATION_ID = '123e4567-e89b-42d3-a456-426614174000';
const CANONICAL_DESIGN = '{"definitions":[],"designRoot":{"bindings":[],"children":[],"heightMm":297,"kind":"canvas","nodeId":"123e4567-e89b-42d3-a456-426614174000","widthMm":210},"displayName":"API template","dslVersion":"renderweave-design/1.0","expressionProfile":"renderweave-expression/1.0"}';
const CONTENT_HASH = `sha256:${createHash('sha256')
  .update(`renderweave-design-content/1\0${CANONICAL_DESIGN}`)
  .digest('hex')}`;
const PNG = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9ZlZkAAAAASUVORK5CYII=',
  'base64',
);

test.describe('formal Template final product', () => {
  test('publishes the catalog in primary navigation at every supported width', async ({ page }) => {
    const browserErrors = captureBrowserErrors(page);
    await installTemplateHttpContract(page);

    for (const viewport of [
      { width: 1440, height: 900 },
      { width: 1280, height: 720 },
      { width: 1024, height: 768 },
    ]) {
      await page.setViewportSize(viewport);
      await page.goto('/templates');
      await expect(page).toHaveURL(/\/templates$/);
      await expect(page.getByRole('heading', { name: '模板' })).toBeVisible();
      await expect(page.getByRole('link', { name: '模板设计' })).toHaveAttribute('aria-current', 'page');
      await expect(page.getByRole('link', { name: /API template/ })).toBeVisible();
      await expect(page.getByText('RenderWeave v1 需要至少 1024px 宽度')).toBeHidden();
      await expectNoHorizontalOverflow(page);
      await expectNoSeriousOrCriticalAxe(page, '.resource-shell');
    }

    expect(browserErrors).toEqual([]);
  });

  test('creates, opens and previews through formal product URLs, then returns to the catalog', async ({ page }) => {
    const browserErrors = captureBrowserErrors(page);
    const contract = await installTemplateHttpContract(page);
    await page.setViewportSize({ width: 1280, height: 720 });
    await page.goto('/templates');

    await page.getByRole('link', { name: '新建模板' }).first().click();
    await expect(page).toHaveURL(/\/templates\/new$/);
    await expect(page.getByRole('heading', { name: '新建模板' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'StaticSchema' })).toContainText('正式产品结构');
    await page.getByRole('textbox', { name: 'Template 名称' }).fill('API template');
    await page.getByRole('button', { name: '创建并打开' }).click();

    await expect(page).toHaveURL(new RegExp(`/templates/${TEMPLATE_ID}$`));
    await expect(page.getByRole('main', { name: 'Template 编辑工作区' })).toBeVisible();
    await expect(page.getByRole('heading', { level: 1, name: 'API template' })).toBeVisible();
    await expect(page.getByText('READY')).toBeVisible();
    await expect(page.getByRole('link', { name: '返回模板目录' })).toHaveAttribute('href', '/templates');

    await page.getByRole('button', { name: '打开权威预览' }).click();
    await page.getByRole('button', { name: '生成权威预览' }).click();
    await expect(page.getByRole('img', { name: 'API template的权威预览' })).toBeVisible();
    await expect(page.getByText('完整结果已核验')).toBeVisible();
    await expect(page.getByText('1 × 1 px')).toBeVisible();
    await expect(page.getByText('renderweave-renderer/test-certified')).toBeVisible();
    expect(contract.previewRequests).toBe(1);
    expect(contract.createRequests).toBe(1);
    await expectNoHorizontalOverflow(page);
    await expectNoSeriousOrCriticalAxe(page, '.template-editor-root');

    await page.getByRole('link', { name: '返回模板目录' }).click();
    await expect(page).toHaveURL(/\/templates$/);
    await expect(page.getByRole('heading', { name: '模板' })).toBeVisible();
    expect(browserErrors).toEqual([]);
  });
});

async function installTemplateHttpContract(page: Page) {
  const observations = { createRequests: 0, previewRequests: 0 };
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const method = request.method();

    if (method === 'GET' && url.pathname === '/api/v1/templates') {
      await json(route, {
        items: [{
          templateId: TEMPLATE_ID,
          displayName: 'API template',
          staticSchema: { schemaKey: 'system-empty', versionTag: 'v1' },
          revision: 0,
          readiness: 'READY',
          updatedAt: '2026-08-26T08:00:00Z',
        }],
      });
      return;
    }
    if (method === 'GET' && url.pathname === '/api/v1/static-schemas') {
      await json(route, {
        items: [{
          schemaKey: 'system-empty',
          versionTag: 'v1',
          origin: 'SYSTEM',
          displayName: '正式产品结构',
          fieldCount: 0,
          referenceDepth: 0,
          publishedAt: '2026-08-26T07:00:00Z',
        }],
        page: 1,
        size: 50,
        total: 1,
      });
      return;
    }
    if (method === 'POST' && url.pathname === '/api/v1/templates') {
      observations.createRequests += 1;
      expect(url.searchParams.get('schemaKey')).toBe('system-empty');
      expect(url.searchParams.get('versionTag')).toBe('v1');
      expect(request.postDataJSON()).toMatchObject({
        dslVersion: 'renderweave-design/1.0',
        displayName: 'API template',
        designRoot: { kind: 'canvas', widthMm: 210, heightMm: 297 },
      });
      await json(route, { templateId: TEMPLATE_ID, disclosure: 'OPAQUE' });
      return;
    }
    if (method === 'GET' && url.pathname === `/api/v1/templates/${TEMPLATE_ID}`) {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: `{"templateId":"${TEMPLATE_ID}","disclosure":"READABLE","revision":0,"staticSchema":{"schemaKey":"system-empty","versionTag":"v1"},"contentHash":"${CONTENT_HASH}","readiness":"STALE","designDsl":${CANONICAL_DESIGN}}`,
      });
      return;
    }
    if (method === 'POST' && url.pathname === `/api/v1/templates/${TEMPLATE_ID}/readiness-recheck`) {
      await json(route, {
        templateId: TEMPLATE_ID,
        revision: 0,
        contentHash: CONTENT_HASH,
        readiness: 'READY',
      });
      return;
    }
    if (method === 'POST' && url.pathname === `/api/v1/templates/${TEMPLATE_ID}/authoritative-preview`) {
      observations.previewRequests += 1;
      expect(url.searchParams.get('format')).toBe('PNG');
      expect(url.searchParams.get('dpi')).toBe('96');
      expect(request.postData()).toBe('{"rootDocument":{}}');
      expect(request.headers()['content-type'])
        .toBe('application/vnd.renderweave.render-input+json;version=1.0');
      await route.fulfill({
        status: 200,
        headers: {
          'Content-Type': 'image/png',
          'Content-Length': String(PNG.byteLength),
          'Content-Digest': `sha-256=:${createHash('sha256').update(PNG).digest('base64')}:`,
          'RenderWeave-Result-Version': 'renderweave-render-result/1.0',
          'RenderWeave-Request-Id': OPERATION_ID,
          'RenderWeave-Renderer-Profile': 'renderweave-renderer/test-certified',
          'RenderWeave-DSL-Version': 'renderweave-render/1.0',
          'RenderWeave-Layout-Profile': 'renderweave-layout/1.0',
          'RenderWeave-Output-Profile': 'renderweave-output-png/1.0',
          'RenderWeave-Format': 'PNG',
          'RenderWeave-Width-Px': '1',
          'RenderWeave-Height-Px': '1',
          'RenderWeave-DPI': '96',
        },
        body: PNG,
      });
      return;
    }

    await route.fulfill({ status: 501, contentType: 'text/plain', body: `${method} ${url.pathname}` });
  });
  return observations;
}

async function json(route: Route, body: unknown) {
  await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) });
}

function captureBrowserErrors(page: Page): string[] {
  const errors: string[] = [];
  page.on('console', (message) => {
    if (message.type() === 'error') errors.push(message.text());
  });
  page.on('pageerror', (error) => errors.push(error.message));
  return errors;
}

async function expectNoSeriousOrCriticalAxe(page: Page, include: string): Promise<void> {
  const accessibility = await new AxeBuilder({ page })
    .include(include)
    .withTags(['wcag2a', 'wcag2aa', 'wcag21aa', 'wcag22aa'])
    .analyze();
  expect(accessibility.violations.filter((violation) =>
    violation.impact === 'serious' || violation.impact === 'critical')).toEqual([]);
}

async function expectNoHorizontalOverflow(page: Page): Promise<void> {
  expect(await page.evaluate(() => ({
    body: document.body.scrollWidth <= document.body.clientWidth,
    document: document.documentElement.scrollWidth <= document.documentElement.clientWidth,
  }))).toEqual({ body: true, document: true });
}
