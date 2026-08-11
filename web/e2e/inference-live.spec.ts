import AxeBuilder from '@axe-core/playwright';
import { expect, test } from '@playwright/test';
import path from 'node:path';

test.skip(process.env.RENDERWEAVE_LIVE_E2E !== '1', 'Runs only inside the real PostgreSQL inference harness.');

test('executes a real replay run and atomically creates its reviewed Draft bundle', async ({ page }) => {
  const staticBeforeResponse = await page.request.get('/api/v1/static-schemas?page=1&size=100');
  expect(staticBeforeResponse.ok()).toBeTruthy();
  const staticBefore = await staticBeforeResponse.json() as { total: number };

  await page.goto('/inference/samples');
  await expect(page.getByRole('heading', { name: '确定性样本' })).toBeVisible();
  await expect(page.locator('.fixture-row')).toHaveCount(20);
  await page.locator('.fixture-row').filter({ hasText: 'combined-17-all-null' }).click();
  await page.getByRole('checkbox').check();
  await page.getByRole('button', { name: '运行并查看监控' }).click();

  await expect(page).toHaveURL(/\/inference-runs\/[0-9a-f-]+\/monitor$/);
  await expect(page.getByText('Candidate 已生成')).toBeVisible();
  await expect(page.getByRole('heading', { name: '阶段与检查点' })).toBeVisible();
  await expect(page.getByText('无需恢复')).toBeVisible();
  await page.setViewportSize({ width: 1024, height: 768 });
  await expect(page.locator('.resource-body')).toBeVisible();
  const logToggle = page.getByRole('button', { name: '收起' });
  await logToggle.focus();
  await page.keyboard.press('Enter');
  await expect(page.locator('#inference-execution-log-body')).toHaveCount(0);
  await expect(page.getByRole('button', { name: '展开' })).toBeFocused();
  await page.keyboard.press('Enter');
  await expect(page.getByRole('heading', { name: '阶段与检查点' })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  const monitorAccessibility = await new AxeBuilder({ page })
    .include('.resource-shell')
    .withTags(['wcag2a', 'wcag2aa', 'wcag21aa', 'wcag22aa'])
    .analyze();
  expect(monitorAccessibility.violations.filter((violation) =>
    violation.impact === 'serious' || violation.impact === 'critical')).toEqual([]);
  await page.setViewportSize({ width: 1280, height: 720 });
  await page.getByRole('link', { name: /查看识别结果/ }).first().click();
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

  const runId = page.url().match(/\/inference-runs\/([0-9a-f-]+)\/review$/)?.[1];
  expect(runId).toBeTruthy();
  await page.getByRole('button', { name: '原子创建 1 个 Draft' }).click();
  await expect(page.getByText('任一 active key 或 tombstone 冲突：整包零写')).toBeVisible();
  await page.getByRole('button', { name: '确认原子创建' }).click();
  await expect(page.getByText('Draft Bundle 已原子创建')).toBeVisible();
  await expect(page.getByText('final Candidate 已冻结；本次操作没有发布、更新或删除任何既有 Schema。')).toBeVisible();
  await expect(page.getByRole('navigation', { name: '数据结构识别进度' }).locator('[aria-current="step"]')).toContainText('原子创建');
  const createdDraftLink = page.getByRole('link', { name: /combined-all-null/ });
  await expect(createdDraftLink).toHaveAttribute('href', '/schemas/combined-all-null');

  const reviewResponse = await page.request.get(`/api/v1/inference-runs/${runId}/candidate`);
  expect(reviewResponse.ok()).toBeTruthy();
  const review = await reviewResponse.json() as {
    run: { state: string; stage: string };
    candidateRevision: number;
    finalCandidate: unknown;
    appliedAt: string | null;
  };
  expect(review.run).toMatchObject({ state: 'COMPLETED', stage: 'ATOMIC_CREATE' });
  expect(review.candidateRevision).toBe(1);
  expect(review.finalCandidate).not.toBeNull();
  expect(review.appliedAt).toBeTruthy();

  const draftResponse = await page.request.get('/api/v1/schema-drafts/combined-all-null');
  expect(draftResponse.ok()).toBeTruthy();
  const draft = await draftResponse.json() as { schemaKey: string; revision: number; creationSource: string };
  expect(draft).toMatchObject({ schemaKey: 'combined-all-null', revision: 0, creationSource: 'AI' });

  const staticAfterResponse = await page.request.get('/api/v1/static-schemas?page=1&size=100');
  expect(staticAfterResponse.ok()).toBeTruthy();
  const staticAfter = await staticAfterResponse.json() as { total: number };
  expect(staticAfter.total).toBe(staticBefore.total);

  const accessibility = await new AxeBuilder({ page })
    .include('.resource-shell')
    .withTags(['wcag2a', 'wcag2aa', 'wcag21aa', 'wcag22aa'])
    .analyze();
  expect(accessibility.violations.filter((violation) =>
    violation.impact === 'serious' || violation.impact === 'critical')).toEqual([]);

  const evidenceDir = process.env.RENDERWEAVE_EVIDENCE_DIR;
  if (evidenceDir) {
    await page.screenshot({ path: path.join(evidenceDir, 'inference-atomic-created-live.png'), fullPage: true });
  }

  await createdDraftLink.click();
  await expect(page).toHaveURL(/\/schemas\/combined-all-null$/);
  await expect(page.getByText('combined-all-null', { exact: true }).first()).toBeVisible();
});
