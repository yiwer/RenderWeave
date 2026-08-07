import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Page, type Route } from '@playwright/test';

const runId = '44444444-4444-4444-8444-444444444444';
const schemaId = '11111111-1111-4111-8111-111111111111';
const fieldId = '22222222-2222-4222-8222-222222222222';
const artifactId = '33333333-3333-4333-8333-333333333333';

test('launches zero-network replay and reviews one evidence-backed Candidate item', async ({ page }, testInfo) => {
  let current = candidateBundle('UNRESOLVED', 'UNRESOLVED');
  let revision = 0;
  let problems = [candidateProblem()];
  let applied = false;
  await page.route('**/api/v1/inference-runs/**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    if (request.method() === 'GET' && url.pathname.endsWith('/events')) {
      await route.fulfill({ status: 200, contentType: 'text/event-stream', body: 'retry: 60000\n\n' });
    } else if (request.method() === 'GET' && url.pathname.endsWith(`/artifacts/${artifactId}`)) {
      await route.fulfill({ status: 200, contentType: 'image/svg+xml', body: evidenceSvg() });
    } else if (request.method() === 'GET' && url.pathname === `/api/v1/inference-runs/${runId}/candidate`) {
      await json(route, reviewResponse(current, revision, problems, applied));
    } else if (request.method() === 'PUT' && url.pathname === `/api/v1/inference-runs/${runId}/candidate`) {
      const body = JSON.parse(request.postData() ?? '{}') as { expectedCandidateRevision: number; candidate: ReturnType<typeof candidateBundle> };
      expect(body.expectedCandidateRevision).toBe(revision);
      expect(body.candidate.schemas[0]!.fields[0]!.assessment.resolution).toBe('RESOLVED_BY_EDIT');
      expect(body.candidate.schemas[0]!.fields[0]!.assessment.confidenceBps).toBe(4200);
      expect(body.candidate.schemas[0]!.fields[0]!.assessment.evidence).toHaveLength(2);
      current = body.candidate;
      revision += 1;
      problems = [];
      await json(route, reviewResponse(current, revision, problems, applied));
    } else if (request.method() === 'POST' && url.pathname === `/api/v1/inference-runs/${runId}/apply`) {
      expect(request.postDataJSON()).toEqual({ expectedCandidateRevision: revision });
      applied = true;
      await json(route, {
        run: runResponse(revision, true), candidateRevision: revision, rootSchemaKey: 'order',
        createdDrafts: [{ schemaKey: 'order', revision: 0, href: '/api/v1/schema-drafts/order' }],
        appliedAt: '2026-08-08T00:00:02Z',
      });
    } else {
      await route.abort('failed');
    }
  });
  await page.route('**/api/v1/inference-runs', async (route) => {
    expect(route.request().headers()['idempotency-key']).toBeTruthy();
    expect(route.request().postDataJSON()).toEqual({ fixtureId: 'combined-08-low-information', externalTransferConfirmed: true });
    await json(route, runResponse(revision, false), 201);
  });
  await page.route('**/api/v1/inference-runs/replay-fixtures', async (route) => {
    await json(route, {
      profileId: 'replay-v1', provider: 'REPLAY', networkAllowed: false, certification: 'REPLAY_ONLY',
      items: [{
        fixtureId: 'combined-08-low-information', mode: 'COMBINED', scenario: 'low-information',
        imageCount: 1, jsonSampleCount: 1, expectedSchemaCount: 1,
        expectedProblemCodes: ['LOW_CONFIDENCE_UNRESOLVED'],
      }],
    });
  });

  await page.goto('/inference');
  await expect(page.getByRole('heading', { name: '从合成样本生成 Schema Candidate' })).toBeVisible();
  await expect(page.getByText('本页不上传真实图片或业务数据，也不会调用 live AI/provider。')).toBeVisible();
  await page.getByRole('checkbox').check();
  await page.getByRole('button', { name: '运行并进入审核' }).click();

  await expect(page).toHaveURL(new RegExp(`/inference-runs/${runId}/review$`));
  await expect(page.getByRole('heading', { name: '逐项审核 AI Schema Candidate' })).toBeVisible();
  await expect(page.getByText('1 个 blocker 阻止落库')).toBeVisible();
  await page.getByRole('button', { name: /金额 total/ }).click();
  await expect(page.locator('[data-evidence-box]')).toBeVisible();
  await expect(page.getByText('/total')).toBeVisible();
  await expect(page.getByRole('button', { name: '确认当前项' })).toBeDisabled();
  await expect(page.getByRole('button', { name: /全部确认/ })).toHaveCount(0);

  await page.getByLabel('Candidate 字段类型').selectOption('TEXT');
  await expect(page.getByText('Candidate 审核门已通过')).toBeVisible();
  await expect(page.getByText('已保存', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: '树图' }).click();
  await expect(page.locator('.candidate-map-surface')).toBeVisible();
  await page.getByRole('button', { name: '原子创建 1 个 Draft' }).click();
  await expect(page.getByText('任一 active key 或 tombstone 冲突：整包零写')).toBeVisible();
  await page.getByRole('button', { name: '确认原子创建' }).click();
  await expect(page.getByText('Draft Bundle 已原子创建')).toBeVisible();
  await expect(page.getByRole('link', { name: /order/ })).toHaveAttribute('href', '/schemas/order');
  await expect(page.getByText('final Candidate 已冻结；本次操作没有发布、更新或删除任何既有 Schema。')).toBeVisible();
  await page.screenshot({ path: testInfo.outputPath('candidate-atomic-created-1280x720.png'), fullPage: true });

  const accessibility = await new AxeBuilder({ page })
    .include('.resource-shell')
    .withTags(['wcag2a', 'wcag2aa', 'wcag21aa', 'wcag22aa'])
    .analyze();
  expect(accessibility.violations.filter((violation) =>
    violation.impact === 'serious' || violation.impact === 'critical')).toEqual([]);
  await expectNoHorizontalOverflow(page);
  await page.setViewportSize({ width: 1024, height: 768 });
  await expect(page.locator('.resource-body')).toBeVisible();
  await expectNoHorizontalOverflow(page);
});

function candidateBundle(kind: 'UNRESOLVED' | 'TEXT', resolution: 'UNRESOLVED' | 'RESOLVED_BY_EDIT') {
  return {
    contractVersion: 'renderweave-candidate/1.0' as const,
    rootCandidateSchemaId: schemaId,
    schemas: [{
      candidateSchemaId: schemaId, proposedSchemaKey: 'order', displayName: '订单', source: 'AI' as const,
      assessment: { confidenceBps: 9200, inferred: true, resolution: 'NOT_REQUIRED' as const, evidence: [{ kind: 'JSON' as const, artifactId: null, boundingBox: null, sampleIndex: 0, jsonPointer: '' }] },
      fields: [{
        candidateFieldId: fieldId, proposedFieldKey: 'total', displayName: '金额', required: false,
        value: { kind, items: null, reference: null, observedKinds: kind === 'UNRESOLVED' ? ['DECIMAL'] : [], constraints: {} },
        source: 'AI' as const,
        assessment: {
          confidenceBps: 4200, inferred: true, resolution,
          evidence: [
            { kind: 'IMAGE' as const, artifactId, boundingBox: { left: 1200, top: 2300, right: 6000, bottom: 4100 }, sampleIndex: null, jsonPointer: null },
            { kind: 'JSON' as const, artifactId: null, boundingBox: null, sampleIndex: 0, jsonPointer: '/total' },
          ],
        },
      }],
    }],
  };
}

function candidateProblem() {
  return { code: 'LOW_CONFIDENCE_UNRESOLVED', severity: 'BLOCKER' as const, itemId: fieldId, pointer: '/schemas/0/fields/0/assessment/resolution', args: { confidenceBps: '4200' } };
}

function reviewResponse(current: ReturnType<typeof candidateBundle>, candidateRevision: number, currentProblems: ReturnType<typeof candidateProblem>[], applied: boolean) {
  return {
    run: runResponse(candidateRevision, applied), candidateRevision,
    original: candidateBundle('UNRESOLVED', 'UNRESOLVED'), current, problems: currentProblems,
    finalCandidate: applied ? current : null,
    appliedAt: applied ? '2026-08-08T00:00:02Z' : null,
    images: [{ artifactId, ordinal: 0, width: 1200, height: 800, contentUrl: `/api/v1/inference-runs/${runId}/artifacts/${artifactId}` }],
    jsonSampleCount: 1,
  };
}

function runResponse(candidateRevision: number, applied: boolean) {
  return {
    runId, mode: 'COMBINED', state: applied ? 'COMPLETED' : 'REVIEW_REQUIRED', stage: applied ? 'ATOMIC_CREATE' : 'USER_APPROVAL', sequence: 7 + candidateRevision + (applied ? 2 : 0),
    profileId: 'replay-v1', replayFixtureId: 'combined-08-low-information', cancellationRequested: false,
    retryOfRunId: null, failureCode: null, candidateRevision,
    createdAt: '2026-08-08T00:00:00Z', updatedAt: applied ? '2026-08-08T00:00:02Z' : '2026-08-08T00:00:01Z',
    finishedAt: applied ? '2026-08-08T00:00:02Z' : null,
  };
}

function evidenceSvg() {
  return '<svg xmlns="http://www.w3.org/2000/svg" width="1200" height="800"><rect width="1200" height="800" fill="#f7f2e9"/><text x="80" y="120">Synthetic evidence</text></svg>';
}

async function json(route: Route, body: unknown, status = 200) {
  await route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
}

async function expectNoHorizontalOverflow(page: Page) {
  const dimensions = await page.evaluate(() => ({ clientWidth: document.documentElement.clientWidth, scrollWidth: document.documentElement.scrollWidth }));
  expect(dimensions.scrollWidth).toBeLessThanOrEqual(dimensions.clientWidth);
}
