import AxeBuilder from '@axe-core/playwright';
import { expect, test } from '@playwright/test';
import path from 'node:path';

test.skip(process.env.RENDERWEAVE_LIVE_E2E !== '1', 'Runs only inside the real PostgreSQL inference harness.');

test('executes a real replay run and autosaves one reviewed Candidate item', async ({ page }) => {
  await page.goto('/inference');
  await expect(page.getByRole('heading', { name: '从合成样本生成 Schema Candidate' })).toBeVisible();
  await expect(page.locator('.fixture-row')).toHaveCount(20);
  await page.locator('.fixture-row').filter({ hasText: 'combined-17-all-null' }).click();
  await page.getByRole('checkbox').check();
  await page.getByRole('button', { name: '运行并进入审核' }).click();

  await expect(page).toHaveURL(/\/inference-runs\/[0-9a-f-]+\/review$/);
  await expect(page.getByText(/blocker 阻止落库/)).toBeVisible();
  await page.locator('.candidate-field-row').filter({ hasText: 'value' }).click();
  await expect(page.locator('[data-evidence-box]')).toBeVisible();
  await expect(page.locator('.json-evidence-list code').filter({ hasText: /^\/value$/ })).toBeVisible();
  await page.getByLabel('Candidate 字段类型').selectOption('TEXT');
  await expect(page.getByText('已保存', { exact: true })).toBeVisible();
  await expect(page.getByText('Candidate 审核门已通过')).toBeVisible();
  await page.getByRole('button', { name: '树图' }).click();
  await expect(page.locator('.candidate-map-surface')).toBeVisible();

  const accessibility = await new AxeBuilder({ page })
    .include('.resource-shell')
    .withTags(['wcag2a', 'wcag2aa', 'wcag21aa', 'wcag22aa'])
    .analyze();
  expect(accessibility.violations.filter((violation) =>
    violation.impact === 'serious' || violation.impact === 'critical')).toEqual([]);

  const evidenceDir = process.env.RENDERWEAVE_EVIDENCE_DIR;
  if (evidenceDir) {
    await page.screenshot({ path: path.join(evidenceDir, 'inference-review-live.png'), fullPage: true });
  }
});
