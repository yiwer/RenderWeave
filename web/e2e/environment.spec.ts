import { expect, test } from '@playwright/test';

test('prototype route boots inside the Vite browser harness', async ({ page }) => {
  await page.goto('/prototype/schema-studio?variant=A');
  await expect(page.locator('[data-prototype="schema-studio"]')).toBeVisible();
  await expect(page.getByRole('heading', { name: '商品推广卡' })).toBeVisible();
});

test('reloads once when a lazy route chunk was replaced during deployment', async ({ page }) => {
  let chunkRequests = 0;
  await page.route('**/*RootDocumentValidatorPage*', async (route) => {
    chunkRequests += 1;
    if (chunkRequests === 1) {
      await route.abort('failed');
      return;
    }
    await route.continue();
  });

  await page.goto('/validator', { waitUntil: 'commit' });

  await expect(page.getByRole('heading', { name: '用真实样本检查 Schema' })).toBeVisible();
  expect(chunkRequests).toBe(2);
  await expect(page.getByText('Unexpected Application Error!')).toHaveCount(0);
});

test('shows the product recovery page without an infinite reload when a chunk stays unavailable', async ({ page }) => {
  let chunkRequests = 0;
  await page.route('**/*RootDocumentValidatorPage*', async (route) => {
    chunkRequests += 1;
    await route.abort('failed');
  });

  await page.goto('/validator', { waitUntil: 'commit' });

  await expect(page.getByRole('heading', { name: '页面资源暂时无法加载' })).toBeVisible();
  await expect(page.getByRole('button', { name: '重新加载应用' })).toBeVisible();
  await expect(page.getByText('Unexpected Application Error!')).toHaveCount(0);
  await expect.poll(() => chunkRequests).toBe(2);
  await page.waitForTimeout(500);
  expect(chunkRequests).toBe(2);
});
