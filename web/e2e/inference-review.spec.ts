import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Locator, type Page, type Route } from '@playwright/test';

import type {
  CandidateBundle,
  CandidateProblem,
  InferenceExecutionLogResponse,
  InferenceRunResponse,
  InferenceRunState,
} from '../src/api/generated';

const runId = '44444444-4444-4444-8444-444444444444';
const recentRunId = '55555555-5555-4555-8555-555555555555';
const retryRunId = '66666666-6666-4666-8666-666666666666';
const schemaId = '11111111-1111-4111-8111-111111111111';
const fieldId = '22222222-2222-4222-8222-222222222222';
const artifactIds = [
  '3333333333333333333333333333333333333333333333333333333333333333',
  'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
] as const;

test('completes the four-step Candidate workflow with keyboard authoring and durable resume', async ({ page }, testInfo) => {
  test.setTimeout(60_000);
  const consoleErrors: string[] = [];
  page.on('console', (message) => {
    if (message.type() === 'error') consoleErrors.push(message.text());
  });

  let current = candidateBundle();
  let revision = 0;
  let applied = false;
  let replayFixtureReads = 0;
  await page.route('**/api/v1/inference-runs**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const method = request.method();

    if (method === 'GET' && url.pathname === '/api/v1/inference-runs/replay-fixtures') {
      replayFixtureReads += 1;
      await json(route, replayFixtures());
    } else if (method === 'GET' && url.pathname === '/api/v1/inference-runs/live-availability') {
      await json(route, liveAvailability());
    } else if (method === 'GET' && url.pathname === '/api/v1/inference-runs') {
      await json(route, {
        page: 1,
        size: 6,
        total: 1,
        items: [runResponse(0, 'COMPLETED', recentRunId, 'json-01-scalars')],
      });
    } else if (method === 'POST' && url.pathname === '/api/v1/inference-runs') {
      expect(request.headers()['idempotency-key']).toBeTruthy();
      expect(request.postDataJSON()).toEqual({
        fixtureId: 'combined-08-low-information',
        externalTransferConfirmed: true,
      });
      await json(route, runResponse(revision, 'REVIEW_REQUIRED'), 201);
    } else if (method === 'GET' && url.pathname.endsWith('/events')) {
      await route.fulfill({ status: 200, contentType: 'text/event-stream', body: 'retry: 60000\n\n' });
    } else if (method === 'GET' && artifactIds.some((artifactId) => url.pathname.endsWith(`/artifacts/${artifactId}`))) {
      await route.fulfill({ status: 200, contentType: 'image/svg+xml', body: evidenceSvg() });
    } else if (method === 'GET' && url.pathname === `/api/v1/inference-runs/${runId}/execution-log`) {
      await json(route, executionLog(runResponse(revision, applied ? 'COMPLETED' : 'REVIEW_REQUIRED')));
    } else if (method === 'GET' && url.pathname === `/api/v1/inference-runs/${runId}`) {
      await json(route, runResponse(revision, applied ? 'COMPLETED' : 'REVIEW_REQUIRED'));
    } else if (method === 'GET' && url.pathname === `/api/v1/inference-runs/${runId}/candidate`) {
      await json(route, reviewResponse(current, revision, problemsFor(current), applied));
    } else if (method === 'PUT' && url.pathname === `/api/v1/inference-runs/${runId}/candidate`) {
      const body = request.postDataJSON() as { expectedCandidateRevision: number; candidate: CandidateBundle };
      expect(body.expectedCandidateRevision).toBe(revision);
      const originalField = body.candidate.schemas
        .flatMap((schema) => schema.fields)
        .find((field) => field.candidateFieldId === fieldId);
      expect(originalField?.assessment.confidenceBps).toBe(4200);
      expect(originalField?.assessment.evidence).toHaveLength(3);
      current = body.candidate;
      revision += 1;
      await json(route, reviewResponse(current, revision, problemsFor(current), applied));
    } else if (method === 'POST' && url.pathname === `/api/v1/inference-runs/${runId}/apply`) {
      expect(request.postDataJSON()).toEqual({ expectedCandidateRevision: revision });
      applied = true;
      const schemaKeys = activeSchemaKeys(current);
      await json(route, {
        run: runResponse(revision, 'COMPLETED'),
        candidateRevision: revision,
        rootSchemaKey: 'order',
        createdDrafts: schemaKeys.map((schemaKey) => ({
          schemaKey,
          revision: 0,
          href: `/api/v1/schema-drafts/${schemaKey}`,
        })),
        appliedAt: '2026-08-10T00:00:02Z',
      });
    } else {
      await route.abort('failed');
    }
  });

  await page.goto('/inference');
  await page.waitForLoadState('networkidle');
  await expect(page.getByRole('heading', { name: '历史识别任务' })).toBeVisible();
  await expect(page.getByRole('navigation', { name: '智能识别版面' })).toHaveCount(0);
  await expect(page.getByRole('heading', { name: '全部识别记录' })).toBeVisible();
  await expect(page.locator('.chrome-actions').getByRole('link', { name: '确定性样本' })).toHaveAttribute('href', '/inference/samples');
  await expect(page.locator('.chrome-actions').getByRole('link', { name: '新增识别' })).toHaveAttribute('href', '/inference/new');
  await expect(page.getByRole('link', { name: /查看结果/ })).toHaveAttribute(
    'href',
    `/inference-runs/${recentRunId}/review`,
  );
  await page.screenshot({ path: testInfo.outputPath('inference-history-1280x720.png'), fullPage: true });
  await page.locator('.chrome-actions').getByRole('link', { name: '新增识别' }).click();
  await expect(page).toHaveURL(/\/inference\/new$/);
  await expect(page.getByRole('heading', { name: '新增识别输入' })).toBeVisible();
  await expect(page.getByRole('navigation', { name: '数据结构识别进度' })).toContainText('准备输入');
  await expect(page.getByText('DashScope', { exact: true })).toBeVisible();
  await expect(page.getByRole('heading', { name: '确定性样本' })).toHaveCount(0);
  await expect(page.locator('.fixture-row')).toHaveCount(0);
  expect(replayFixtureReads).toBe(0);
  await page.screenshot({ path: testInfo.outputPath('inference-new-1280x720.png'), fullPage: true });

  await page.locator('.chrome-actions').getByRole('link', { name: '返回历史任务' }).click();
  await page.locator('.chrome-actions').getByRole('link', { name: '确定性样本' }).click();
  await expect(page).toHaveURL(/\/inference\/samples$/);
  await expect(page.getByRole('heading', { name: '确定性样本' })).toBeVisible();
  await expect(page.getByText('外部网络', { exact: true })).toBeVisible();
  await expect(page.getByText('禁止', { exact: true })).toBeVisible();
  await expect(page.locator('.fixture-row')).toHaveCount(1);
  expect(replayFixtureReads).toBe(1);
  await page.screenshot({ path: testInfo.outputPath('inference-samples-1280x720.png'), fullPage: true });
  await page.getByRole('checkbox', { name: /确认使用 replay-v1/ }).check();
  await page.getByRole('button', { name: '运行并查看监控' }).click();

  await expect(page).toHaveURL(new RegExp(`/inference-runs/${runId}/monitor$`));
  await expect(page.getByRole('heading', { name: '识别监控' })).toBeVisible();
  await expect(page.getByText('Candidate 已生成')).toBeVisible();
  await expect(page.getByRole('heading', { name: '执行日志' })).toBeVisible();
  await expect(page.getByText('模型调用 #1 · 生成数据定义')).toBeVisible();
  await page.screenshot({ path: testInfo.outputPath('inference-monitor-1280x720.png'), fullPage: true });
  await page.getByRole('link', { name: /查看识别结果/ }).first().click();

  await expect(page).toHaveURL(new RegExp(`/inference-runs/${runId}/review$`));
  await expect(page.getByRole('heading', { name: '识别结果' })).toBeVisible();
  await expect(page.getByRole('navigation', { name: '数据结构识别进度' }).locator('[aria-current="step"]')).toContainText('逐项校对');
  await expect(page.getByText('1 个 blocker 阻止落库')).toBeVisible();
  await expect(page.getByRole('progressbar', { name: '逐项校对完成度' })).toHaveAttribute('aria-valuenow', '50');
  await expect(page.getByRole('button', { name: '移除当前项' })).toHaveCount(0);
  await expect(page.getByText('根数据结构不可移除；可继续修改名称与 schemaKey。')).toBeVisible();

  await page.locator('.candidate-field-row').filter({ hasText: 'total' }).click();
  const secondImage = page.getByRole('tab', { name: '查看证据图片 2' });
  await secondImage.focus();
  await page.keyboard.press('Enter');
  await expect(page.getByRole('img', { name: '证据图片 2' })).toBeVisible();
  await expect(page.locator('[data-evidence-box]')).toBeVisible();
  await expect(page.getByText('/total')).toBeVisible();
  await expect(page.getByRole('button', { name: '确认当前项' })).toBeDisabled();
  await expect(page.getByRole('button', { name: /全部确认/ })).toHaveCount(0);

  await page.getByLabel('Candidate 字段类型').selectOption('TEXT');
  await expect.poll(() => current.schemas[0]?.fields[0]?.value.kind).toBe('TEXT');
  await expect(page.getByText('已通过编辑解决')).toBeVisible();
  await expect(page.getByRole('button', { name: '确认当前项' })).toHaveCount(0);
  await page.getByRole('checkbox', { name: '启用最小长度' }).check();
  await page.getByLabel('最小长度', { exact: true }).fill('3');
  await expect.poll(() => current.schemas[0]?.fields[0]?.value.constraints.minLength).toBe('3');
  await expect(page.getByText('已保存', { exact: true })).toBeVisible();

  const addSchema = page.getByRole('button', { name: '新增', exact: true });
  await addSchema.focus();
  await page.keyboard.press('Enter');
  await page.getByLabel('显示名称', { exact: true }).fill('客户');
  await page.getByLabel('schemaKey', { exact: true }).fill('customer');
  await expect.poll(() => current.schemas.some((schema) => schema.proposedSchemaKey === 'customer')).toBe(true);

  await addUserField(page, 'name', '客户名称');
  await addUserField(page, 'email', '联系邮箱');
  const moveCurrentFieldUp = page.getByRole('button', { name: '上移当前字段' });
  await moveCurrentFieldUp.focus();
  await page.keyboard.press('Enter');
  await expect.poll(() => current.schemas.find((schema) => schema.proposedSchemaKey === 'customer')?.fields[0]?.proposedFieldKey).toBe('email');

  const moveCustomerUp = page.getByRole('button', { name: '上移 客户' });
  await moveCustomerUp.focus();
  await page.keyboard.press('Enter');
  await expect.poll(() => current.schemas[0]?.proposedSchemaKey).toBe('customer');
  await expect(page.locator('.bundle-schema-select strong').first()).toHaveText('客户');

  await page.locator('.bundle-schema-select').filter({ hasText: '订单' }).click();
  await page.getByRole('button', { name: '新增人工字段' }).click();
  await page.getByLabel('显示名称', { exact: true }).fill('客户信息');
  await page.getByLabel('fieldKey', { exact: true }).fill('customer');
  await page.getByLabel('Candidate 字段类型').selectOption('REFERENCE');
  await page.getByLabel('目标 Schema').selectOption({ label: '客户' });
  await expect.poll(() => current.schemas
    .find((schema) => schema.proposedSchemaKey === 'order')?.fields
    .some((field) => field.proposedFieldKey === 'customer' && field.value.kind === 'REFERENCE')).toBe(true);

  const search = page.getByPlaceholder('搜索字段');
  await search.focus();
  await page.keyboard.type('customer');
  await expect(page.locator('.candidate-field-row')).toHaveCount(1);
  await search.fill('');

  const mapButton = page.getByRole('button', { name: '树图' });
  await mapButton.focus();
  await page.keyboard.press('Enter');
  await expect(page.locator('.candidate-map-surface')).toBeVisible();
  await expect(page.getByText('Candidate 审核门已通过')).toBeVisible();
  await expect(page.getByText('已保存', { exact: true })).toBeVisible();

  await expectMinimumTarget(addSchema, 44);
  await expectMinimumTarget(mapButton, 44);
  await expectMinimumTarget(moveCustomerUp, 44);

  for (const width of [1260, 1200, 1181]) {
    await page.setViewportSize({ width, height: 768 });
    await expect(page.getByLabel('Candidate 属性与证据')).toBeVisible();
    await expect(page.getByRole('button', { name: '属性与证据' })).toHaveCount(0);
    await expectNoHorizontalOverflow(page);
  }
  await page.setViewportSize({ width: 1180, height: 768 });
  await expect(page.getByRole('button', { name: '属性与证据' })).toBeVisible();
  await expect(page.getByLabel('Candidate 属性与证据')).toHaveCount(0);
  await expectNoHorizontalOverflow(page);

  for (const viewport of [
    { width: 1440, height: 900 },
    { width: 1024, height: 768 },
    { width: 1280, height: 720 },
  ]) {
    await page.setViewportSize(viewport);
    await page.evaluate(() => window.scrollTo(0, 0));
    await expect(page.locator('.resource-body')).toBeVisible();
    if (viewport.width === 1024) {
      const formButton = page.getByRole('button', { name: '表单' });
      await formButton.focus();
      await page.keyboard.press('Enter');
      const inspectorTrigger = page.getByRole('button', { name: '属性与证据' });
      await inspectorTrigger.focus();
      await page.keyboard.press('Enter');
      const inspector = page.getByRole('dialog', { name: 'Candidate 属性与证据' });
      await expect(inspector).toBeVisible();
      await page.screenshot({ path: testInfo.outputPath('candidate-inspector-drawer-1024x768.png'), fullPage: true });
      await inspector.getByLabel('显示名称', { exact: true }).fill('客户关联');
      await expect.poll(() => current.schemas
        .find((schema) => schema.proposedSchemaKey === 'order')?.fields
        .some((field) => field.displayName === '客户关联')).toBe(true);
      const closeInspector = inspector.getByRole('button', { name: '关闭属性与证据' });
      await closeInspector.focus();
      await page.keyboard.press('Enter');
      await expect(inspector).toBeHidden();
      await expect(inspectorTrigger).toBeFocused();
      await mapButton.focus();
      await page.keyboard.press('Enter');
    }
    await expectNoHorizontalOverflow(page);
    await page.screenshot({
      path: testInfo.outputPath(`candidate-review-${viewport.width}x${viewport.height}.png`),
      fullPage: true,
    });
  }

  const accessibility = await new AxeBuilder({ page })
    .include('.resource-shell')
    .withTags(['wcag2a', 'wcag2aa', 'wcag21aa', 'wcag22aa'])
    .analyze();
  expect(accessibility.violations.filter((violation) =>
    violation.impact === 'serious' || violation.impact === 'critical')).toEqual([]);

  await page.getByRole('button', { name: '原子创建 2 个 Draft' }).click();
  await expect(page.getByText('任一 active key 或 tombstone 冲突：整包零写')).toBeVisible();
  await page.getByRole('button', { name: '确认原子创建' }).click();
  await expect(page.getByText('Draft Bundle 已原子创建')).toBeVisible();
  await expect(page.getByRole('link', { name: /order/ })).toHaveAttribute('href', '/schemas/order');
  await expect(page.getByRole('link', { name: /customer/ })).toHaveAttribute('href', '/schemas/customer');
  await expect(page.getByText('final Candidate 已冻结；本次操作没有发布、更新或删除任何既有 Schema。')).toBeVisible();
  await expect(page.getByRole('navigation', { name: '数据结构识别进度' }).locator('[aria-current="step"]')).toContainText('原子创建');
  await page.screenshot({ path: testInfo.outputPath('candidate-atomic-created-1280x720.png'), fullPage: true });
  expect(consoleErrors).toEqual([]);
});

test('keeps bounded visual diagnostics keyboard-accessible at 1024 without payload leakage', async ({ page }, testInfo) => {
  const failedRun: InferenceRunResponse = {
    ...runResponse(0, 'FAILED'),
    mode: 'IMAGE_ONLY',
    stage: 'HIERARCHY',
    sequence: 8,
    profileId: 'dashscope-qwen37-flash-20260715-product-v22-generic',
    sourceReference: 'repository-synthetic-transit-board-v3',
    failureCode: 'VISUAL_HIERARCHY_V2_RELATIONSHIP_SUPPORT_IDS_EMPTY',
  };
  const log: InferenceExecutionLogResponse = {
    run: failedRun,
    events: [
      { sequence: 1, type: 'QUEUED', state: 'QUEUED', stage: 'OBSERVE', occurredAt: '2026-08-10T00:00:00Z' },
      { sequence: 4, type: 'CHECKPOINT_ADVANCED', state: 'RUNNING', stage: 'HIERARCHY', occurredAt: '2026-08-10T00:00:08Z' },
      { sequence: 6, type: 'LEASE_RECLAIMED', state: 'RUNNING', stage: 'HIERARCHY', occurredAt: '2026-08-10T00:00:12Z' },
      { sequence: 8, type: 'FAILED', state: 'FAILED', stage: 'HIERARCHY', occurredAt: '2026-08-10T00:00:18Z' },
    ],
    attempts: [
      {
        attemptOrdinal: 0,
        stage: 'OBSERVE',
        status: 'REJECTED',
        outcomeCode: 'LIVE_VISUAL_ANALYSIS_REJECTED',
        providerModel: 'qwen3.7-flash',
        inputTokens: 2_300,
        outputTokens: 4_100,
        costMicrosCny: 2_715,
        durationMillis: 22_083,
        problemCodeCounts: { VISUAL_GROUNDING_PARENT_KIND_INVALID: 1 },
        completedAt: '2026-08-10T00:00:05Z',
      },
      {
        attemptOrdinal: 1,
        stage: 'OBSERVE',
        status: 'SUCCEEDED',
        outcomeCode: 'LIVE_VISUAL_GROUNDING_ACCEPTED',
        providerModel: 'qwen3.7-flash',
        inputTokens: 2_340,
        outputTokens: 4_220,
        costMicrosCny: 3_001,
        durationMillis: 24_012,
        problemCodeCounts: {},
        completedAt: '2026-08-10T00:00:08Z',
      },
      {
        attemptOrdinal: 2,
        stage: 'HIERARCHY',
        status: 'REJECTED',
        outcomeCode: 'LIVE_VISUAL_ANALYSIS_REJECTED',
        providerModel: 'qwen3.7-flash',
        inputTokens: 1_900,
        outputTokens: 2_800,
        costMicrosCny: 1_900,
        durationMillis: 16_000,
        problemCodeCounts: { VISUAL_HIERARCHY_V2_RELATIONSHIP_SUPPORT_IDS_EMPTY: 1 },
        completedAt: '2026-08-10T00:00:17Z',
      },
      {
        attemptOrdinal: 3,
        stage: 'HIERARCHY',
        status: 'SUCCEEDED',
        outcomeCode: 'LIVE_VISUAL_HIERARCHY_V2_ACCEPTED',
        providerModel: 'qwen3.7-flash-2026-07-15',
        inputTokens: 1_950,
        outputTokens: 2_400,
        costMicrosCny: 1_630,
        durationMillis: 15_200,
        problemCodeCounts: { VISUAL_HIERARCHY_RELATIONSHIP_SUPPORT_OWNER_NORMALIZED: 1 },
        completedAt: '2026-08-10T00:00:19Z',
      },
    ],
    truncated: false,
  };

  await page.route('**/api/v1/inference-runs/**', async (route) => {
    const url = new URL(route.request().url());
    if (route.request().method() === 'GET' && url.pathname === `/api/v1/inference-runs/${runId}/execution-log`) {
      await json(route, log);
    } else if (route.request().method() === 'GET' && url.pathname === `/api/v1/inference-runs/${runId}`) {
      await json(route, failedRun);
    } else {
      await route.abort('failed');
    }
  });

  await page.setViewportSize({ width: 1024, height: 768 });
  await page.goto(`/inference-runs/${runId}/monitor`);
  await page.waitForLoadState('networkidle');

  await expect(page.getByRole('heading', { name: '阶段与检查点' })).toBeVisible();
  await expect(page.getByRole('heading', { name: '有限问题定位' })).toBeVisible();
  await expect(page.getByText('区域树')).toBeVisible();
  await expect(page.getByText('VISUAL_GROUNDING_PARENT_KIND_INVALID').first()).toBeVisible();
  await expect(page.getByText('VISUAL_HIERARCHY_V2_RELATIONSHIP_SUPPORT_IDS_EMPTY').first()).toBeVisible();
  await expect(page.getByText('层级关系支撑 ID 列表不能为空').first()).toBeVisible();
  await expect(page.getByText('已按唯一容器区域 GROUP 归属归一化层级关系支撑').first()).toBeVisible();
  await expect(page.getByText('从检查点恢复后仍失败')).toBeVisible();
  await expect(page.getByText('raw-ocr-secret')).toHaveCount(0);
  await expect(page.getByText('provider-response-secret')).toHaveCount(0);

  const toggle = page.getByRole('button', { name: '收起' });
  await toggle.focus();
  await page.keyboard.press('Enter');
  await expect(page.locator('#inference-execution-log-body')).toHaveCount(0);
  await expect(page.getByRole('button', { name: '展开' })).toBeFocused();
  await page.keyboard.press('Enter');
  await expect(page.getByRole('heading', { name: '阶段与检查点' })).toBeVisible();
  await expectNoHorizontalOverflow(page);

  const accessibility = await new AxeBuilder({ page })
    .include('.resource-shell')
    .withTags(['wcag2a', 'wcag2aa', 'wcag21aa', 'wcag22aa'])
    .analyze();
  expect(accessibility.violations.filter((violation) =>
    violation.impact === 'serious' || violation.impact === 'critical')).toEqual([]);
  await page.screenshot({ path: testInfo.outputPath('visual-telemetry-1024x768.png'), fullPage: true });
});

test('preflights a local upload queue while the deployment transfer gate is closed', async ({ page }) => {
  let livePosts = 0;
  await page.route('**/api/v1/inference-runs**', async (route) => {
    const url = new URL(route.request().url());
    if (route.request().method() === 'GET' && url.pathname === '/api/v1/inference-runs/replay-fixtures') {
      await json(route, replayFixtures());
    } else if (route.request().method() === 'GET' && url.pathname === '/api/v1/inference-runs/live-availability') {
      await json(route, liveAvailability());
    } else if (route.request().method() === 'GET' && url.pathname === '/api/v1/inference-runs') {
      await json(route, { page: 1, size: 6, total: 0, items: [] });
    } else if (route.request().method() === 'POST' && url.pathname === '/api/v1/inference-runs/live') {
      livePosts += 1;
      await route.abort('blockedbyclient');
    } else {
      await route.abort('failed');
    }
  });

  await page.goto('/inference/new');
  await page.waitForLoadState('networkidle');
  const fileInputs = page.locator('.live-upload-field input[type="file"]');
  await fileInputs.nth(0).setInputFiles([
    { name: 'design.png', mimeType: 'image/png', buffer: Buffer.from('synthetic-png') },
    { name: 'notes.txt', mimeType: 'text/plain', buffer: Buffer.from('not-an-image') },
  ]);
  await fileInputs.nth(1).setInputFiles({
    name: 'sample.json',
    mimeType: 'application/json',
    buffer: Buffer.from('{"total":"12.00"}'),
  });

  await expect(page.getByText('design.png', { exact: true })).toBeVisible();
  await expect(page.getByText('sample.json', { exact: true })).toBeVisible();
  await expect(page.getByText('仅支持 PNG 或 JPEG。')).toBeVisible();
  await expect(page.getByText(/文件只保留在当前浏览器页面/)).toBeVisible();
  await expect(page.getByRole('button', { name: '排队识别并查看监控' })).toBeDisabled();
  await page.getByRole('button', { name: '移除文件 notes.txt' }).click();
  await expect(page.getByText('notes.txt', { exact: true })).toHaveCount(0);
  await page.getByRole('tab', { name: '仅 JSON' }).click();
  await expect(page.getByText('非当前模式文件仅在本页保留，本次不会发送；切回对应模式后可继续使用。')).toBeVisible();
  await expect(page.locator('.fixture-metrics div').filter({ hasText: '本次文件' })).toContainText('1');
  await expect(page.getByText('design.png', { exact: true })).toBeVisible();
  await page.getByRole('tab', { name: '图片 + JSON' }).click();
  await expect(page.locator('.fixture-metrics div').filter({ hasText: '本次文件' })).toContainText('2');
  expect(livePosts).toBe(0);
});

test('offers four product models and an optional cumulative run cost ceiling', async ({ page }) => {
  let livePosts = 0;
  let multipartBody = '';
  await page.route('**/api/v1/inference-runs**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    if (request.method() === 'GET' && url.pathname === '/api/v1/inference-runs/replay-fixtures') {
      await json(route, replayFixtures());
    } else if (request.method() === 'GET' && url.pathname === '/api/v1/inference-runs/live-availability') {
      await json(route, liveAvailability(true));
    } else if (request.method() === 'GET' && url.pathname === '/api/v1/inference-runs') {
      await json(route, { page: 1, size: 6, total: 0, items: [] });
    } else if (request.method() === 'POST' && url.pathname === '/api/v1/inference-runs/live') {
      livePosts += 1;
      multipartBody = request.postDataBuffer()?.toString('utf8') ?? '';
      await json(route, {
        ...runResponse(0, 'QUEUED'),
        profileId: 'dashscope-qwen37-flash-product-v4',
        sourceReference: 'user-upload',
        costLimitMicrosCny: 250000,
      }, 201);
    } else {
      await route.abort('failed');
    }
  });

  await page.goto('/inference/new');
  await expect(page.getByText('可用', { exact: true })).toBeVisible();
  await expect(page.locator('.live-profile-grid button')).toHaveCount(4);
  for (const model of ['qwen3.7-flash', 'qwen3.7-plus', 'qwen3.8-max', 'qwen3.7-max-2026-06-08']) {
    await expect(page.locator('.live-profile-grid button').filter({ hasText: model })).toHaveCount(1);
  }

  await page.getByRole('tab', { name: '仅 JSON' }).click();
  await page.locator('.live-upload-field input[type="file"]').nth(1).setInputFiles({
    name: 'sample.json',
    mimeType: 'application/json',
    buffer: Buffer.from('{"title":"示例"}'),
  });
  await page.getByRole('checkbox', { name: /设置本次任务成本上限/ }).check();
  const costInput = page.getByRole('spinbutton', { name: '本次任务成本上限' });
  await costInput.fill('0');
  await expect(page.getByRole('button', { name: '排队识别并查看监控' })).toBeDisabled();
  await costInput.fill('0.25');
  await page.getByRole('checkbox', { name: /确认数据可外发/ }).check();
  await page.getByRole('checkbox', { name: /接受实验配置/ }).check();
  await expect(page.getByRole('button', { name: '排队识别并查看监控' })).toBeEnabled();
  await page.getByRole('button', { name: '排队识别并查看监控' }).click();

  await expect.poll(() => livePosts).toBe(1);
  expect(multipartBody).toContain('"inputClassification":"USER_PROVIDED"');
  expect(multipartBody).toContain('"costLimitMicrosCny":250000');
});

test('requires confirmation to cancel and creates a new auditable retry run', async ({ page }) => {
  let run = runResponse(0, 'QUEUED');
  let cancelCalls = 0;
  let retryCalls = 0;
  await page.route('**/api/v1/inference-runs/**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    if (request.method() === 'GET' && url.pathname === `/api/v1/inference-runs/${runId}`) {
      await json(route, run);
    } else if (request.method() === 'GET' && url.pathname === `/api/v1/inference-runs/${runId}/execution-log`) {
      await json(route, executionLog(run));
    } else if (request.method() === 'POST' && url.pathname === `/api/v1/inference-runs/${runId}/cancel`) {
      cancelCalls += 1;
      run = { ...run, state: 'CANCELLED', finishedAt: '2026-08-10T00:00:03Z' };
      await json(route, run);
    } else if (request.method() === 'POST' && url.pathname === `/api/v1/inference-runs/${runId}/retries`) {
      retryCalls += 1;
      expect(request.headers()['idempotency-key']).toBeTruthy();
      await json(route, { ...runResponse(0, 'QUEUED', retryRunId), retryOfRunId: runId }, 201);
    } else if (request.method() === 'GET' && url.pathname === `/api/v1/inference-runs/${retryRunId}`) {
      await json(route, { ...runResponse(0, 'QUEUED', retryRunId), retryOfRunId: runId });
    } else if (request.method() === 'GET' && url.pathname === `/api/v1/inference-runs/${retryRunId}/execution-log`) {
      await json(route, executionLog({ ...runResponse(0, 'QUEUED', retryRunId), retryOfRunId: runId }));
    } else {
      await route.abort('failed');
    }
  });

  await page.goto(`/inference-runs/${runId}/monitor`);
  await page.waitForLoadState('networkidle');
  const inferenceBreadcrumbBox = await page.locator('.resource-breadcrumb').boundingBox();
  expect(inferenceBreadcrumbBox).not.toBeNull();
  expect(inferenceBreadcrumbBox!.x).toBeLessThan(420);
  const cancel = page.getByRole('button', { name: '取消任务' });
  await cancel.focus();
  await page.keyboard.press('Enter');
  expect(cancelCalls).toBe(0);
  await page.getByRole('button', { name: '确认取消' }).click();
  await expect.poll(() => cancelCalls).toBe(1);
  await expect(page.getByText('识别任务未生成 Candidate')).toBeVisible({ timeout: 10_000 });

  const retry = page.getByRole('button', { name: '重新运行' });
  await retry.focus();
  await page.keyboard.press('Enter');
  await expect(page).toHaveURL(new RegExp(`/inference-runs/${retryRunId}/monitor$`));
  expect(retryCalls).toBe(1);
});

test('shows cooperative cancellation acceptance while the current provider call finishes', async ({ page }) => {
  let run = runResponse(0, 'RUNNING');
  let cancelCalls = 0;
  await page.route('**/api/v1/inference-runs/**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    if (request.method() === 'GET' && url.pathname === `/api/v1/inference-runs/${runId}`) {
      await json(route, run);
    } else if (request.method() === 'GET' && url.pathname === `/api/v1/inference-runs/${runId}/execution-log`) {
      await json(route, executionLog(run));
    } else if (request.method() === 'POST' && url.pathname === `/api/v1/inference-runs/${runId}/cancel`) {
      cancelCalls += 1;
      run = { ...run, cancellationRequested: true, sequence: run.sequence + 1 };
      await json(route, run);
    } else {
      await route.abort('failed');
    }
  });

  await page.goto(`/inference-runs/${runId}/monitor`);
  await page.getByRole('button', { name: '取消任务' }).click();
  await page.getByRole('button', { name: '确认取消' }).click();

  await expect.poll(() => cancelCalls).toBe(1);
  await expect(page.getByText('取消请求已受理')).toBeVisible();
  await expect(page.getByText(/等待当前模型调用结束/)).toBeVisible();
  await expect(page.getByRole('button', { name: '取消任务' })).toHaveCount(0);
});

test('keeps a failed autosave locally and lets the user retry without losing edits', async ({ page }) => {
  let server = candidateBundle();
  let revision = 0;
  let saveAttempts = 0;
  await page.route('**/api/v1/inference-runs/**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    if (request.method() === 'GET' && url.pathname.endsWith('/events')) {
      await route.fulfill({ status: 200, contentType: 'text/event-stream', body: 'retry: 60000\n\n' });
    } else if (request.method() === 'GET' && artifactIds.some((artifactId) => url.pathname.endsWith(`/artifacts/${artifactId}`))) {
      await route.fulfill({ status: 200, contentType: 'image/svg+xml', body: evidenceSvg() });
    } else if (request.method() === 'GET' && url.pathname === `/api/v1/inference-runs/${runId}`) {
      await json(route, runResponse(revision, 'REVIEW_REQUIRED'));
    } else if (request.method() === 'GET' && url.pathname === `/api/v1/inference-runs/${runId}/execution-log`) {
      await json(route, executionLog(runResponse(revision, 'REVIEW_REQUIRED')));
    } else if (request.method() === 'GET' && url.pathname === `/api/v1/inference-runs/${runId}/candidate`) {
      await json(route, reviewResponse(server, revision, problemsFor(server), false));
    } else if (request.method() === 'PUT' && url.pathname === `/api/v1/inference-runs/${runId}/candidate`) {
      saveAttempts += 1;
      const body = request.postDataJSON() as { expectedCandidateRevision: number; candidate: CandidateBundle };
      expect(body.expectedCandidateRevision).toBe(0);
      expect(body.candidate.schemas[0]?.fields[0]?.value.kind).toBe('TEXT');
      if (saveAttempts === 1) {
        await json(route, {
          type: 'about:blank',
          title: 'Candidate revision conflict',
          status: 409,
          detail: 'The Candidate changed before this save.',
          code: 'CANDIDATE_REVISION_CONFLICT',
          traceId: 'browser-conflict',
          revision: 0,
        }, 409);
      } else {
        server = body.candidate;
        revision = 1;
        await json(route, reviewResponse(server, revision, [], false));
      }
    } else {
      await route.abort('failed');
    }
  });

  await page.goto(`/inference-runs/${runId}/review`);
  await page.waitForLoadState('networkidle');
  await page.locator('.candidate-field-row').filter({ hasText: 'total' }).click();
  await page.getByLabel('Candidate 字段类型').selectOption('TEXT');
  await expect(page.getByText('本地修改尚未保存')).toBeVisible();
  await expect(page.getByText(/本地修改仍保留/)).toBeVisible();
  expect(saveAttempts).toBe(1);

  await page.getByRole('button', { name: '重试' }).click();
  await expect.poll(() => saveAttempts).toBe(2);
  await expect(page.getByText('已保存', { exact: true })).toBeVisible();
  await expect(page.getByText('Candidate 审核门已通过')).toBeVisible();
  expect(server.schemas[0]?.fields[0]?.value.kind).toBe('TEXT');
});

async function addUserField(page: Page, fieldKey: string, displayName: string) {
  const addField = page.getByRole('button', { name: '新增人工字段' });
  await addField.focus();
  await page.keyboard.press('Enter');
  await page.getByLabel('显示名称', { exact: true }).fill(displayName);
  await page.getByLabel('fieldKey', { exact: true }).fill(fieldKey);
}

function candidateBundle(): CandidateBundle {
  return {
    contractVersion: 'renderweave-candidate/1.0',
    rootCandidateSchemaId: schemaId,
    schemas: [{
      candidateSchemaId: schemaId,
      proposedSchemaKey: 'order',
      displayName: '订单',
      source: 'AI',
      assessment: {
        confidenceBps: 9200,
        inferred: true,
        resolution: 'NOT_REQUIRED',
        evidence: [{ kind: 'JSON', artifactId: null, boundingBox: null, sampleIndex: 0, jsonPointer: '' }],
      },
      fields: [{
        candidateFieldId: fieldId,
        proposedFieldKey: 'total',
        displayName: '金额',
        required: false,
        value: { kind: 'UNRESOLVED', items: null, reference: null, observedKinds: ['DECIMAL'], constraints: {} },
        source: 'AI',
        assessment: {
          confidenceBps: 4200,
          inferred: true,
          resolution: 'UNRESOLVED',
          evidence: [
            { kind: 'IMAGE', artifactId: artifactIds[0], boundingBox: { left: 1200, top: 2300, right: 6000, bottom: 4100 }, sampleIndex: null, jsonPointer: null },
            { kind: 'IMAGE', artifactId: artifactIds[1], boundingBox: { left: 2000, top: 1800, right: 7200, bottom: 3700 }, sampleIndex: null, jsonPointer: null },
            { kind: 'JSON', artifactId: null, boundingBox: null, sampleIndex: 0, jsonPointer: '/total' },
          ],
        },
      }],
    }],
  };
}

function problemsFor(candidate: CandidateBundle): CandidateProblem[] {
  const field = candidate.schemas
    .flatMap((schema) => schema.fields)
    .find((item) => item.candidateFieldId === fieldId);
  if (!field || (field.value.kind !== 'UNRESOLVED' && field.value.kind !== 'CONFLICT')
    && field.assessment.resolution !== 'UNRESOLVED') return [];
  return [{
    code: 'LOW_CONFIDENCE_UNRESOLVED',
    severity: 'BLOCKER',
    itemId: fieldId,
    pointer: '/schemas/0/fields/0/assessment/resolution',
    args: { confidenceBps: '4200' },
  }];
}

function reviewResponse(
  current: CandidateBundle,
  candidateRevision: number,
  problems: CandidateProblem[],
  applied: boolean,
) {
  return {
    run: runResponse(candidateRevision, applied ? 'COMPLETED' : 'REVIEW_REQUIRED'),
    candidateRevision,
    original: candidateBundle(),
    current,
    problems,
    finalCandidate: applied ? current : null,
    appliedAt: applied ? '2026-08-10T00:00:02Z' : null,
    images: artifactIds.map((artifactId, ordinal) => ({
      artifactId,
      ordinal,
      width: 1200,
      height: 800,
      contentUrl: `/api/v1/inference-runs/${runId}/artifacts/${artifactId}`,
    })),
    jsonSampleCount: 1,
  };
}

function runResponse(
  candidateRevision: number,
  state: InferenceRunState,
  id = runId,
  sourceReference = 'combined-08-low-information',
): InferenceRunResponse {
  const stage = state === 'COMPLETED'
    ? 'ATOMIC_CREATE'
    : state === 'REVIEW_REQUIRED'
      ? 'USER_APPROVAL'
      : state === 'FAILED'
        ? 'REPAIR'
        : 'NORMALIZE';
  return {
    runId: id,
    mode: sourceReference.startsWith('json-') ? 'JSON_ONLY' : 'COMBINED',
    state,
    stage,
    sequence: 7 + candidateRevision + (state === 'COMPLETED' ? 2 : 0),
    profileId: 'replay-v1',
    sourceReference,
    costLimitMicrosCny: null,
    cancellationRequested: false,
    retryOfRunId: null,
    failureCode: state === 'FAILED' ? 'LIVE_REPAIR_BUDGET_EXHAUSTED' : null,
    candidateRevision: state === 'QUEUED' || state === 'RUNNING' ? null : candidateRevision,
    createdAt: '2026-08-10T00:00:00Z',
    updatedAt: state === 'COMPLETED' ? '2026-08-10T00:00:02Z' : '2026-08-10T00:00:01Z',
    finishedAt: state === 'COMPLETED' || state === 'FAILED' || state === 'CANCELLED'
      ? '2026-08-10T00:00:02Z'
      : null,
  };
}

function executionLog(run: InferenceRunResponse) {
  return {
    run,
    events: [
      { sequence: 1, type: 'QUEUED', state: 'QUEUED', stage: 'OBSERVE', occurredAt: '2026-08-10T00:00:00Z' },
      { sequence: run.sequence, type: run.state === 'COMPLETED' ? 'CANDIDATE_APPLIED' : run.state === 'CANCELLED' ? 'CANCELLED' : 'REVIEW_REQUIRED', state: run.state, stage: run.stage, occurredAt: run.updatedAt },
    ],
    attempts: [{
      attemptOrdinal: 0,
      stage: 'STRUCTURE',
      status: 'SUCCEEDED',
      outcomeCode: 'REPLAY_OUTPUT_ACCEPTED',
      providerModel: null,
      inputTokens: 0,
      outputTokens: 0,
      costMicrosCny: 0,
      durationMillis: 12,
      problemCodeCounts: { LOW_CONFIDENCE_UNRESOLVED: 1 },
      completedAt: '2026-08-10T00:00:00.500Z',
    }],
    truncated: false,
  };
}

function activeSchemaKeys(candidate: CandidateBundle) {
  return candidate.schemas
    .filter((schema) => schema.assessment.resolution !== 'REMOVED')
    .map((schema) => schema.proposedSchemaKey)
    .filter((schemaKey): schemaKey is string => Boolean(schemaKey));
}

function replayFixtures() {
  return {
    profileId: 'replay-v1',
    provider: 'REPLAY',
    networkAllowed: false,
    certification: 'REPLAY_ONLY',
    items: [{
      fixtureId: 'combined-08-low-information',
      mode: 'COMBINED',
      scenario: 'low-information',
      imageCount: 2,
      jsonSampleCount: 1,
      expectedSchemaCount: 1,
      expectedProblemCodes: ['LOW_CONFIDENCE_UNRESOLVED'],
    }],
  };
}

function liveAvailability(enabled = false) {
  const profile = (
    profileId: string,
    model: string,
    supportedModes: Array<'IMAGE_ONLY' | 'JSON_ONLY' | 'COMBINED'>,
  ) => ({
    profileId,
    provider: 'DASHSCOPE',
    model,
    certification: 'EXPERIMENTAL',
    supportedModes,
    maximumTotalCalls: 5,
    maximumOutputTokens: 4096,
    maximumEstimatedCostMicrosCny: 2000000,
    pricingEffectiveDate: '2026-08-10',
  });
  return {
    enabled,
    configured: enabled,
    uploadEnabled: enabled,
    inputClassification: 'USER_PROVIDED',
    runCostLimitRequired: false,
    maximumRunCostLimitMicrosCny: 100000000,
    profiles: [
      profile('dashscope-qwen37-flash-product-v4', 'qwen3.7-flash', ['IMAGE_ONLY', 'JSON_ONLY', 'COMBINED']),
      profile('dashscope-qwen37-plus-product-v4', 'qwen3.7-plus', ['IMAGE_ONLY', 'JSON_ONLY', 'COMBINED']),
      profile('dashscope-qwen38-max-product-v4', 'qwen3.8-max', ['IMAGE_ONLY', 'JSON_ONLY', 'COMBINED']),
      profile('dashscope-qwen37-max-20260608-product-v4', 'qwen3.7-max-2026-06-08', ['IMAGE_ONLY', 'JSON_ONLY', 'COMBINED']),
    ],
  };
}

function evidenceSvg() {
  return '<svg xmlns="http://www.w3.org/2000/svg" width="1200" height="800"><rect width="1200" height="800" fill="#f7f2e9"/><text x="80" y="120">Synthetic evidence</text></svg>';
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

async function expectMinimumTarget(locator: Locator, minimum: number) {
  const box = await locator.boundingBox();
  expect(box).not.toBeNull();
  expect(box!.height).toBeGreaterThanOrEqual(minimum);
  expect(box!.width).toBeGreaterThanOrEqual(minimum);
}
