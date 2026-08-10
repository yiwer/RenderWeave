import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Page, type Route } from '@playwright/test';

const savedAt = '2026-08-08T02:30:00Z';
const exactDecimal = '12345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678';

test.describe('Schema resource lifecycle', () => {
  test('lists Drafts, previews immutable history, restores and copies a saved revision', async ({ page }, testInfo) => {
    let current = draftSnapshot('catalog-card', 2, '商品目录卡');
    await page.route('**/api/v1/**', async (route) => {
      const url = new URL(route.request().url());
      const method = route.request().method();
      if (method === 'GET' && url.pathname === '/api/v1/schema-drafts') {
        await json(route, { items: [draftSummary(current)], page: 1, size: 50, total: 1 });
      } else if (method === 'GET' && url.pathname === '/api/v1/schema-drafts/catalog-card') {
        await json(route, current);
      } else if (method === 'GET' && url.pathname === '/api/v1/schema-drafts/catalog-card/revisions') {
        await json(route, {
          items: [
            { revision: 2, displayName: '商品目录卡', fieldCount: 1, savedAt },
            { revision: 0, displayName: '最初目录卡', fieldCount: 1, savedAt: '2026-08-07T02:30:00Z' },
          ],
          page: 1, size: 100, total: 2,
        });
      } else if (method === 'GET' && url.pathname === '/api/v1/schema-drafts/catalog-card/revisions/2') {
        await json(route, {
          schemaKey: 'catalog-card', revision: 2, savedAt,
          definition: definition('商品目录卡'),
        });
      } else if (method === 'GET' && url.pathname === '/api/v1/schema-drafts/catalog-card/revisions/0') {
        await json(route, {
          schemaKey: 'catalog-card', revision: 0, savedAt: '2026-08-07T02:30:00Z',
          definition: definition('最初目录卡'),
        });
      } else if (method === 'POST' && url.pathname === '/api/v1/schema-drafts/catalog-card/restore') {
        expect(route.request().postData()).toBe('{"expectedRevision":2,"sourceRevision":0}');
        current = draftSnapshot('catalog-card', 3, '最初目录卡');
        await json(route, current);
      } else if (method === 'POST' && url.pathname === '/api/v1/schema-drafts/catalog-card/copies') {
        const body = JSON.parse(route.request().postData() ?? '{}') as { schemaKey: string; displayName: string };
        current = draftSnapshot(body.schemaKey, 0, body.displayName);
        await json(route, current, 201);
      } else if (method === 'GET' && url.pathname === '/api/v1/schema-drafts/catalog-card-copy') {
        await json(route, current);
      } else {
        await route.abort('failed');
      }
    });

    await page.goto('/schemas');
    await expect(page.getByRole('heading', { name: '数据结构设计' })).toBeVisible();
    await expect(page.locator('.app-resource-rail').getByRole('link', { name: '新建 Draft' })).toHaveCount(0);
    await expect(page.getByRole('link', { name: '打开 商品目录卡' })).toBeVisible();
    await page.getByRole('button', { name: '查看 商品目录卡 的历史' }).click();
    await expect(page.getByRole('heading', { name: '不可变 revision 历史' })).toBeVisible();
    await expect(page.getByRole('button', { name: /revision 2/ })).toHaveAttribute('aria-pressed', 'true');
    await expect(page.getByRole('tab', { name: '字段树' })).toHaveAttribute('aria-selected', 'true');
    await expect(page.locator('.history-preview .readonly-schema-tree')).toContainText('商品目录卡');
    await page.screenshot({ path: testInfo.outputPath('draft-history-tree-1280x720.png'), fullPage: true });
    const historyAccessibility = await new AxeBuilder({ page }).include('.history-dialog').analyze();
    expect(historyAccessibility.violations.filter((violation) =>
      violation.impact === 'serious' || violation.impact === 'critical')).toEqual([]);
    await page.getByRole('tab', { name: '字段表单' }).click();
    await expect(page.locator('.history-preview .readonly-definition-form')).toContainText('标题');
    await page.getByRole('tab', { name: 'DSL JSON' }).click();
    await expect(page.locator('.history-preview pre')).toContainText('renderweave-schema/1.0');
    await page.getByRole('button', { name: '关闭' }).click();
    await expect(page.getByRole('button', { name: '发布 商品目录卡 为 StaticSchema' })).toBeVisible();
    await expect(page.getByRole('button', { name: '删除 商品目录卡' })).toBeVisible();
    await page.screenshot({ path: testInfo.outputPath('draft-list-1280x720.png'), fullPage: true });
    await page.getByRole('link', { name: '打开 商品目录卡' }).click();
    await expect(page.locator('.studio-rail').getByRole('link', { name: '当前 Draft' })).toHaveCount(0);
    await expect(page.locator('.studio-rail').getByRole('link', { name: '数据结构设计' })).toHaveAttribute('aria-current', 'page');

    await page.getByRole('button', { name: '历史', exact: true }).click();
    await expect(page.getByRole('heading', { name: '不可变 revision 历史' })).toBeVisible();
    await expect(page.getByRole('button', { name: /revision 2/ })).toHaveAttribute('aria-pressed', 'true');
    await page.getByRole('button', { name: /revision 0/ }).click();
    await expect(page.locator('.history-preview')).toContainText('最初目录卡');
    await page.getByRole('button', { name: '恢复为新 revision' }).click();
    await expect(page.locator('.rail-context-card small')).toContainText('revision 3');
    await expect(page.locator('#schema-display-name')).toHaveValue('最初目录卡');

    await page.getByRole('button', { name: '复制', exact: true }).click();
    await page.getByLabel('新 schemaKey').fill('catalog-card-copy');
    await page.getByRole('dialog').getByLabel('显示名称', { exact: true }).fill('目录卡副本');
    await page.getByRole('button', { name: '创建副本' }).click();
    await expect(page).toHaveURL(/\/schemas\/catalog-card-copy$/);
    await expect(page.locator('#schema-display-name')).toHaveValue('目录卡副本');
  });

  test('keeps local conflict edits, shows a structural diff and reloads only on command', async ({ page }) => {
    const local = draftSnapshot('conflict-card', 2, '本地前的名称');
    const server = draftSnapshot('conflict-card', 3, '服务端名称');
    let conflicted = false;
    await page.route('**/api/v1/schema-drafts/conflict-card', async (route) => {
      if (route.request().method() === 'PUT') {
        conflicted = true;
        await json(route, {
          type: 'https://renderweave.dev/problems/revision-conflict', title: 'Revision conflict', status: 409,
          code: 'REVISION_CONFLICT', traceId: 'test-conflict', detail: 'current revision is 3', revision: 3,
        }, 409);
      } else {
        await json(route, conflicted ? server : local);
      }
    });

    await page.goto('/schemas/conflict-card');
    await page.locator('#schema-display-name').fill('我的本地名称');
    await page.getByRole('button', { name: '保存 revision' }).click();
    await expect(page.getByText('检测到 revision 冲突，本地内容已保留')).toBeVisible();
    await expect(page.locator('#schema-display-name')).toHaveValue('我的本地名称');
    await expect(page.locator('.conflict-diff')).toContainText('服务端 revision 3');
    await expect(page.locator('.conflict-diff')).toContainText('我的本地名称');
    await expect(page.locator('.conflict-diff')).toContainText('服务端名称');

    await page.getByRole('button', { name: '载入服务端' }).click();
    await expect(page.locator('#schema-display-name')).toHaveValue('服务端名称');
    await expect(page.locator('.rail-context-card small')).toContainText('revision 3');
  });

  test('publishes an exact StaticSchema and preserves a 128-digit decimal in readable artifacts', async ({ page }, testInfo) => {
    let draft = draftSnapshot('price-card', 4, '价格卡');
    let definitionRequests = 0;
    let compiledRequests = 0;
    let staticSnapshot = {
      schemaKey: 'price-card', versionTag: 'v1', origin: 'DRAFT', sourceDraftRevision: 5,
      definition: definition('价格卡'), compilerVersion: 'renderweave-schema/1.0',
      releaseNote: '首个稳定版本', referenceDepth: 1, publishedAt: savedAt,
    };
    await page.route('**/api/v1/**', async (route) => {
      const url = new URL(route.request().url());
      const method = route.request().method();
      if (method === 'GET' && url.pathname === '/api/v1/schema-drafts/price-card') {
        await json(route, draft);
      } else if (method === 'PUT' && url.pathname === '/api/v1/schema-drafts/price-card') {
        expect(route.request().postData()).toContain('"expectedRevision":4');
        draft = draftSnapshot('price-card', 5, '价格卡发布版');
        await json(route, draft);
      } else if (method === 'POST' && url.pathname === '/api/v1/static-schemas') {
        expect(route.request().postData()).toContain('"expectedRevision":5');
        staticSnapshot = { ...staticSnapshot, definition: draft.definition };
        await json(route, staticSnapshot, 201);
      } else if (method === 'GET' && url.pathname === '/api/v1/static-schemas/price-card/v1') {
        await json(route, staticSnapshot);
      } else if (method === 'GET' && url.pathname === '/api/v1/static-schemas') {
        await json(route, {
          items: [{
            schemaKey: staticSnapshot.schemaKey,
            versionTag: staticSnapshot.versionTag,
            displayName: staticSnapshot.definition.displayName,
            origin: staticSnapshot.origin,
            fieldCount: staticSnapshot.definition.fields.length,
            referenceDepth: staticSnapshot.referenceDepth,
            publishedAt: staticSnapshot.publishedAt,
          }],
          page: 1, size: 100, total: 1,
        });
      } else if (method === 'GET' && url.pathname.endsWith('/definition')) {
        definitionRequests += 1;
        await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(staticSnapshot.definition) });
      } else if (method === 'GET' && url.pathname.endsWith('/compiled-json-schema')) {
        compiledRequests += 1;
        await route.fulfill({
          status: 200,
          contentType: 'application/schema+json',
          body: `{"type":"object","properties":{"price":{"type":"number","maximum":${exactDecimal}}},"additionalProperties":true}`,
        });
      } else {
        await route.abort('failed');
      }
    });

    await page.goto('/schemas/price-card');
    await page.locator('#schema-display-name').fill('价格卡发布版');
    await page.getByRole('button', { name: '保存并发布' }).click();
    await expect(page.getByRole('heading', { name: '保存并发布 StaticSchema' })).toBeVisible();
    await page.getByLabel('versionTag').fill('v1');
    await page.getByLabel('发布说明（可选）').fill('首个稳定版本');
    await page.getByRole('button', { name: '保存并原子发布' }).click();

    await expect(page).toHaveURL(/\/static-schemas\/price-card\/v1$/);
    await expect(page.getByText('不可变边界已建立')).toBeVisible();
    await expect(page.getByRole('tab', { name: '字段树' })).toHaveAttribute('aria-selected', 'true');
    await expect(page.locator('.static-view-panel .readonly-schema-tree')).toContainText('标题');
    const breadcrumbBox = await page.locator('.resource-breadcrumb').boundingBox();
    expect(breadcrumbBox).not.toBeNull();
    expect(breadcrumbBox!.x).toBeLessThan(420);
    await page.screenshot({ path: testInfo.outputPath('static-detail-tree-1280x720.png'), fullPage: true });
    const staticAccessibility = await new AxeBuilder({ page }).include('.static-detail-views').analyze();
    expect(staticAccessibility.violations.filter((violation) =>
      violation.impact === 'serious' || violation.impact === 'critical')).toEqual([]);
    await page.setViewportSize({ width: 1024, height: 768 });
    await expectNoHorizontalOverflow(page);
    await page.setViewportSize({ width: 1280, height: 720 });
    await page.getByRole('tab', { name: '字段表单' }).click();
    await expect(page.locator('.static-definition-form')).toContainText('标题');
    await expect(page.getByRole('button', { name: '查看字段 标题' })).toHaveAttribute('aria-pressed', 'true');
    await expect(page.locator('.static-field-inspector')).toBeVisible();
    await expect(page.locator('.static-field-inspector')).toContainText('fieldKey');
    await expect(page.locator('.static-field-inspector')).toContainText('title');
    await expect(page.locator('.static-field-inspector')).toContainText('字段类型');
    await expect(page.locator('.static-field-inspector')).toContainText('文本');
    await expect(page.locator('.static-field-inspector')).toContainText('必填');
    expect(definitionRequests).toBe(0);
    expect(compiledRequests).toBe(0);
    await page.screenshot({ path: testInfo.outputPath('static-detail-form-1280x720.png'), fullPage: true });

    await page.setViewportSize({ width: 1024, height: 768 });
    await expect(page.locator('.static-field-inspector')).toBeHidden();
    await page.getByRole('button', { name: '字段信息' }).click();
    await expect(page.locator('.static-field-inspector')).toBeVisible();
    await expectNoHorizontalOverflow(page);
    await page.getByRole('button', { name: '关闭字段信息' }).click();
    await page.setViewportSize({ width: 1280, height: 720 });

    await page.getByRole('tab', { name: 'Compiled JSON Schema' }).click();
    await expect(page.locator('.artifact-panel pre')).toContainText(exactDecimal);
    await expect(page.locator('.artifact-panel pre')).toContainText('\n  "type"');
    expect(compiledRequests).toBe(1);

    await page.getByRole('tab', { name: 'Definition DSL' }).click();
    await expect(page.locator('.artifact-panel pre')).toContainText('价格卡发布版');
    expect(definitionRequests).toBe(1);
    await page.screenshot({ path: testInfo.outputPath('static-detail-1280x720.png'), fullPage: true });

    await page.goto('/static-schemas');
    const staticCard = page.locator('.static-card').filter({ hasText: '价格卡发布版' });
    await expect(staticCard.locator('.static-card-title > strong')).toHaveText('价格卡发布版');
    await expect(staticCard.locator('.static-version-badge')).toHaveText('v1');
    await page.screenshot({ path: testInfo.outputPath('static-list-version-1280x720.png'), fullPage: true });
  });

  test('soft deletes with immediate restore and validates raw-number RootDocument batches accessibly', async ({ page }, testInfo) => {
    let current = draftSnapshot('restore-card', 5, '可恢复卡片');
    let deleted = false;
    await page.route('**/api/v1/**', async (route) => {
      const url = new URL(route.request().url());
      const method = route.request().method();
      if (method === 'GET' && url.pathname === '/api/v1/schema-drafts/restore-card') {
        await json(route, current);
      } else if (method === 'DELETE' && url.pathname === '/api/v1/schema-drafts/restore-card') {
        expect(url.searchParams.get('expectedRevision')).toBe('5');
        deleted = true;
        await route.fulfill({ status: 204 });
      } else if (method === 'GET' && url.pathname === '/api/v1/schema-drafts') {
        await json(route, { items: deleted ? [] : [draftSummary(current)], page: 1, size: url.searchParams.get('size') === '100' ? 100 : 50, total: deleted ? 0 : 1 });
      } else if (method === 'POST' && url.pathname === '/api/v1/schema-drafts/restore-card/restore') {
        expect(route.request().postData()).toBe('{"expectedRevision":5,"sourceRevision":5}');
        deleted = false;
        current = draftSnapshot('restore-card', 6, '可恢复卡片');
        await json(route, current);
      } else if (method === 'GET' && url.pathname === '/api/v1/static-schemas') {
        await json(route, { items: [], page: 1, size: 100, total: 0 });
      } else if (method === 'POST' && url.pathname === '/api/v1/root-document-validations') {
        const rawBody = route.request().postData() ?? '';
        expect(rawBody).toContain(`"amount": ${exactDecimal}`);
        await json(route, {
          target: { kind: 'draft', schemaKey: 'restore-card', revision: 6 },
          resolvedSchemas: [{ kind: 'draft', schemaKey: 'restore-card', revision: 6 }],
          summary: { total: 1, valid: 1, invalid: 0 },
          documents: [{ index: 0, valid: true, problems: [], truncated: false }],
        });
      } else {
        await route.abort('failed');
      }
    });

    await page.goto('/schemas/restore-card');
    await page.getByRole('button', { name: '删除', exact: true }).click();
    await page.getByRole('button', { name: '确认软删除' }).click();
    await expect(page).toHaveURL(/\/schemas$/);
    await expect(page.getByText('可恢复卡片 已软删除')).toBeVisible();
    await page.getByRole('button', { name: '撤销删除' }).click();
    await expect(page).toHaveURL(/\/schemas\/restore-card$/);
    await expect(page.locator('.rail-context-card small')).toContainText('revision 6');

    await page.goto('/validator');
    await page.getByLabel('schemaKey').fill('restore-card');
    await page.getByLabel('Document 1 JSON').fill(`{\n  "amount": ${exactDecimal},\n  "unknown": {"accepted": true}\n}`);
    await page.getByRole('button', { name: '验证 1 份样本' }).click();
    await expect(page.getByText('全部样本有效')).toBeVisible();
    await expect(page.locator('.resolved-target')).toContainText('restore-card@revision:6');

    const accessibility = await new AxeBuilder({ page })
      .include('.resource-shell')
      .withTags(['wcag2a', 'wcag2aa', 'wcag21aa', 'wcag22aa'])
      .analyze();
    expect(accessibility.violations.filter((violation) =>
      violation.impact === 'serious' || violation.impact === 'critical')).toEqual([]);

    await expectNoHorizontalOverflow(page);
    await page.screenshot({ path: testInfo.outputPath('validator-result-1280x720.png'), fullPage: true });
    await page.setViewportSize({ width: 1024, height: 768 });
    await expect(page.locator('.resource-body')).toBeVisible();
    await expectNoHorizontalOverflow(page);
    await page.setViewportSize({ width: 1000, height: 768 });
    await expect(page.getByText('RenderWeave v1 需要至少 1024px 宽度')).toBeVisible();
  });
});

function definition(displayName: string) {
  return {
    dslVersion: 'renderweave-schema/1.0',
    displayName,
    fields: [{ fieldKey: 'title', displayName: '标题', required: true, value: { type: 'text' } }],
  };
}

function draftSnapshot(schemaKey: string, revision: number, displayName: string) {
  return {
    schemaKey,
    revision,
    definition: definition(displayName),
    creationSource: 'USER',
    createdAt: '2026-08-07T01:00:00Z',
    updatedAt: savedAt,
    savedAt,
    resolvedRevisions: { [schemaKey]: revision },
  };
}

function draftSummary(snapshot: ReturnType<typeof draftSnapshot>) {
  return {
    schemaKey: snapshot.schemaKey,
    revision: snapshot.revision,
    creationSource: snapshot.creationSource,
    displayName: snapshot.definition.displayName,
    fieldCount: snapshot.definition.fields.length,
    createdAt: snapshot.createdAt,
    updatedAt: snapshot.updatedAt,
    savedAt: snapshot.savedAt,
  };
}

async function json(route: Route, body: unknown, status = 200) {
  await route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
}

async function expectNoHorizontalOverflow(page: Page) {
  const dimensions = await page.evaluate(() => ({
    clientWidth: document.documentElement.clientWidth,
    scrollWidth: document.documentElement.scrollWidth,
  }));
  expect(dimensions.scrollWidth).toBeLessThanOrEqual(dimensions.clientWidth);
}
