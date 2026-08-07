import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Page } from '@playwright/test';

test.describe('production Schema Studio', () => {
  test('keeps array/reference edits lossless across Form, Map, history and dirty guard', async ({ page }, testInfo) => {
    const consoleErrors: string[] = [];
    page.on('console', (message) => { if (message.type() === 'error') consoleErrors.push(message.text()); });
    page.on('pageerror', (error) => consoleErrors.push(error.message));

    await page.route('**/api/v1/schema-drafts/seed-schema', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          schemaKey: 'seed-schema', revision: 1,
          definition: { dslVersion: 'renderweave-schema/1.0', displayName: 'Seed', fields: [] },
          creationSource: 'USER', createdAt: '2026-08-08T00:00:00Z',
          updatedAt: '2026-08-08T00:00:00Z', savedAt: '2026-08-08T00:00:00Z',
          resolvedRevisions: { 'seed-schema': 1 },
        }),
      });
    });
    await page.goto('/schemas/seed-schema');
    await page.locator('.rail-create').click();
    await expect(page).toHaveURL(/\/schemas\/new$/);
    await expect(page.locator('[data-product="schema-studio"]')).toBeVisible();
    await page.locator('#schema-key').fill('catalog-card');
    await page.locator('#schema-display-name').fill('商品目录卡');
    await page.getByLabel('fieldKey', { exact: true }).fill('products');
    await page.getByLabel('显示名称（可选）', { exact: true }).fill('商品列表');
    await page.getByLabel('字段类型').selectOption('array');
    await page.getByLabel('数组元素类型').selectOption('reference');
    await page.getByLabel('目标 schemaKey').fill('product-item');
    await page.getByRole('button', { name: 'StaticSchemaRef' }).click();
    await page.getByLabel('versionTag').fill('v1');

    await page.getByRole('button', { name: '树状图', exact: true }).click();
    await expect(page.locator('.react-flow__node')).toHaveCount(3);
    await expect(page.locator('.map-detail-node')).toContainText('product-item@v1');
    await page.screenshot({ path: testInfo.outputPath('schema-studio-map-1280x720.png'), fullPage: true });

    await page.keyboard.press('Control+z');
    await expect(page.getByLabel('versionTag')).toHaveValue('');
    await page.keyboard.press('Control+Shift+z');
    await expect(page.getByLabel('versionTag')).toHaveValue('v1');

    await page.getByRole('button', { name: '表单', exact: true }).click();
    await expect(page.getByText('Array[引用]', { exact: false }).first()).toBeVisible();

    const accessibility = await new AxeBuilder({ page })
      .include('[data-product="schema-studio"]')
      .withTags(['wcag2a', 'wcag2aa', 'wcag21aa', 'wcag22aa'])
      .analyze();
    expect(accessibility.violations.filter((violation) =>
      violation.impact === 'serious' || violation.impact === 'critical')).toEqual([]);

    await page.goBack();
    await expect(page.getByRole('dialog')).toBeVisible();
    await expect(page.getByRole('heading', { name: '离开前保存更改？' })).toBeVisible();
    await page.getByRole('button', { name: '继续编辑' }).click();
    await expect(page).toHaveURL(/\/schemas\/new$/);

    await expectNoHorizontalOverflow(page);
    await page.screenshot({ path: testInfo.outputPath('schema-studio-form-1280x720.png'), fullPage: true });

    await page.setViewportSize({ width: 1024, height: 768 });
    await expect(page.locator('.studio-body')).toBeVisible();
    await page.getByRole('button', { name: '字段检查器' }).click();
    await expect(page.locator('.studio-inspector')).toBeVisible();
    await expectNoHorizontalOverflow(page);
    await page.screenshot({ path: testInfo.outputPath('schema-studio-drawer-1024x768.png'), fullPage: true });

    await page.setViewportSize({ width: 1000, height: 768 });
    await expect(page.getByText('RenderWeave v1 需要至少 1024px 宽度')).toBeVisible();
    await expectNoHorizontalOverflow(page);
    expect(consoleErrors).toEqual([]);
  });

  test('loads and operates a representative 256-field schema in both modes', async ({ page }) => {
    const definition = representativeDefinition();
    await page.route('**/api/v1/schema-drafts/large-schema', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          schemaKey: 'large-schema',
          revision: 8,
          definition,
          creationSource: 'USER',
          createdAt: '2026-08-08T00:00:00Z',
          updatedAt: '2026-08-08T00:00:00Z',
          savedAt: '2026-08-08T00:00:00Z',
          resolvedRevisions: { 'large-schema': 8, child: 2 },
        }),
      });
    });

    await page.goto('/schemas/large-schema');
    await expect(page.locator('.studio-field-row')).toHaveCount(256);
    await expect(page.getByRole('button', { name: '已达到 256 个字段上限' })).toBeDisabled();
    await page.getByPlaceholder('搜索字段、说明或类型').fill('field-255');
    await expect(page.locator('.studio-field-row')).toHaveCount(1);
    await page.getByPlaceholder('搜索字段、说明或类型').fill('');

    await page.getByRole('button', { name: '树状图', exact: true }).click();
    await expect(page.locator('.react-flow__node')).toHaveCount(299);
    await expect(page.locator('.studio-map-status')).toContainText('256 个字段');
    await page.getByRole('button', { name: '表单', exact: true }).click();
    await expect(page.locator('.studio-field-row')).toHaveCount(256);
  });
});

async function expectNoHorizontalOverflow(page: Page) {
  const dimensions = await page.evaluate(() => ({
    clientWidth: document.documentElement.clientWidth,
    scrollWidth: document.documentElement.scrollWidth,
  }));
  expect(dimensions.scrollWidth).toBeLessThanOrEqual(dimensions.clientWidth);
}

function representativeDefinition() {
  const types = ['text', 'decimal', 'date', 'time', 'boolean', 'reference'] as const;
  return {
    dslVersion: 'renderweave-schema/1.0',
    displayName: '256 字段代表性 Schema',
    fields: Array.from({ length: 256 }, (_, index) => {
      const fieldNumber = index.toString().padStart(3, '0');
      if (index % 6 === 5) {
        return {
          fieldKey: `field-${fieldNumber}`,
          displayName: `数组字段 ${fieldNumber}`,
          required: index % 2 === 0,
          value: { type: 'array', constraints: { maxItems: 100 }, items: { type: 'reference', ref: { schemaKey: 'child', versionTag: 'v1' } } },
        };
      }
      const type = types[index % types.length]!;
      const value = type === 'reference'
        ? { type, ref: { schemaKey: 'child', versionTag: 'v1' } }
        : { type };
      return {
        fieldKey: `field-${fieldNumber}`,
        displayName: `字段 ${fieldNumber}`,
        required: index % 2 === 0,
        value,
      };
    }),
  };
}
