import { readFileSync } from 'node:fs';
import path from 'node:path';

import {
  expect,
  test,
  type APIRequestContext,
  type Locator,
  type Page,
  type Response,
} from '@playwright/test';

const LIVE = process.env.RENDERWEAVE_TEMPLATE_ROUNDTRIP_LIVE === '1';
const DESIGN_MEDIA_TYPE = 'application/vnd.renderweave.design+json';
const COMPLETE_WIRE_FIXTURE = readFileSync(path.resolve(
  process.cwd(),
  '..',
  'renderweave-template',
  'src',
  'test',
  'resources',
  'cn',
  'hbads',
  'renderweave',
  'template',
  'complete-wire-v1',
  'all-kinds.json',
), 'utf8');
const COMPLETE_WIRE_EDITED_NAME = 'Complete wire edited in Structured mode';

interface TemplateCurrentBody {
  templateId: string;
  revision: number;
  contentHash: string;
  readiness: string;
  designDsl: Record<string, unknown>;
}

interface InvalidConfirmationBody {
  code: string;
  confirmationToken: string;
  truncated: boolean;
  problems: Array<{ code: string; canonicalPointer: string }>;
}

interface AuthoredNodeBody {
  nodeId: string;
  kind: string;
  displayName?: string;
  placement?: Record<string, unknown>;
  children?: AuthoredNodeBody[];
}

interface AbsoluteGeometry {
  xMm: number;
  yMm: number;
  widthMm: number;
  heightMm: number;
}

test.describe('complete DesignDSL real Template round trip', () => {
  test.skip(!LIVE, 'requires the explicit local Template roundtrip environment');

  test('opens all admitted shapes in Structured mode and preserves them through save/reload', async ({ page }) => {
    test.setTimeout(90_000);
    const browserErrors = captureBrowserErrors(page);
    await page.setViewportSize({ width: 1280, height: 720 });

    const setup = await createTemplateForCompleteWire(page.request);
    const fixtureCurrent = await saveAndConfirmInvalid(
      page.request,
      setup.templateId,
      setup.revision,
      COMPLETE_WIRE_FIXTURE,
    );
    expect(fixtureCurrent.revision).toBe(1);
    expect(fixtureCurrent.readiness).toBe('INVALID');
    expect(fixtureCurrent.designDsl).toEqual(JSON.parse(COMPLETE_WIRE_FIXTURE));
    expectCompleteWireCoverage(fixtureCurrent.designDsl);

    const initialOpen = page.waitForResponse(templateCurrentResponse(setup.templateId));
    await page.goto(`/templates/${setup.templateId}`, { waitUntil: 'domcontentloaded' });
    expect((await initialOpen).status()).toBe(200);
    await expect(page.getByRole('main', { name: 'Template 编辑工作区' })).toBeVisible();
    await expect(page.getByText('Structured Editor', { exact: true })).toBeVisible();
    await expect(page.getByText('revision 1', { exact: true })).toBeVisible();
    await expect(page.getByText('INVALID', { exact: true })).toBeVisible();
    await expect(page.getByText('18 个 authored 节点', { exact: true })).toBeVisible();

    await page.getByRole('textbox', { name: 'Template 名称' }).fill(COMPLETE_WIRE_EDITED_NAME);
    await page.getByRole('button', { name: '应用本地名称' }).click();
    await expect(page.getByText('Canonical 本地草稿', { exact: true })).toBeVisible();

    const offerResponse = page.waitForResponse(templateSaveResponse(setup.templateId, 422));
    await page.getByRole('button', { name: '保存 canonical 本地草稿' }).click();
    const offer = await offerResponse;
    const offerBody = await offer.json() as InvalidConfirmationBody;
    expect(offerBody.code).toBe('TEMPLATE_DEPENDENCY_CONFIRMATION_REQUIRED');
    expect(offerBody.truncated).toBe(false);
    expect(offerBody.confirmationToken).toMatch(/^[0-9a-f]{64}$/);
    expect(offerBody.problems.length).toBeGreaterThan(0);
    await expect(page.getByRole('heading', { name: '确认仍保存为 INVALID' })).toBeVisible();

    const confirmedResponse = page.waitForResponse(templateSaveResponse(setup.templateId, 200));
    await page.getByRole('button', { name: '仍保存为 INVALID' }).click();
    const confirmed = await confirmedResponse;
    expect(confirmed.request().headers()['x-confirmation-token']).toBe(offerBody.confirmationToken);
    const confirmedBody = await confirmed.json() as TemplateCurrentBody;
    expect(confirmedBody.revision).toBe(2);
    expect(confirmedBody.readiness).toBe('INVALID');
    await expect(page.getByText('revision 2', { exact: true })).toBeVisible();
    await expect(page.getByText('Canonical current', { exact: true })).toBeVisible();

    const reloadResponse = page.waitForResponse(templateCurrentResponse(setup.templateId));
    await page.reload({ waitUntil: 'domcontentloaded' });
    expect((await reloadResponse).status()).toBe(200);
    await expect(page.getByText('Structured Editor', { exact: true })).toBeVisible();
    await expect(page.getByRole('heading', {
      level: 1,
      name: COMPLETE_WIRE_EDITED_NAME,
    })).toBeVisible();
    await expect(page.getByText('revision 2', { exact: true })).toBeVisible();

    const reloadedResponse = await page.request.get(`/api/v1/templates/${setup.templateId}`);
    expect(reloadedResponse.status()).toBe(200);
    const reloaded = await reloadedResponse.json() as TemplateCurrentBody;
    expect(reloaded.revision).toBe(2);
    expect(reloaded.readiness).toBe('INVALID');
    expect(reloaded.contentHash).not.toBe(fixtureCurrent.contentHash);
    expect(reloaded.designDsl.displayName).toBe(COMPLETE_WIRE_EDITED_NAME);
    expect(withoutDisplayName(reloaded.designDsl)).toEqual(
      withoutDisplayName(fixtureCurrent.designDsl),
    );
    expectCompleteWireCoverage(reloaded.designDsl);
    expect(browserErrors.filter((message) => message.includes('status of 422'))).toHaveLength(1);
    expect(browserErrors.filter((message) => !message.includes('status of 422'))).toEqual([]);
  });

  test('authors Frame, Stack and Rect through the production shell and reloads their exact tree and geometry', async ({ page }) => {
    test.setTimeout(90_000);
    const browserErrors = captureBrowserErrors(page);
    await page.setViewportSize({ width: 1280, height: 720 });

    const created = await createTemplateForCoreAuthoring(page.request);
    expect(created.revision).toBe(0);
    const initialOpen = page.waitForResponse(templateCurrentResponse(created.templateId));
    await page.goto(`/templates/${created.templateId}`, { waitUntil: 'domcontentloaded' });
    expect((await initialOpen).status()).toBe(200);
    await expect(page.getByRole('main', { name: 'Template 编辑工作区' })).toBeVisible();
    await expect(page.getByText('revision 0', { exact: true })).toBeVisible();

    await page.getByRole('button', { name: '容器' }).click();
    await page.getByRole('button', { name: '添加框架' }).click();
    const frameRow = page.getByRole('treeitem', { name: /框架 1/ });
    await expect(frameRow).toHaveAttribute('aria-selected', 'true');
    const frameId = requiredAttribute(await frameRow.getAttribute('data-template-editor-node-id'));

    await page.getByRole('button', { name: '元素' }).click();
    await page.getByRole('button', { name: '添加矩形' }).click();
    const firstRectRow = page.getByRole('treeitem', { name: /矩形 1/ });
    const firstRectId = requiredAttribute(
      await firstRectRow.getAttribute('data-template-editor-node-id'),
    );

    await page.getByRole('button', { name: '元素' }).click();
    await page.getByRole('button', { name: '添加矩形' }).click();
    const secondRectRow = page.getByRole('treeitem', { name: /矩形 2/ });
    const secondRectId = requiredAttribute(
      await secondRectRow.getAttribute('data-template-editor-node-id'),
    );

    await frameRow.click();
    await page.getByRole('button', { name: '容器' }).click();
    await page.getByRole('button', { name: '添加堆叠容器' }).click();
    const stackRow = page.getByRole('treeitem', { name: /堆叠 1/ });
    const stackId = requiredAttribute(await stackRow.getAttribute('data-template-editor-node-id'));

    await firstRectRow.press('F2');
    const rename = page.getByRole('textbox', { name: '重命名 矩形 1' });
    await rename.fill('堆叠项');
    await rename.press('Enter');
    const renamedRectRow = page.getByRole('treeitem', { name: /堆叠项/ });
    await expect(renamedRectRow).toHaveAttribute('data-template-editor-node-id', firstRectId);

    // Rect 2 is still ABSOLUTE under Frame here. Exercise direct canvas geometry
    // before moving the other Rect into Stack, whose STACK placement is not part
    // of the T222 browser layout slice.
    await secondRectRow.click();
    const selection = page.locator(`[data-template-canvas-selection="${secondRectId}"]`);
    await expect(selection).toBeVisible();
    const initialGeometry = await readSelectedAbsoluteGeometry(page);
    await dragLocatorBy(page, selection, 24, 16);
    await expect.poll(() => readSelectedAbsoluteGeometry(page)).not.toEqual(initialGeometry);
    const movedGeometry = await readSelectedAbsoluteGeometry(page);
    expect(movedGeometry.xMm).toBeGreaterThan(initialGeometry.xMm);
    expect(movedGeometry.yMm).toBeGreaterThan(initialGeometry.yMm);

    const southEastHandle = page.locator(
      `[data-template-canvas-selection="${secondRectId}"] [data-resize-handle="se"]`,
    );
    await dragLocatorBy(page, southEastHandle, 20, 12);
    await expect.poll(() => readSelectedAbsoluteGeometry(page)).not.toEqual(movedGeometry);
    const resizedGeometry = await readSelectedAbsoluteGeometry(page);
    expect(resizedGeometry.widthMm).toBeGreaterThan(movedGeometry.widthMm);
    expect(resizedGeometry.heightMm).toBeGreaterThan(movedGeometry.heightMm);

    await page.getByRole('button', { name: '撤销本地编辑' }).click();
    await expect.poll(() => readSelectedAbsoluteGeometry(page)).toEqual(movedGeometry);
    await page.getByRole('button', { name: '重做本地编辑' }).click();
    await expect.poll(() => readSelectedAbsoluteGeometry(page)).toEqual(resizedGeometry);

    await page.getByRole('button', { name: '结构' }).click();
    await dragTreeNodeInto(renamedRectRow, stackRow);
    await expect(page.getByRole('treeitem', { name: /堆叠项/ })).toHaveAttribute('aria-level', '4');

    await secondRectRow.click({ button: 'right' });
    const layerMenu = page.getByRole('menu', { name: '矩形 2 操作' });
    await expect(layerMenu).toBeVisible();
    await layerMenu.getByRole('menuitem', { name: '置于顶层' }).click();

    const saveResponsePromise = page.waitForResponse(
      templateSaveResponse(created.templateId, 200),
    );
    await page.getByRole('button', { name: '保存 canonical 本地草稿' }).click();
    const saveResponse = await saveResponsePromise;
    const savedRequest = saveResponse.request();
    expect(new URL(savedRequest.url()).searchParams.get('expectedRevision')).toBe('0');
    expect(savedRequest.headers()['content-type']).toBe(DESIGN_MEDIA_TYPE);
    const savedBody = await saveResponse.json() as TemplateCurrentBody;
    expect(savedBody.revision).toBe(1);
    expectCoreAuthoringResult(savedBody, {
      frameId,
      stackId,
      stackedRectId: firstRectId,
      absoluteRectId: secondRectId,
      absoluteGeometry: resizedGeometry,
    });
    await expect(page.getByText('revision 1', { exact: true })).toBeVisible();
    await expect(page.getByText('Canonical current', { exact: true })).toBeVisible();

    const reloadResponse = page.waitForResponse(templateCurrentResponse(created.templateId));
    await page.reload({ waitUntil: 'domcontentloaded' });
    expect((await reloadResponse).status()).toBe(200);
    await expect(page.getByText('revision 1', { exact: true })).toBeVisible();
    await expect(page.getByRole('treeitem', { name: /堆叠项/ })).toBeVisible();
    const reloadedAbsoluteRect = page.getByRole('treeitem', { name: /矩形 2/ });
    await reloadedAbsoluteRect.click();
    await expect(page.locator(
      `[data-template-canvas-selection="${secondRectId}"]`,
    )).toBeVisible();
    await expect.poll(() => readSelectedAbsoluteGeometry(page)).toEqual(resizedGeometry);

    const reloadedResponse = await page.request.get(`/api/v1/templates/${created.templateId}`);
    expect(reloadedResponse.status()).toBe(200);
    const reloaded = await reloadedResponse.json() as TemplateCurrentBody;
    expect(reloaded.revision).toBe(1);
    expect(reloaded.readiness).toBe('READY');
    expectCoreAuthoringResult(reloaded, {
      frameId,
      stackId,
      stackedRectId: firstRectId,
      absoluteRectId: secondRectId,
      absoluteGeometry: resizedGeometry,
    });
    expect(browserErrors).toEqual([]);
  });
});

async function createTemplateForCompleteWire(
  request: APIRequestContext,
): Promise<TemplateCurrentBody> {
  const initialDesign = JSON.stringify({
    dslVersion: 'renderweave-design/1.0',
    expressionProfile: 'renderweave-expression/1.0',
    displayName: 'Complete wire setup',
    definitions: [],
    designRoot: {
      nodeId: '90000000-0000-4000-8000-000000000001',
      kind: 'canvas',
      widthMm: 210,
      heightMm: 297,
      bindings: [],
      children: [],
    },
  });
  const response = await request.post(
    '/api/v1/templates?schemaKey=system-basic-text&versionTag=v1',
    {
      headers: { 'Content-Type': DESIGN_MEDIA_TYPE },
      data: initialDesign,
    },
  );
  expect(response.status()).toBe(201);
  return response.json() as Promise<TemplateCurrentBody>;
}

async function createTemplateForCoreAuthoring(
  request: APIRequestContext,
): Promise<TemplateCurrentBody> {
  const initialDesign = JSON.stringify({
    dslVersion: 'renderweave-design/1.0',
    expressionProfile: 'renderweave-expression/1.0',
    displayName: 'Core authoring E2E',
    definitions: [],
    designRoot: {
      nodeId: '91000000-0000-4000-8000-000000000001',
      kind: 'canvas',
      widthMm: 210,
      heightMm: 297,
      bindings: [],
      children: [],
    },
  });
  const response = await request.post(
    '/api/v1/templates?schemaKey=system-empty&versionTag=v1',
    {
      headers: { 'Content-Type': DESIGN_MEDIA_TYPE },
      data: initialDesign,
    },
  );
  expect(response.status()).toBe(201);
  return response.json() as Promise<TemplateCurrentBody>;
}

async function saveAndConfirmInvalid(
  request: APIRequestContext,
  templateId: string,
  expectedRevision: number,
  canonicalDesign: string,
): Promise<TemplateCurrentBody> {
  const url = `/api/v1/templates/${templateId}?expectedRevision=${expectedRevision}`;
  const offerResponse = await request.put(url, {
    headers: { 'Content-Type': DESIGN_MEDIA_TYPE },
    data: canonicalDesign,
  });
  expect(offerResponse.status()).toBe(422);
  const offer = await offerResponse.json() as InvalidConfirmationBody;
  expect(offer.code).toBe('TEMPLATE_DEPENDENCY_CONFIRMATION_REQUIRED');
  expect(offer.truncated).toBe(false);
  expect(offer.confirmationToken).toMatch(/^[0-9a-f]{64}$/);
  expect(offer.problems.length).toBeGreaterThan(0);

  const committedResponse = await request.put(url, {
    headers: {
      'Content-Type': DESIGN_MEDIA_TYPE,
      'X-Confirmation-Token': offer.confirmationToken,
    },
    data: canonicalDesign,
  });
  expect(committedResponse.status()).toBe(200);
  return committedResponse.json() as Promise<TemplateCurrentBody>;
}

function templateCurrentResponse(templateId: string) {
  return (response: Response): boolean => {
    const url = new URL(response.url());
    return response.request().method() === 'GET'
      && url.pathname === `/api/v1/templates/${templateId}`;
  };
}

function templateSaveResponse(templateId: string, status: number) {
  return (response: Response): boolean => {
    const url = new URL(response.url());
    return response.request().method() === 'PUT'
      && url.pathname === `/api/v1/templates/${templateId}`
      && response.status() === status;
  };
}

function withoutDisplayName(designDsl: Record<string, unknown>): Record<string, unknown> {
  const copy = structuredClone(designDsl);
  delete copy.displayName;
  return copy;
}

async function dragLocatorBy(
  page: Page,
  target: Locator,
  deltaX: number,
  deltaY: number,
): Promise<void> {
  await expect(target).toBeVisible();
  const bounds = await target.boundingBox();
  if (!bounds) throw new Error('Canvas interaction target has no visible bounding box');
  const start = {
    x: bounds.x + bounds.width / 2,
    y: bounds.y + bounds.height / 2,
  };
  await page.mouse.move(start.x, start.y);
  await page.mouse.down();
  await page.mouse.move(start.x + deltaX, start.y + deltaY, { steps: 4 });
  await page.mouse.up();
}

async function dragTreeNodeInto(source: Locator, target: Locator): Promise<void> {
  await expect(source).toBeVisible();
  await expect(target).toBeVisible();
  const targetBounds = await target.boundingBox();
  if (!targetBounds) throw new Error('Tree drop target has no visible bounding box');
  await source.dragTo(target, {
    targetPosition: {
      x: targetBounds.width / 2,
      y: targetBounds.height / 2,
    },
  });
}

async function readSelectedAbsoluteGeometry(page: Page): Promise<AbsoluteGeometry> {
  return {
    xMm: await numericInputValue(page, 'X 坐标'),
    yMm: await numericInputValue(page, 'Y 坐标'),
    widthMm: await numericInputValue(page, '宽度'),
    heightMm: await numericInputValue(page, '高度'),
  };
}

async function numericInputValue(page: Page, label: string): Promise<number> {
  const raw = await page.getByLabel(label, { exact: true }).inputValue();
  const value = Number(raw);
  if (!Number.isFinite(value)) throw new Error(`${label} did not expose a finite number: ${raw}`);
  return value;
}

function expectCoreAuthoringResult(
  current: TemplateCurrentBody,
  expected: {
    frameId: string;
    stackId: string;
    stackedRectId: string;
    absoluteRectId: string;
    absoluteGeometry: AbsoluteGeometry;
  },
): void {
  const root = authoredNode(current.designDsl.designRoot, 'designRoot');
  expect(root).toMatchObject({
    kind: 'canvas',
    widthMm: 210,
    heightMm: 297,
  });
  expect(root.children).toHaveLength(1);

  const frame = authoredNode(root.children?.[0], 'Canvas Frame child');
  expect(frame).toMatchObject({
    nodeId: expected.frameId,
    kind: 'frame',
    displayName: '框架 1',
    placement: {
      type: 'ABSOLUTE',
      xMm: 25.4,
      yMm: 25.4,
      widthMode: 'FIXED',
      widthMm: 80,
      heightMode: 'FIXED',
      heightMm: 60,
    },
    padding: { topMm: 0, rightMm: 0, bottomMm: 0, leftMm: 0 },
  });
  expect(frame.children?.map((child) => child.nodeId)).toEqual([
    expected.stackId,
    expected.absoluteRectId,
  ]);

  const stack = authoredNode(frame.children?.[0], 'reordered Stack child');
  expect(stack).toMatchObject({
    nodeId: expected.stackId,
    kind: 'stack',
    displayName: '堆叠 1',
    direction: 'COLUMN',
    gapMm: 0,
    placement: {
      type: 'ABSOLUTE',
      xMm: 25.4,
      yMm: 25.4,
      widthMode: 'FIXED',
      widthMm: 80,
      heightMode: 'FIXED',
      heightMm: 60,
    },
    padding: { topMm: 0, rightMm: 0, bottomMm: 0, leftMm: 0 },
  });
  expect(stack.children).toHaveLength(1);
  expect(authoredNode(stack.children?.[0], 'Stack Rect child')).toMatchObject({
    nodeId: expected.stackedRectId,
    kind: 'rect',
    displayName: '堆叠项',
    placement: {
      type: 'STACK',
      widthMode: 'FIXED',
      widthMm: 25.4,
      heightMode: 'FIXED',
      heightMm: 25.4,
    },
  });

  expect(authoredNode(frame.children?.[1], 'absolute Rect child')).toMatchObject({
    nodeId: expected.absoluteRectId,
    kind: 'rect',
    displayName: '矩形 2',
    placement: {
      type: 'ABSOLUTE',
      widthMode: 'FIXED',
      heightMode: 'FIXED',
      ...expected.absoluteGeometry,
    },
  });
}

function authoredNode(value: unknown, location: string): AuthoredNodeBody {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    throw new Error(`${location} is not an authored node object`);
  }
  const candidate = value as Partial<AuthoredNodeBody>;
  if (typeof candidate.nodeId !== 'string' || typeof candidate.kind !== 'string') {
    throw new Error(`${location} has no nodeId/kind`);
  }
  return candidate as AuthoredNodeBody;
}

function requiredAttribute(value: string | null): string {
  if (!value) throw new Error('Expected a non-empty DOM identity attribute');
  return value;
}

function captureBrowserErrors(page: Page): string[] {
  const errors: string[] = [];
  page.on('console', (message) => {
    if (message.type() === 'error') errors.push(message.text());
  });
  page.on('pageerror', (error) => errors.push(error.message));
  return errors;
}

function expectCompleteWireCoverage(designDsl: Record<string, unknown>): void {
  const nodeKinds = new Set<string>();
  const placementTypes = new Set<string>();
  const sourceKinds = new Set<string>();
  const definitionKinds = new Set<string>();

  const visit = (value: unknown): void => {
    if (Array.isArray(value)) {
      value.forEach(visit);
      return;
    }
    if (value === null || typeof value !== 'object') return;
    const object = value as Record<string, unknown>;
    if (typeof object.kind === 'string') {
      if (ALL_NODE_KINDS.has(object.kind)) nodeKinds.add(object.kind);
      if (ALL_VALUE_SOURCE_KINDS.has(object.kind)) sourceKinds.add(object.kind);
      if (ALL_DEFINITION_KINDS.has(object.kind)) definitionKinds.add(object.kind);
    }
    if (object.placement !== null && typeof object.placement === 'object') {
      const placement = object.placement as Record<string, unknown>;
      if (typeof placement.type === 'string') placementTypes.add(placement.type);
    }
    Object.values(object).forEach(visit);
  };
  visit(designDsl);

  expect([...nodeKinds].sort()).toEqual([...ALL_NODE_KINDS].sort());
  expect([...placementTypes].sort()).toEqual(['ABSOLUTE', 'GRID', 'PACK', 'STACK']);
  expect([...definitionKinds].sort()).toEqual(['custom', 'expression', 'mapping']);
  expect([...sourceKinds].sort()).toEqual([
    'capability',
    'context',
    'definition',
    'literal',
    'loopIndex',
  ]);
}

const ALL_NODE_KINDS = new Set([
  'canvas',
  'group',
  'frame',
  'stack',
  'grid',
  'repeat',
  'text',
  'image',
  'rect',
  'ellipse',
  'line',
  'polygon',
  'polyline',
  'path',
  'qrCode',
  'barcode',
  'templateUse',
  'conditional',
]);
const ALL_VALUE_SOURCE_KINDS = new Set([
  'literal',
  'definition',
  'context',
  'loopIndex',
  'capability',
]);
const ALL_DEFINITION_KINDS = new Set(['custom', 'mapping', 'expression']);
