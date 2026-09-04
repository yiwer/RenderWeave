import AxeBuilder from '@axe-core/playwright';
import { createHash } from 'node:crypto';
import { mkdirSync, writeFileSync } from 'node:fs';
import path from 'node:path';

import {
  expect,
  test,
  type APIRequestContext,
  type Locator,
  type Page,
  type Response,
} from '@playwright/test';

const LIVE = process.env.RENDERWEAVE_TEMPLATE_CANDIDATE_LIVE === '1';
const EVIDENCE_DIR = process.env.RENDERWEAVE_EVIDENCE_DIR;
const DESIGN_MEDIA_TYPE = 'application/vnd.renderweave.design+json';
const CANVAS_WIDTH_MM = 101.6;
const CANVAS_HEIGHT_MM = 76.2;

interface TemplateCurrentBody {
  templateId: string;
  revision: number;
  contentHash: string;
  readiness: string;
  designDsl: Record<string, unknown>;
}

interface CandidateCompositionSetup {
  schemaKey: string;
  child: TemplateCurrentBody;
  childDisplayName: string;
}

interface PixelProbe {
  x: number;
  y: number;
  rgba: [number, number, number, number];
}

declare global {
  interface Window {
    __renderWeaveCandidatePendingSeen?: boolean;
    __renderWeaveCandidatePendingObserver?: MutationObserver;
  }
}

test.describe('local Template Candidate Preview', () => {
  test.skip(!LIVE, 'requires the explicit local Candidate Preview canary environment');

  test('authors data-bound composition and verifies two real inputs while Authoritative Preview stays closed', async ({ page }) => {
    test.setTimeout(180_000);
    const browserErrors = captureBrowserErrors(page);
    await page.setViewportSize({ width: 1440, height: 900 });

    const setup = await createCandidateCompositionSetup(page.request);
    const child = await authorBoundChild(page, setup);
    expect(child).toMatchObject({ revision: 1, readiness: 'READY' });
    expectBoundChild(child.designDsl);
    const parent = await createTemplateWithSchema(
      page.request,
      setup.schemaKey,
      candidateDesign(`Candidate composition ${Date.now()}`, '98000000-0000-4000-8000-000000000001'),
    );
    const displayName = String(parent.designDsl.displayName);
    await page.goto(`/templates/${parent.templateId}?candidatePreview=local`, {
      waitUntil: 'domcontentloaded',
    });
    await expect(page.getByRole('main', { name: 'Template 编辑工作区' })).toBeVisible();
    const authored = await authorCandidateComposition(page, setup.childDisplayName);

    const saveResponsePromise = page.waitForResponse(templateSaveResponse(parent.templateId, 200));
    await page.getByRole('button', { name: '保存 canonical 本地草稿' }).click();
    const saved = await (await saveResponsePromise).json() as TemplateCurrentBody;
    expect(saved).toMatchObject({ revision: 1, readiness: 'READY' });
    expectCandidateComposition(saved.designDsl, authored, setup.child.templateId);
    await expect(page.getByText('revision 1')).toBeVisible();
    await expect(page.getByText('Canonical current')).toBeVisible();

    await page.getByRole('button', { name: '打开候选预览（NOT_CERTIFIED）' }).click();
    await expect(page.getByRole('heading', { name: '候选预览' })).toBeVisible();
    await expect(page.getByText('NOT_CERTIFIED', { exact: true })).toBeVisible();

    const inputA = JSON.stringify({
      rootDocument: { barWidth: 12.7, tags: ['one'], showDetails: false },
    });
    const inputB = JSON.stringify({
      rootDocument: { barWidth: 38.1, tags: ['one', 'two', 'three'], showDetails: true },
    });
    const inputEditor = page.getByRole('textbox', { name: 'RenderInput JSON' });
    await inputEditor.fill(inputA);
    await installPendingObserver(page);

    const pngResponse = page.waitForResponse(candidateResponse('PNG'));
    await page.getByRole('button', { name: '生成候选预览（NOT_CERTIFIED）' }).click();
    const pngA = await verifyCandidateResponse(await pngResponse, 'PNG', inputA);
    const candidateImage = page.getByRole('img', {
      name: `${displayName}的候选预览（NOT_CERTIFIED）`,
    });
    await expect(candidateImage).toBeVisible();
    await expect(page.getByText('NOT_CERTIFIED · 完整结果已核验')).toBeVisible();
    expect(await candidatePendingWasSeen(page)).toBe(true);

    const visualA = await inspectCandidatePixels(candidateImage, {
      repeatThird: { x: 63.5, y: 12.7 },
      conditional: { x: 12.7, y: 38.1 },
      childAnchor: { x: 57.15, y: 38.1 },
      boundChildTail: { x: 69.85, y: 38.1 },
    });
    expect(visualA.repeatThird.rgba).toEqual([255, 255, 255, 255]);
    expect(visualA.conditional.rgba).toEqual([255, 255, 255, 255]);
    expect(visualA.childAnchor.rgba).toEqual([37, 99, 235, 255]);
    expect(visualA.boundChildTail.rgba).toEqual([255, 255, 255, 255]);

    await inputEditor.fill(inputB);
    await expect(candidateImage).toHaveCount(0);
    await installPendingObserver(page);
    const secondPngResponse = page.waitForResponse(candidateResponse('PNG'));
    await page.getByRole('button', { name: '生成候选预览（NOT_CERTIFIED）' }).click();
    const pngB = await verifyCandidateResponse(await secondPngResponse, 'PNG', inputB);
    await expect(candidateImage).toBeVisible();
    expect(await candidatePendingWasSeen(page)).toBe(true);
    expect(pngB.contentDigest).not.toBe(pngA.contentDigest);

    const visualB = await inspectCandidatePixels(candidateImage, {
      repeatThird: { x: 63.5, y: 12.7 },
      conditional: { x: 12.7, y: 38.1 },
      childAnchor: { x: 57.15, y: 38.1 },
      boundChildTail: { x: 69.85, y: 38.1 },
    });
    expect(visualB.repeatThird.rgba).toEqual([22, 163, 74, 255]);
    expect(visualB.conditional.rgba).toEqual([220, 38, 38, 255]);
    expect(visualB.childAnchor.rgba).toEqual([37, 99, 235, 255]);
    expect(visualB.boundChildTail.rgba).toEqual([37, 99, 235, 255]);

    await page.getByRole('combobox', { name: '输出格式' }).selectOption('JPEG');
    await expect(page.getByRole('img')).toHaveCount(0);
    await installPendingObserver(page);
    const jpegResponse = page.waitForResponse(candidateResponse('JPEG'));
    await page.getByRole('button', { name: '生成候选预览（NOT_CERTIFIED）' }).click();
    const jpeg = await verifyCandidateResponse(await jpegResponse, 'JPEG', inputB);
    await expect(candidateImage).toBeVisible();
    await expect(page.getByText('Quality', { exact: true }).locator('..')).toContainText('90');
    expect(await candidatePendingWasSeen(page)).toBe(true);

    await expectNoSeriousOrCriticalAxe(page, '.template-editor-root');
    expect(browserErrors).toEqual([]);

    await page.goto(`/templates/${parent.templateId}`, { waitUntil: 'domcontentloaded' });
    await page.getByRole('button', { name: '打开权威预览' }).click();
    const authoritativeResponse = page.waitForResponse((response) => {
      const url = new URL(response.url());
      return response.request().method() === 'POST'
        && url.pathname === `/api/v1/templates/${parent.templateId}/authoritative-preview`;
    });
    await page.getByRole('button', { name: '生成权威预览' }).click();
    const authoritative = await authoritativeResponse;
    expect(authoritative.status()).toBe(503);
    expect(authoritative.headers()['renderweave-candidate-status']).toBeUndefined();
    const authoritativeProblem = await authoritative.json() as { code?: string };
    expect(authoritativeProblem.code).toBe('RENDERER_UNAVAILABLE');
    const alert = page.getByRole('alert');
    await expect(alert).toContainText('权威预览未生成');
    await expect(alert).toContainText('RENDERER_UNAVAILABLE');
    await expect(alert).toBeFocused();

    expect(browserErrors.filter((message) =>
      !message.includes('server responded with a status of 503'))).toEqual([]);
    writeEvidence({
      contractVersion: 'renderweave-template-candidate-preview-validation/1.0',
      assurance: 'NOT_CERTIFIED',
      templateId: parent.templateId,
      childTemplateId: setup.child.templateId,
      authoredChange: 'BINDING_REPEAT_CONDITIONAL_TEMPLATE_USE_SAVED_AT_REVISION_1',
      inputs: [
        { id: 'A', tagCount: 1, showDetails: false, barWidthMm: 12.7, png: pngA, pixels: visualA },
        { id: 'B', tagCount: 3, showDetails: true, barWidthMm: 38.1, png: pngB, jpeg, pixels: visualB },
      ],
      jpeg,
      authoritativePreview: {
        status: authoritative.status(),
        code: authoritativeProblem.code,
        candidateStatusHeaderPresent: false,
      },
      accessibility: { seriousOrCritical: 0 },
      asyncFeedback: { candidatePendingObserved: true, authoritativeErrorFocused: true },
    });
  });
});

function candidateResponse(format: 'PNG' | 'JPEG') {
  return (response: Response): boolean => {
    const url = new URL(response.url());
    return response.request().method() === 'POST'
      && url.pathname.startsWith('/internal/candidate-preview/templates/')
      && url.searchParams.get('format') === format;
  };
}

async function verifyCandidateResponse(
  response: Response,
  format: 'PNG' | 'JPEG',
  expectedInput: string,
) {
  expect(response.status()).toBe(200);
  expect(response.request().postData()).toBe(expectedInput);
  const headers = response.headers();
  const bytes = await response.body();
  const digest = `sha-256=:${createHash('sha256').update(bytes).digest('base64')}:`;
  expect(headers['renderweave-candidate-status']).toBe('NOT_CERTIFIED');
  expect(headers['cache-control']).toContain('no-store');
  expect(headers['content-length']).toBe(String(bytes.byteLength));
  expect(headers['content-digest']).toBe(digest);
  expect(headers['renderweave-result-version']).toBe('renderweave-render-result/1.0');
  expect(headers['renderweave-renderer-profile']).toBe('renderweave-renderer/1.0');
  expect(headers['renderweave-dsl-version']).toBe('renderweave-render/1.0');
  expect(headers['renderweave-layout-profile']).toBe('renderweave-layout/1.0');
  expect(headers['renderweave-format']).toBe(format);
  expect(Number(headers['renderweave-width-px'])).toBeGreaterThan(0);
  expect(Number(headers['renderweave-height-px'])).toBeGreaterThan(0);
  expect(headers['renderweave-dpi']).toBe('96');
  if (format === 'PNG') {
    expect(headers['content-type']).toContain('image/png');
    expect(headers['renderweave-output-profile']).toBe('renderweave-output-png/1.0');
    expect(headers['renderweave-quality']).toBeUndefined();
    expect([...bytes.subarray(0, 8)]).toEqual([137, 80, 78, 71, 13, 10, 26, 10]);
  } else {
    expect(headers['content-type']).toContain('image/jpeg');
    expect(headers['renderweave-output-profile']).toBe('renderweave-output-jpeg/1.0');
    expect(headers['renderweave-quality']).toBe('90');
    expect([...bytes.subarray(0, 2)]).toEqual([255, 216]);
    expect([...bytes.subarray(-2)]).toEqual([255, 217]);
  }
  return {
    format,
    byteLength: bytes.byteLength,
    contentDigest: digest,
    rendererProfile: headers['renderweave-renderer-profile'],
    layoutProfile: headers['renderweave-layout-profile'],
    outputProfile: headers['renderweave-output-profile'],
    widthPx: Number(headers['renderweave-width-px']),
    heightPx: Number(headers['renderweave-height-px']),
    dpi: Number(headers['renderweave-dpi']),
    ...(format === 'JPEG' ? { quality: Number(headers['renderweave-quality']) } : {}),
  };
}

async function createCandidateCompositionSetup(
  request: APIRequestContext,
): Promise<CandidateCompositionSetup> {
  const suffix = `${Date.now().toString(36)}-${process.pid.toString(36)}`;
  const schemaKey = `t228-candidate-${suffix}`;
  const childDisplayName = `T228 bound child ${suffix}`;
  await publishStaticSchema(request, schemaKey, {
    dslVersion: 'renderweave-schema/1.0',
    displayName: `T228 Candidate input ${suffix}`,
    fields: [
      {
        fieldKey: 'barWidth',
        displayName: '条宽',
        required: true,
        value: { type: 'decimal' },
      },
      {
        fieldKey: 'tags',
        displayName: '标签',
        required: true,
        value: { type: 'array', items: { type: 'text' } },
      },
      {
        fieldKey: 'showDetails',
        displayName: '显示详情',
        required: true,
        value: { type: 'boolean' },
      },
    ],
  });
  const child = await createTemplateWithSchema(
    request,
    schemaKey,
    candidateDesign(childDisplayName, '97000000-0000-4000-8000-000000000001', 50.8, 25.4),
  );
  return { schemaKey, child, childDisplayName };
}

async function authorBoundChild(
  page: Page,
  setup: CandidateCompositionSetup,
): Promise<TemplateCurrentBody> {
  await page.goto(`/templates/${setup.child.templateId}`, { waitUntil: 'domcontentloaded' });
  await expect(page.getByRole('main', { name: 'Template 编辑工作区' })).toBeVisible();
  await page.getByRole('button', { name: '数据源' }).click();
  await expect(page.getByText(setup.schemaKey, { exact: true })).toBeVisible();
  await page.getByRole('button', { name: '元素' }).click();
  await page.getByRole('button', { name: '添加矩形' }).click();
  await setSelectedGeometry(page, { x: 0, y: 0, width: 25.4, height: 25.4 });
  await page.getByRole('button', { name: '绑定宽度' }).click();
  const dialog = page.getByRole('dialog', { name: '绑定宽度' });
  await dialog.getByRole('radio', { name: /条宽.*\/barWidth.*小数/ }).check();
  await dialog.getByRole('button', { name: '创建绑定' }).click();
  const save = page.waitForResponse(templateSaveResponse(setup.child.templateId, 200));
  await page.getByRole('button', { name: '保存 canonical 本地草稿' }).click();
  return (await save).json() as Promise<TemplateCurrentBody>;
}

async function authorCandidateComposition(
  page: Page,
  childDisplayName: string,
): Promise<{ repeatId: string; conditionalId: string; templateUseId: string }> {
  const root = page.locator('[role="treeitem"][data-kind="canvas"]');

  await root.click();
  await page.getByRole('button', { name: '容器', exact: true }).click();
  await page.getByRole('button', { name: '添加循环容器' }).click();
  const repeat = page.locator('[role="treeitem"][data-kind="repeat"]');
  const repeatId = requiredAttribute(await repeat.getAttribute('data-template-editor-node-id'));
  await selectOptionContaining(page, page.getByLabel('循环列表属性', { exact: true }), '/tags');
  await selectOptionContaining(page, page.getByLabel('循环排列方向', { exact: true }), '横向');
  await setSelectedGeometry(page, { x: 0, y: 0, width: 101.6, height: 25.4 });
  await page.getByRole('button', { name: '元素' }).click();
  await page.getByRole('button', { name: '添加矩形' }).click();
  await commitInput(page.getByLabel('宽度', { exact: true }), '25.4');
  await commitInput(page.getByLabel('高度', { exact: true }), '25.4');
  await commitInput(page.getByLabel('填充颜色', { exact: true }), '#16A34AFF');

  await root.click();
  await page.getByRole('button', { name: '容器', exact: true }).click();
  await page.getByRole('button', { name: '添加条件容器' }).click();
  const conditional = page.locator('[role="treeitem"][data-kind="conditional"]');
  const conditionalId = requiredAttribute(
    await conditional.getAttribute('data-template-editor-node-id'),
  );
  await selectOptionContaining(page, page.getByLabel('条件数据源', { exact: true }), '/showDetails');
  await setSelectedGeometry(page, { x: 0, y: 25.4, width: 25.4, height: 25.4 });
  await page.getByRole('button', { name: '元素' }).click();
  await page.getByRole('button', { name: '添加矩形' }).click();
  await setSelectedGeometry(page, { x: 0, y: 0, width: 25.4, height: 25.4 });
  await commitInput(page.getByLabel('填充颜色', { exact: true }), '#DC2626FF');

  await root.click();
  await page.getByRole('button', { name: '容器', exact: true }).click();
  await page.getByRole('button', { name: '添加嵌套模板' }).click();
  const templateUse = page.locator('[role="treeitem"][data-kind="templateUse"]');
  await expect(templateUse).toBeVisible();
  await expect(page.getByText(childDisplayName, { exact: true })).toBeVisible();
  const templateUseId = requiredAttribute(
    await templateUse.getAttribute('data-template-editor-node-id'),
  );
  await setSelectedGeometry(page, { x: 50.8, y: 25.4, width: 50.8, height: 25.4 });
  return { repeatId, conditionalId, templateUseId };
}

function expectCandidateComposition(
  designDsl: Record<string, unknown>,
  authored: { repeatId: string; conditionalId: string; templateUseId: string },
  childTemplateId: string,
): void {
  const repeat = requiredAuthoredNode(designDsl, authored.repeatId);
  expect(repeat).toMatchObject({
    kind: 'repeat',
    items: { kind: 'context', domain: 'invocation', pointer: '/tags' },
    instanceLayout: { kind: 'STACK', direction: 'ROW', gapMm: 0 },
  });
  expect(Array.isArray(repeat.children) ? repeat.children : []).toHaveLength(1);
  expect(requiredAuthoredNode(designDsl, authored.conditionalId)).toMatchObject({
    kind: 'conditional',
    condition: { kind: 'context', domain: 'invocation', pointer: '/showDetails' },
  });
  expect(requiredAuthoredNode(designDsl, authored.templateUseId)).toMatchObject({
    kind: 'templateUse',
    templateRef: { templateId: childTemplateId },
    contextSelector: {
      kind: 'context',
      domain: { kind: 'invocation' },
      pointer: '',
      contextAbsentPolicy: 'ERROR',
    },
  });
}

function expectBoundChild(designDsl: Record<string, unknown>): void {
  const rect = requiredAuthoredNodeOfKind(designDsl, 'rect');
  expect(rect.bindings).toEqual([{
    bindingId: expect.stringMatching(
      /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/,
    ),
    targetPropertyRef: {
      rootPropertyId: 'placement',
      selectors: [{ kind: 'member', name: 'widthMm' }],
    },
    source: { kind: 'context', domain: 'invocation', pointer: '/barWidth' },
  }]);
}

async function publishStaticSchema(
  request: APIRequestContext,
  schemaKey: string,
  definition: Record<string, unknown>,
): Promise<void> {
  const draftResponse = await request.post('/api/v1/schema-drafts', {
    data: { schemaKey, definition },
  });
  expect(draftResponse.status()).toBe(201);
  const draft = await draftResponse.json() as { revision: number };
  const publishResponse = await request.post('/api/v1/static-schemas', {
    data: {
      schemaKey,
      expectedRevision: draft.revision,
      versionTag: 'v1',
      releaseNote: 'T228 Candidate Preview composition canary',
    },
  });
  expect(publishResponse.status()).toBe(201);
}

async function createTemplateWithSchema(
  request: APIRequestContext,
  schemaKey: string,
  designDsl: Record<string, unknown>,
): Promise<TemplateCurrentBody> {
  const response = await request.post(
    `/api/v1/templates?schemaKey=${encodeURIComponent(schemaKey)}&versionTag=v1`,
    {
      headers: { 'Content-Type': DESIGN_MEDIA_TYPE },
      data: JSON.stringify(designDsl),
    },
  );
  expect(response.status()).toBe(201);
  return response.json() as Promise<TemplateCurrentBody>;
}

function candidateDesign(
  displayName: string,
  nodeId: string,
  widthMm = CANVAS_WIDTH_MM,
  heightMm = CANVAS_HEIGHT_MM,
): Record<string, unknown> {
  return {
    dslVersion: 'renderweave-design/1.0',
    expressionProfile: 'renderweave-expression/1.0',
    displayName,
    definitions: [],
    designRoot: {
      nodeId,
      kind: 'canvas',
      widthMm,
      heightMm,
      backgroundColor: '#FFFFFFFF',
      bindings: [],
      children: [],
    },
  };
}

async function setSelectedGeometry(
  page: Page,
  geometry: { x: number; y: number; width: number; height: number },
): Promise<void> {
  await commitInput(page.getByLabel('X 坐标', { exact: true }), String(geometry.x));
  await commitInput(page.getByLabel('Y 坐标', { exact: true }), String(geometry.y));
  await commitInput(page.getByLabel('宽度', { exact: true }), String(geometry.width));
  await commitInput(page.getByLabel('高度', { exact: true }), String(geometry.height));
}

async function commitInput(input: Locator, value: string): Promise<void> {
  await input.fill(value);
  await input.blur();
  await expect(input).toHaveValue(value);
}

async function selectOptionContaining(page: Page, select: Locator, text: string): Promise<void> {
  await select.click();
  const listbox = page.getByRole('listbox');
  const option = listbox.getByRole('option').filter({ hasText: text });
  await expect(option).toHaveCount(1);
  await option.click();
}

function templateSaveResponse(templateId: string, status: number) {
  return (response: Response): boolean => {
    const url = new URL(response.url());
    return response.request().method() === 'PUT'
      && url.pathname === `/api/v1/templates/${templateId}`
      && response.status() === status;
  };
}

function requiredAuthoredNode(
  designDsl: Record<string, unknown>,
  nodeId: string,
): Record<string, unknown> {
  const root = designDsl.designRoot;
  if (!root || typeof root !== 'object' || Array.isArray(root)) throw new Error('design root missing');
  const queue = [root as Record<string, unknown>];
  while (queue.length > 0) {
    const node = queue.shift();
    if (!node) break;
    if (node.nodeId === nodeId) return node;
    if (Array.isArray(node.children)) {
      queue.push(...node.children.filter((child): child is Record<string, unknown> => (
        typeof child === 'object' && child !== null && !Array.isArray(child)
      )));
    }
  }
  throw new Error(`authored node missing: ${nodeId}`);
}

function requiredAuthoredNodeOfKind(
  designDsl: Record<string, unknown>,
  kind: string,
): Record<string, unknown> {
  const root = designDsl.designRoot;
  if (!root || typeof root !== 'object' || Array.isArray(root)) throw new Error('design root missing');
  const queue = [root as Record<string, unknown>];
  while (queue.length > 0) {
    const node = queue.shift();
    if (!node) break;
    if (node.kind === kind) return node;
    if (Array.isArray(node.children)) {
      queue.push(...node.children.filter((child): child is Record<string, unknown> => (
        typeof child === 'object' && child !== null && !Array.isArray(child)
      )));
    }
  }
  throw new Error(`authored node kind missing: ${kind}`);
}

function requiredAttribute(value: string | null): string {
  if (!value) throw new Error('required attribute missing');
  return value;
}

async function inspectCandidatePixels<T extends Record<string, { x: number; y: number }>>(
  image: Locator,
  probes: T,
): Promise<{ [K in keyof T]: PixelProbe }> {
  await expect.poll(() => image.evaluate((element) => {
    const candidate = element as HTMLImageElement;
    return candidate.complete && candidate.naturalWidth > 0 && candidate.naturalHeight > 0;
  })).toBe(true);
  return image.evaluate((element, input) => {
    const candidate = element as HTMLImageElement;
    const canvas = document.createElement('canvas');
    canvas.width = candidate.naturalWidth;
    canvas.height = candidate.naturalHeight;
    const context = canvas.getContext('2d', { willReadFrequently: true });
    if (!context) throw new Error('2D canvas unavailable');
    context.drawImage(candidate, 0, 0);
    return Object.fromEntries(Object.entries(input.points).map(([key, point]) => {
      const x = Math.min(canvas.width - 1, Math.floor((point.x / input.widthMm) * canvas.width));
      const y = Math.min(canvas.height - 1, Math.floor((point.y / input.heightMm) * canvas.height));
      const rgba = Array.from(context.getImageData(x, y, 1, 1).data);
      return [key, { x, y, rgba }];
    }));
  }, {
    points: probes,
    widthMm: CANVAS_WIDTH_MM,
    heightMm: CANVAS_HEIGHT_MM,
  }) as Promise<{ [K in keyof T]: PixelProbe }>;
}

async function installPendingObserver(page: Page): Promise<void> {
  await page.evaluate(() => {
    window.__renderWeaveCandidatePendingSeen = document.body.textContent
      ?.includes('正在生成 NOT_CERTIFIED 候选预览') ?? false;
    window.__renderWeaveCandidatePendingObserver?.disconnect();
    window.__renderWeaveCandidatePendingObserver = new MutationObserver(() => {
      if (document.body.textContent?.includes('正在生成 NOT_CERTIFIED 候选预览')) {
        window.__renderWeaveCandidatePendingSeen = true;
        window.__renderWeaveCandidatePendingObserver?.disconnect();
      }
    });
    window.__renderWeaveCandidatePendingObserver.observe(document.body, {
      childList: true,
      subtree: true,
      characterData: true,
    });
  });
}

async function candidatePendingWasSeen(page: Page): Promise<boolean> {
  return page.evaluate(() => {
    window.__renderWeaveCandidatePendingObserver?.disconnect();
    return window.__renderWeaveCandidatePendingSeen === true;
  });
}

async function expectNoSeriousOrCriticalAxe(page: Page, include: string): Promise<void> {
  const accessibility = await new AxeBuilder({ page })
    .include(include)
    .withTags(['wcag2a', 'wcag2aa', 'wcag21aa', 'wcag22aa'])
    .analyze();
  expect(accessibility.violations.filter((violation) =>
    violation.impact === 'serious' || violation.impact === 'critical')).toEqual([]);
}

function captureBrowserErrors(page: Page): string[] {
  const errors: string[] = [];
  page.on('console', (message) => {
    if (message.type() === 'error') errors.push(message.text());
  });
  page.on('pageerror', (error) => errors.push(error.message));
  return errors;
}

function writeEvidence(value: unknown): void {
  if (!EVIDENCE_DIR) return;
  mkdirSync(EVIDENCE_DIR, { recursive: true });
  writeFileSync(
    path.join(EVIDENCE_DIR, 'candidate-preview-summary.json'),
    `${JSON.stringify(value, null, 2)}\n`,
    'utf8',
  );
}
