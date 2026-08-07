import { expect, test } from '@playwright/test';

test('prototype route boots inside the Vite browser harness', async ({ page }) => {
  await page.goto('/prototype/schema-studio?variant=A');
  await expect(page.locator('[data-prototype="schema-studio"]')).toBeVisible();
  await expect(page.getByRole('heading', { name: '商品推广卡' })).toBeVisible();
});
