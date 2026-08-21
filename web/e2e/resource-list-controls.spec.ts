import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Page, type Route } from '@playwright/test';

test.describe('resource list controls', () => {
  test('searches, sorts and paginates data structure designs through the server contract', async ({ page }, testInfo) => {
    const drafts = Array.from({ length: 12 }, (_, index) => draftSummary(index + 1));
    let lastQuery = new URLSearchParams();
    await page.route('**/api/v1/schema-drafts**', async (route) => {
      const url = new URL(route.request().url());
      if (route.request().method() !== 'GET' || url.pathname !== '/api/v1/schema-drafts') return route.abort('failed');
      lastQuery = new URLSearchParams(url.search);
      await pagedList(route, drafts, url, 'draft');
    });

    await page.goto('/schemas');
    await expect(page.getByRole('heading', { name: '数据结构设计' })).toBeVisible();
    await expect(page.locator('.app-resource-rail').getByRole('link', { name: '数据结构设计' })).toBeVisible();
    await expect(page.locator('.app-resource-rail').getByRole('link', { name: '数据结构资产' })).toBeVisible();
    await expect(page.locator('.draft-schema-card')).toHaveCount(9);
    await expect.poll(() => lastQuery.get('sort')).toBe('UPDATED_DESC');
    await expect.poll(() => lastQuery.get('size')).toBe('9');

    await page.getByRole('button', { name: '下一页' }).click();
    await expect(page.locator('.draft-schema-card')).toHaveCount(3);
    await expect.poll(() => lastQuery.get('page')).toBe('2');

    await page.getByLabel('搜索数据结构设计').fill('design-10');
    await expect(page.locator('.draft-schema-card')).toHaveCount(1);
    await expect.poll(() => lastQuery.get('search')).toBe('design-10');
    await expect.poll(() => lastQuery.get('page')).toBe('1');

    await page.getByLabel('排序').click();
    await page.getByRole('option', { name: '名称 Z–A' }).click();
    await expect.poll(() => lastQuery.get('sort')).toBe('NAME_DESC');
    await page.getByRole('button', { name: '清除搜索' }).click();
    await expect(page.locator('.draft-schema-card')).toHaveCount(9);
    await expect(page.locator('.draft-schema-card').first()).toContainText('设计 12');

    await page.getByLabel('每页数量').click();
    await page.getByRole('option', { name: '18', exact: true }).click();
    await expect(page.locator('.draft-schema-card')).toHaveCount(12);
    await expect.poll(() => lastQuery.get('size')).toBe('18');
    await expectNoHorizontalOverflow(page);
    await page.screenshot({ path: testInfo.outputPath('data-structure-design-list-1280x720.png'), fullPage: true });
  });

  test('defaults assets to user releases and switches exclusively to system presets', async ({ page }, testInfo) => {
    await page.emulateMedia({ reducedMotion: 'reduce' });
    const assets = [
      ...Array.from({ length: 12 }, (_, index) => staticSummary(index + 1, 'DRAFT')),
      ...Array.from({ length: 6 }, (_, index) => staticSummary(index + 1, 'SYSTEM')),
    ];
    let lastQuery = new URLSearchParams();
    await page.route('**/api/v1/static-schemas**', async (route) => {
      const url = new URL(route.request().url());
      if (route.request().method() !== 'GET' || url.pathname !== '/api/v1/static-schemas') return route.abort('failed');
      lastQuery = new URLSearchParams(url.search);
      await pagedList(route, assets, url, 'static');
    });

    await page.goto('/static-schemas');
    await expect(page.getByRole('heading', { name: '数据结构资产' })).toBeVisible();
    const originSwitch = page.getByRole('switch', { name: '只显示系统预设' });
    await expect(originSwitch).toHaveAttribute('aria-checked', 'false');
    await expect(page.locator('.static-card')).toHaveCount(9);
    await expect(page.locator('.static-card').first()).toContainText('用户资产');
    await expect(page.getByText('系统预设 01')).toHaveCount(0);
    await expect.poll(() => lastQuery.get('origin')).toBe('DRAFT');

    await page.getByRole('button', { name: '下一页' }).click();
    await expect(page.locator('.static-card')).toHaveCount(3);
    await expect.poll(() => lastQuery.get('page')).toBe('2');

    await originSwitch.click();
    await expect(originSwitch).toHaveAttribute('aria-checked', 'true');
    await expect(page.locator('.static-card')).toHaveCount(6);
    await expect(page.locator('.static-card').first()).toContainText('系统预设');
    await expect(page.getByText('用户资产 01')).toHaveCount(0);
    await expect.poll(() => lastQuery.get('origin')).toBe('SYSTEM');
    await expect.poll(() => lastQuery.get('page')).toBe('1');

    await page.getByLabel('搜索数据结构资产').fill('system-06');
    await expect(page.locator('.static-card')).toHaveCount(1);
    await expect.poll(() => lastQuery.get('search')).toBe('system-06');
    await page.getByRole('button', { name: '清除搜索' }).click();
    await page.getByLabel('排序').click();
    await page.getByRole('option', { name: '名称 Z–A' }).click();
    await expect(page.locator('.static-card').first()).toContainText('系统预设 06');
    await expect.poll(() => lastQuery.get('sort')).toBe('NAME_DESC');

    await page.locator('.static-card').evaluateAll(async (cards) => {
      await Promise.all(cards.flatMap((card) => card.getAnimations())
        .map((animation) => animation.finished));
    });

    const accessibility = await new AxeBuilder({ page })
      .include('.resource-shell')
      .withTags(['wcag2a', 'wcag2aa', 'wcag21aa', 'wcag22aa'])
      .analyze();
    expect(accessibility.violations.filter((violation) =>
      violation.impact === 'serious' || violation.impact === 'critical')).toEqual([]);

    await expectNoHorizontalOverflow(page);
    await page.screenshot({ path: testInfo.outputPath('data-structure-assets-system-1280x720.png'), fullPage: true });
    await page.setViewportSize({ width: 1024, height: 768 });
    await expectNoHorizontalOverflow(page);
    await page.screenshot({ path: testInfo.outputPath('data-structure-assets-system-1024x768.png'), fullPage: true });
  });
});

interface ListItem {
  schemaKey: string;
  displayName: string;
  savedAt?: string;
  publishedAt?: string;
  versionTag?: string;
  origin?: 'DRAFT' | 'SYSTEM';
}

async function pagedList(route: Route, source: ListItem[], url: URL, kind: 'draft' | 'static') {
  const page = Number(url.searchParams.get('page') ?? '1');
  const size = Number(url.searchParams.get('size') ?? '20');
  const search = (url.searchParams.get('search') ?? '').toLocaleLowerCase('zh-CN');
  const sort = url.searchParams.get('sort') ?? (kind === 'draft' ? 'UPDATED_DESC' : 'PUBLISHED_DESC');
  const origin = url.searchParams.get('origin') ?? 'ALL';
  let items = source.filter((item) => kind === 'draft' || origin === 'ALL' || item.origin === origin);
  items = items.filter((item) => !search || `${item.schemaKey} ${item.displayName} ${item.versionTag ?? ''}`.toLocaleLowerCase('zh-CN').includes(search));
  items.sort((left, right) => {
    if (sort === 'NAME_ASC' || sort === 'NAME_DESC') {
      const direction = sort === 'NAME_ASC' ? 1 : -1;
      return direction * left.displayName.localeCompare(right.displayName, 'zh-CN', { numeric: true });
    }
    const leftTime = left.savedAt ?? left.publishedAt ?? '';
    const rightTime = right.savedAt ?? right.publishedAt ?? '';
    const direction = sort.endsWith('_ASC') ? 1 : -1;
    return direction * leftTime.localeCompare(rightTime);
  });
  const offset = (page - 1) * size;
  await route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ items: items.slice(offset, offset + size), page, size, total: items.length }),
  });
}

function draftSummary(index: number) {
  const suffix = index.toString().padStart(2, '0');
  const timestamp = `2026-08-${suffix}T02:30:00Z`;
  return {
    schemaKey: `design-${suffix}`,
    revision: index,
    creationSource: index % 3 === 0 ? 'AI' : 'USER',
    displayName: `设计 ${suffix}`,
    fieldCount: index,
    createdAt: timestamp,
    updatedAt: timestamp,
    savedAt: timestamp,
  };
}

function staticSummary(index: number, origin: 'DRAFT' | 'SYSTEM') {
  const suffix = index.toString().padStart(2, '0');
  return {
    schemaKey: `${origin === 'SYSTEM' ? 'system' : 'asset'}-${suffix}`,
    versionTag: `v${index}`,
    origin,
    displayName: `${origin === 'SYSTEM' ? '系统预设' : '用户资产'} ${suffix}`,
    fieldCount: index,
    referenceDepth: 1,
    publishedAt: `2026-08-${suffix}T02:30:00Z`,
  };
}

async function expectNoHorizontalOverflow(page: Page) {
  const dimensions = await page.evaluate(() => ({
    clientWidth: document.documentElement.clientWidth,
    scrollWidth: document.documentElement.scrollWidth,
  }));
  expect(dimensions.scrollWidth).toBeLessThanOrEqual(dimensions.clientWidth);
}
