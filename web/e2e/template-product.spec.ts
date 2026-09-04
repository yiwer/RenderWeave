import AxeBuilder from '@axe-core/playwright';
import { createHash } from 'node:crypto';

import { expect, test, type Page, type Route } from '@playwright/test';

const TEMPLATE_ID = '9034a1da-5a76-469c-8de0-516eebf2c742';
const OPERATION_ID = '123e4567-e89b-42d3-a456-426614174000';
const CANONICAL_DESIGN = '{"definitions":[],"designRoot":{"bindings":[],"children":[],"heightMm":297,"kind":"canvas","nodeId":"123e4567-e89b-42d3-a456-426614174000","widthMm":210},"displayName":"API template","dslVersion":"renderweave-design/1.0","expressionProfile":"renderweave-expression/1.0"}';
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

  test('carries an exact StaticSchema through keyboard creation and keeps every result class safe', async ({ page }) => {
    const browserErrors = captureBrowserErrors(page);
    const contract = await installTemplateHttpContract(page, [
      'OPAQUE',
      'TRANSPORT_UNKNOWN',
      'READABLE',
    ]);
    await page.setViewportSize({ width: 1280, height: 720 });

    await page.goto('/static-schemas/archived-price/v7');
    await page.getByRole('link', { name: '基于此结构新建模板' }).click();
    await expect(page).toHaveURL(/\/templates\/new\?schemaKey=archived-price&versionTag=v7$/);
    await expect(page.getByRole('button', { name: 'StaticSchema' }))
      .toContainText('Archived price · archived-price@v7');

    await page.getByRole('searchbox', { name: '搜索 StaticSchema' }).fill('catalog');
    await expect(page.getByText('共 18 项 · 第 1 页')).toBeVisible();
    await page.getByRole('button', { name: '下一页' }).click();
    await expect(page.getByText('共 18 项 · 第 2 页')).toBeVisible();
    const schemaPicker = page.getByRole('button', { name: 'StaticSchema' });
    await schemaPicker.focus();
    await page.keyboard.press('Enter');
    await page.keyboard.press('End');
    await page.keyboard.press('Enter');
    await expect(schemaPicker).toContainText('Catalog Beta · catalog-beta@v2');

    await page.getByRole('textbox', { name: 'Template 名称' }).fill('Opaque attempt');
    await page.getByRole('button', { name: '创建并打开' }).click();
    await expect(page.getByText('Template 已提交')).toBeVisible();
    await expect(page.getByText(TEMPLATE_ID)).toBeVisible();
    await expect(page.getByText(/不具备读取权限/)).toBeVisible();
    await expect(page).toHaveURL(/\/templates\/new/);

    await page.goto('/templates/new?schemaKey=archived-price&versionTag=v7');
    await page.getByRole('textbox', { name: 'Template 名称' }).fill('Preserved intent');
    await page.getByRole('button', { name: '创建并打开' }).click();
    await expect(page.getByRole('alert')).toContainText('创建结果未知');
    await expect(page.getByRole('alert')).toContainText('再次创建可能创建重复 Template');
    await expect(page.getByRole('textbox', { name: 'Template 名称' })).toHaveValue('Preserved intent');
    await expect(page.getByText('Template 目录已刷新，请先检查是否已创建。')).toBeVisible();
    expect(contract.catalogRequests).toBeGreaterThan(0);

    await page.getByRole('button', { name: '我已检查目录，仍要再次创建' }).click();
    await expect(page).toHaveURL(new RegExp(`/templates/${TEMPLATE_ID}$`));
    await expect(page.getByRole('main', { name: 'Template 编辑工作区' })).toBeVisible();
    expect(contract.createRequests).toBe(3);
    expect(contract.createSchemaRefs).toEqual([
      'catalog-beta@v2',
      'archived-price@v7',
      'archived-price@v7',
    ]);
    expect(contract.createDisplayNames).toEqual([
      'Opaque attempt',
      'Preserved intent',
      'Preserved intent',
    ]);
    await expectNoHorizontalOverflow(page);
    await expectNoSeriousOrCriticalAxe(page, '.template-editor-root');
    expect(browserErrors.some((error) => error.includes('ERR_CONNECTION_RESET'))).toBe(true);
    expect(browserErrors.filter((error) => !error.includes('ERR_CONNECTION_RESET'))).toEqual([]);
  });

  test('creates a Rect in the formal editor, saves its canonical wire and keeps it after reopen', async ({ page }) => {
    const browserErrors = captureBrowserErrors(page);
    const contract = await installTemplateHttpContract(page);
    await page.setViewportSize({ width: 1280, height: 720 });
    await page.goto(`/templates/${TEMPLATE_ID}`);

    await page.getByRole('button', { name: '元素' }).click();
    await page.getByRole('button', { name: '添加矩形' }).click();

    await expect(page.getByRole('button', { name: '结构' })).toHaveAttribute('aria-current', 'page');
    await expect(page.getByRole('treeitem', { name: /矩形 1/ })).toHaveAttribute('aria-selected', 'true');
    await expect(page.getByRole('heading', { name: '矩形 1' })).toBeVisible();
    const rectShape = page.locator('[data-template-canvas-node-kind="rect"]');
    await expect(rectShape).toBeVisible();
    const rectBeforeZoom = await rectShape.boundingBox();
    expect(rectBeforeZoom).not.toBeNull();
    expect(rectBeforeZoom?.width).toBeGreaterThan(20);
    expect(rectBeforeZoom?.height).toBeGreaterThan(10);

    const viewport = page.locator('[data-template-canvas-viewport]');
    const scaleBefore = Number(await viewport.getAttribute('data-canvas-scale'));
    const anchor = {
      x: (rectBeforeZoom?.x ?? 0) + (rectBeforeZoom?.width ?? 0) / 2,
      y: (rectBeforeZoom?.y ?? 0) + (rectBeforeZoom?.height ?? 0) / 2,
    };
    await page.mouse.move(anchor.x, anchor.y);
    await page.mouse.wheel(0, -180);
    await expect.poll(async () => Number(await viewport.getAttribute('data-canvas-scale')))
      .toBeGreaterThan(scaleBefore);
    const rectAfterZoom = await rectShape.boundingBox();
    expect(Math.abs(
      (rectAfterZoom?.x ?? 0) + (rectAfterZoom?.width ?? 0) / 2 - anchor.x,
    )).toBeLessThan(2);
    expect(Math.abs(
      (rectAfterZoom?.y ?? 0) + (rectAfterZoom?.height ?? 0) / 2 - anchor.y,
    )).toBeLessThan(2);
    await expect(page.getByRole('button', { name: '适合画板' })).toBeVisible();

    await page.getByRole('button', { name: '折叠画布子级' }).click();
    await expect(page.getByRole('treeitem', { name: /矩形 1/ })).toHaveCount(0);
    await page.getByRole('button', { name: '展开画布子级' }).click();
    await page.getByRole('searchbox', { name: '搜索 DesignDSL 结构' }).fill('矩形 1');
    await expect(page.getByRole('treeitem', { name: /画布/ })).toBeVisible();
    await expect(page.getByRole('treeitem', { name: /矩形 1/ })).toBeVisible();
    await expect(page.getByText('Canonical 本地草稿')).toBeVisible();
    await page.getByRole('button', { name: '保存 canonical 本地草稿' }).click();
    await expect(page.getByText('revision 1')).toBeVisible();

    expect(contract.saveRequests).toBe(1);
    const saved = JSON.parse(contract.savedCanonical);
    expect(saved.designRoot.children).toHaveLength(1);
    expect(saved.designRoot.children[0]).toMatchObject({
      kind: 'rect',
      displayName: '矩形 1',
      bindings: [],
      placement: {
        type: 'ABSOLUTE',
        xMm: 10,
        yMm: 10,
        widthMode: 'FIXED',
        widthMm: 30,
        heightMode: 'FIXED',
        heightMm: 20,
      },
      fill: { color: '#2563EBFF' },
    });
    expect(saved.designRoot.children[0].nodeId)
      .toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);

    await page.reload();
    const rect = page.getByRole('treeitem', { name: /矩形 1/ });
    await expect(rect).toBeVisible();
    await rect.click();
    await expect(page.getByRole('heading', { name: '矩形 1' })).toBeVisible();
    await expect(page.getByText('Canonical current')).toBeVisible();
    await expectNoSeriousOrCriticalAxe(page, '.template-editor-root');
    expect(browserErrors).toEqual([]);
  });
});

type CreateOutcome = 'READABLE' | 'OPAQUE' | 'TRANSPORT_UNKNOWN';

async function installTemplateHttpContract(
  page: Page,
  createOutcomes: CreateOutcome[] = ['READABLE'],
) {
  const observations = {
    catalogRequests: 0,
    createRequests: 0,
    createDisplayNames: [] as string[],
    createSchemaRefs: [] as string[],
    previewRequests: 0,
    saveRequests: 0,
    savedCanonical: CANONICAL_DESIGN,
    revision: 0,
  };
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const method = request.method();

    if (method === 'GET' && url.pathname === '/api/v1/templates') {
      observations.catalogRequests += 1;
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
      const search = url.searchParams.get('search') ?? '';
      const pageNumber = Number(url.searchParams.get('page') ?? '1');
      const catalogItem = pageNumber === 1
        ? staticSchemaSummary('catalog-alpha', 'v1', 'Catalog Alpha')
        : staticSchemaSummary('catalog-beta', 'v2', 'Catalog Beta');
      await json(route, {
        items: search === 'catalog'
          ? [catalogItem]
          : [staticSchemaSummary('system-empty', 'v1', '正式产品结构', 'SYSTEM')],
        page: pageNumber,
        size: Number(url.searchParams.get('size') ?? '9'),
        total: search === 'catalog' ? 18 : 1,
      });
      return;
    }
    const exactStatic = url.pathname.match(/^\/api\/v1\/static-schemas\/([^/]+)\/([^/]+)$/);
    if (method === 'GET' && exactStatic) {
      const schemaKey = decodeURIComponent(exactStatic[1] ?? '');
      const versionTag = decodeURIComponent(exactStatic[2] ?? '');
      await json(route, staticSchemaSnapshot(
        schemaKey,
        versionTag,
        schemaKey === 'archived-price' ? 'Archived price' : '正式产品结构',
      ));
      return;
    }
    if (method === 'POST' && url.pathname === '/api/v1/templates') {
      observations.createRequests += 1;
      observations.createSchemaRefs.push(
        `${url.searchParams.get('schemaKey')}@${url.searchParams.get('versionTag')}`,
      );
      const createBody = request.postDataJSON();
      observations.createDisplayNames.push(String(createBody.displayName));
      expect(createBody).toMatchObject({
        dslVersion: 'renderweave-design/1.0',
        designRoot: { kind: 'canvas', widthMm: 210, heightMm: 297 },
      });
      const outcome = createOutcomes.shift() ?? 'READABLE';
      if (outcome === 'TRANSPORT_UNKNOWN') {
        await route.abort('connectionreset');
        return;
      }
      if (outcome === 'OPAQUE') {
        await json(route, { templateId: TEMPLATE_ID, disclosure: 'OPAQUE' });
        return;
      }
      await json(route, JSON.parse(currentTemplateBody(CANONICAL_DESIGN, 0, 'READY')));
      return;
    }
    if (method === 'GET' && url.pathname === `/api/v1/templates/${TEMPLATE_ID}`) {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: currentTemplateBody(
          observations.savedCanonical,
          observations.revision,
          observations.revision === 0 ? 'STALE' : 'READY',
        ),
      });
      return;
    }
    if (method === 'POST' && url.pathname === `/api/v1/templates/${TEMPLATE_ID}/readiness-recheck`) {
      await json(route, {
        templateId: TEMPLATE_ID,
        revision: observations.revision,
        contentHash: contentHashOf(observations.savedCanonical),
        readiness: 'READY',
      });
      return;
    }
    if (method === 'PUT' && url.pathname === `/api/v1/templates/${TEMPLATE_ID}`) {
      expect(url.searchParams.get('expectedRevision')).toBe(String(observations.revision));
      expect(request.headers()['content-type']).toBe('application/vnd.renderweave.design+json');
      observations.saveRequests += 1;
      observations.savedCanonical = request.postData() ?? '';
      observations.revision += 1;
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: currentTemplateBody(observations.savedCanonical, observations.revision, 'READY'),
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

function staticSchemaSummary(
  schemaKey: string,
  versionTag: string,
  displayName: string,
  origin: 'DRAFT' | 'SYSTEM' = 'DRAFT',
) {
  return {
    schemaKey,
    versionTag,
    origin,
    displayName,
    fieldCount: 0,
    referenceDepth: 1,
    publishedAt: '2026-08-26T07:00:00Z',
  };
}

function staticSchemaSnapshot(schemaKey: string, versionTag: string, displayName: string) {
  return {
    schemaKey,
    versionTag,
    origin: schemaKey.startsWith('system-') ? 'SYSTEM' : 'DRAFT',
    sourceDraftRevision: schemaKey.startsWith('system-') ? null : 4,
    definition: {
      dslVersion: 'renderweave-schema/1.0',
      displayName,
      fields: [],
    },
    compilerVersion: 'renderweave-schema-compiler/1.0',
    releaseNote: null,
    referenceDepth: 1,
    publishedAt: '2026-08-26T07:00:00Z',
  };
}

function contentHashOf(canonicalDesignDsl: string): string {
  return `sha256:${createHash('sha256')
    .update(`renderweave-design-content/1\0${canonicalDesignDsl}`)
    .digest('hex')}`;
}

function currentTemplateBody(
  canonicalDesignDsl: string,
  revision: number,
  readiness: 'READY' | 'STALE',
): string {
  return `{"templateId":"${TEMPLATE_ID}","disclosure":"READABLE","revision":${revision},`
    + `"staticSchema":{"schemaKey":"system-empty","versionTag":"v1"},`
    + `"contentHash":"${contentHashOf(canonicalDesignDsl)}","readiness":"${readiness}",`
    + `"designDsl":${canonicalDesignDsl}}`;
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
