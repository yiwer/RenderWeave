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
const FONT_FIXTURE = readFileSync(path.resolve(
  process.cwd(),
  '..',
  'renderweave-asset',
  'src',
  'test',
  'resources',
  'asset-fixtures',
  'minimal-ttf.ttf',
));
const PNG_FIXTURE = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=',
  'base64',
);

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

interface AssetCurrentBody {
  assetId: string;
  disclosure: 'READABLE';
  kind: 'IMAGE' | 'FONT';
  lifecycle: 'ACTIVE' | 'DELETED';
  displayName: string;
}

interface AuthoredNodeBody extends Record<string, unknown> {
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
    // The deliberately INVALID complete-wire fixture contains unresolved Font/Image
    // AssetRefs. The production editor now resolves those refs to show authored
    // dependency feedback, so Chromium reports the expected 404 resource probes.
    expect(browserErrors.filter((message) => message.includes('status of 404')).length)
      .toBeGreaterThanOrEqual(2);
    expect(browserErrors.filter((message) => (
      !message.includes('status of 422') && !message.includes('status of 404')
    ))).toEqual([]);
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

  test('authors every visual leaf with real Assets and preserves exact wire through reload', async ({ page }) => {
    test.setTimeout(120_000);
    const browserErrors = captureBrowserErrors(page);
    await page.setViewportSize({ width: 1440, height: 900 });

    const font = await createE2eAsset(
      page.request,
      'FONT',
      'E2E 价签字体',
      'template-editor-font-v1',
      'minimal-ttf.ttf',
      'font/ttf',
      FONT_FIXTURE,
    );
    const image = await createE2eAsset(
      page.request,
      'IMAGE',
      'E2E 商品图片',
      'template-editor-image-v1',
      'product.png',
      'image/png',
      PNG_FIXTURE,
    );
    const created = await createTemplateForVisualAuthoring(page.request);
    const initialOpen = page.waitForResponse(templateCurrentResponse(created.templateId));
    await page.goto(`/templates/${created.templateId}`, { waitUntil: 'domcontentloaded' });
    expect((await initialOpen).status()).toBe(200);
    await expect(page.getByRole('main', { name: 'Template 编辑工作区' })).toBeVisible();

    await page.getByRole('button', { name: '元素' }).click();
    await page.getByRole('button', { name: '添加文本' }).click();
    await expect(page.getByRole('dialog', { name: '选择字体 Asset' })).toBeVisible();
    await page.getByRole('button', { name: new RegExp(font.assetId) }).click();
    const textRow = page.getByRole('treeitem', { name: /文本 1/ });
    const textId = requiredAttribute(await textRow.getAttribute('data-template-editor-node-id'));
    await page.getByLabel('文本值', { exact: true }).fill('会员价 ¥19.90');
    await page.getByLabel('文本值', { exact: true }).press('Enter');

    await page.getByRole('button', { name: '元素' }).click();
    await page.getByRole('button', { name: '添加图片' }).click();
    await expect(page.getByRole('dialog', { name: '选择图片 Asset' })).toBeVisible();
    await page.getByRole('button', { name: new RegExp(image.assetId) }).click();
    const imageRow = page.getByRole('treeitem', { name: /图片 1/ });
    const imageNodeId = requiredAttribute(await imageRow.getAttribute('data-template-editor-node-id'));
    await page.getByLabel('图片适配', { exact: true }).selectOption('COVER');

    await page.getByRole('button', { name: '元素' }).click();
    const ellipseButton = page.getByRole('button', { name: '添加椭圆' });
    const artboard = page.locator('.te-artboard');
    await ellipseButton.dragTo(artboard, { targetPosition: { x: 200, y: 160 } });
    const ellipseRow = page.getByRole('treeitem', { name: /椭圆 1/ });
    const ellipseId = requiredAttribute(await ellipseRow.getAttribute('data-template-editor-node-id'));
    const ellipseBefore = await readSelectedAbsoluteGeometry(page);
    await dragLocatorBy(
      page,
      page.locator(`[data-template-canvas-selection="${ellipseId}"] [data-resize-handle="se"]`),
      36,
      8,
    );
    await expect.poll(() => readSelectedAbsoluteGeometry(page)).not.toEqual(ellipseBefore);
    const ellipseGeometry = await readSelectedAbsoluteGeometry(page);
    expect(ellipseGeometry.widthMm - ellipseBefore.widthMm)
      .toBeGreaterThan(ellipseGeometry.heightMm - ellipseBefore.heightMm);
    const projectedEllipse = page.locator(
      `[data-template-canvas-node-id="${ellipseId}"] [data-template-visual-kind="ellipse"] ellipse`,
    );
    await expect(projectedEllipse).toBeVisible();
    expect(Number(await projectedEllipse.getAttribute('rx')))
      .toBeGreaterThan(Number(await projectedEllipse.getAttribute('ry')));

    for (const label of [
      '矩形', '直线', '多边形', '折线', '路径', '二维码', '条形码', '形状',
    ]) {
      await page.getByRole('button', { name: '元素' }).click();
      await page.getByRole('button', { name: `添加${label}` }).click();
    }

    await expect(page.getByRole('treeitem', { name: /星形/ })).toBeVisible();
    await expect(page.locator('[data-template-preview-authority="non-certified-local-draft"]'))
      .toHaveCount(2);

    const saveResponsePromise = page.waitForResponse(
      templateSaveResponse(created.templateId, 200),
    );
    await page.getByRole('button', { name: '保存 canonical 本地草稿' }).click();
    const saved = await (await saveResponsePromise).json() as TemplateCurrentBody;
    expect(saved.revision).toBe(1);
    expect(saved.readiness).toBe('READY');
    expectVisualAuthoringResult(saved, {
      textId,
      imageNodeId,
      ellipseId,
      fontAssetId: font.assetId,
      imageAssetId: image.assetId,
      ellipseGeometry,
    });

    const reloadResponse = page.waitForResponse(templateCurrentResponse(created.templateId));
    await page.reload({ waitUntil: 'domcontentloaded' });
    expect((await reloadResponse).status()).toBe(200);
    await expect(page.getByText('revision 1', { exact: true })).toBeVisible();
    await expect(page.getByRole('treeitem', { name: /文本 1/ })).toBeVisible();
    await expect(page.getByRole('treeitem', { name: /星形/ })).toBeVisible();

    const reloadedResponse = await page.request.get(`/api/v1/templates/${created.templateId}`);
    expect(reloadedResponse.status()).toBe(200);
    const reloaded = await reloadedResponse.json() as TemplateCurrentBody;
    expect(reloaded.contentHash).toBe(saved.contentHash);
    expect(reloaded.designDsl).toEqual(saved.designDsl);
    expectVisualAuthoringResult(reloaded, {
      textId,
      imageNodeId,
      ellipseId,
      fontAssetId: font.assetId,
      imageAssetId: image.assetId,
      ellipseGeometry,
    });
    expect(browserErrors).toEqual([]);
  });

  test('binds a required StaticSchema text field and preserves the exact Binding through save/reload', async ({ page }) => {
    test.setTimeout(90_000);
    const browserErrors = captureBrowserErrors(page);
    await page.setViewportSize({ width: 1280, height: 800 });

    const font = await createE2eAsset(
      page.request,
      'FONT',
      'E2E 绑定字体',
      'template-editor-binding-font-v1',
      'minimal-ttf.ttf',
      'font/ttf',
      FONT_FIXTURE,
    );
    const created = await createTemplateForBindingAuthoring(page.request);
    const initialOpen = page.waitForResponse(templateCurrentResponse(created.templateId));
    await page.goto(`/templates/${created.templateId}`, { waitUntil: 'domcontentloaded' });
    expect((await initialOpen).status()).toBe(200);

    await page.getByRole('button', { name: '数据源' }).click();
    await expect(page.getByText('system-basic-text', { exact: true })).toBeVisible();

    await page.getByRole('button', { name: '元素' }).click();
    await page.getByRole('button', { name: '添加文本' }).click();
    await page.getByRole('button', { name: new RegExp(font.assetId) }).click();
    const textRow = page.getByRole('treeitem', { name: /文本 1/ });
    const textId = requiredAttribute(await textRow.getAttribute('data-template-editor-node-id'));

    await page.getByRole('button', { name: '绑定文本值' }).click();
    const bindingDialog = page.getByRole('dialog', { name: '绑定文本值' });
    await expect(bindingDialog).toBeVisible();
    await bindingDialog.getByRole('radio', { name: /value.*\/value.*文本/ }).check();
    await bindingDialog.getByRole('button', { name: '创建绑定' }).click();

    await page.getByRole('tab', { name: /绑定 1 个绑定/ }).click();
    await expect(page.getByText('runs[0].text', { exact: true })).toBeVisible();
    await expect(page.getByText('上下文 · invocation / /value', { exact: true })).toBeVisible();

    await page.getByRole('button', { name: '撤销本地编辑' }).click();
    await expect(page.getByRole('tab', { name: /绑定 0 个绑定/ })).toBeVisible();
    await page.getByRole('button', { name: '重做本地编辑' }).click();
    await expect(page.getByRole('tab', { name: /绑定 1 个绑定/ })).toBeVisible();

    const saveResponsePromise = page.waitForResponse(
      templateSaveResponse(created.templateId, 200),
    );
    await page.getByRole('button', { name: '保存 canonical 本地草稿' }).click();
    const saved = await (await saveResponsePromise).json() as TemplateCurrentBody;
    expect(requiredAuthoredNode(saved.designDsl, textId).bindings).toEqual([{
      bindingId: expect.stringMatching(
        /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/,
      ),
      targetPropertyRef: {
        rootPropertyId: 'runs',
        selectors: [{ kind: 'index', index: 0 }, { kind: 'member', name: 'text' }],
      },
      source: { kind: 'context', domain: 'invocation', pointer: '/value' },
    }]);

    const reloadResponse = page.waitForResponse(templateCurrentResponse(created.templateId));
    await page.reload({ waitUntil: 'domcontentloaded' });
    expect((await reloadResponse).status()).toBe(200);
    await page.getByRole('treeitem', { name: /文本 1/ }).click();
    await page.getByRole('tab', { name: /绑定 1 个绑定/ }).click();
    await expect(page.getByText('runs[0].text', { exact: true })).toBeVisible();

    const reloadedResponse = await page.request.get(`/api/v1/templates/${created.templateId}`);
    expect(reloadedResponse.status()).toBe(200);
    const reloaded = await reloadedResponse.json() as TemplateCurrentBody;
    expect(requiredAuthoredNode(reloaded.designDsl, textId).bindings)
      .toEqual(requiredAuthoredNode(saved.designDsl, textId).bindings);
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

async function createTemplateForVisualAuthoring(
  request: APIRequestContext,
): Promise<TemplateCurrentBody> {
  const initialDesign = JSON.stringify({
    dslVersion: 'renderweave-design/1.0',
    expressionProfile: 'renderweave-expression/1.0',
    displayName: 'Visual authoring E2E',
    definitions: [],
    designRoot: {
      nodeId: '92000000-0000-4000-8000-000000000001',
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
  const current = await response.json() as TemplateCurrentBody;
  expect(current).toMatchObject({ revision: 0, readiness: 'READY' });
  return current;
}

async function createTemplateForBindingAuthoring(
  request: APIRequestContext,
): Promise<TemplateCurrentBody> {
  const initialDesign = JSON.stringify({
    dslVersion: 'renderweave-design/1.0',
    expressionProfile: 'renderweave-expression/1.0',
    displayName: 'Binding authoring E2E',
    definitions: [],
    designRoot: {
      nodeId: '93000000-0000-4000-8000-000000000001',
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

async function createE2eAsset(
  request: APIRequestContext,
  kind: AssetCurrentBody['kind'],
  displayName: string,
  idempotencyKey: string,
  sourceFileName: string,
  mimeType: string,
  content: Buffer,
): Promise<AssetCurrentBody> {
  const response = await request.post('/api/v1/assets', {
    headers: { 'Idempotency-Key': idempotencyKey },
    multipart: {
      kind,
      displayName,
      sourceFileName,
      content: {
        name: sourceFileName,
        mimeType,
        buffer: content,
      },
    },
  });
  expect(response.status()).toBe(201);
  const asset = await response.json() as AssetCurrentBody;
  expect(asset).toMatchObject({
    assetId: expect.stringMatching(
      /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/,
    ),
    disclosure: 'READABLE',
    kind,
    lifecycle: 'ACTIVE',
    displayName,
  });
  return asset;
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

function expectVisualAuthoringResult(
  current: TemplateCurrentBody,
  expected: {
    textId: string;
    imageNodeId: string;
    ellipseId: string;
    fontAssetId: string;
    imageAssetId: string;
    ellipseGeometry: AbsoluteGeometry;
  },
): void {
  expect(current).toMatchObject({ revision: 1, readiness: 'READY' });
  const root = authoredNode(current.designDsl.designRoot, 'visual DesignDSL root');
  expect(root).toMatchObject({
    nodeId: '92000000-0000-4000-8000-000000000001',
    kind: 'canvas',
    widthMm: 210,
    heightMm: 297,
    bindings: [],
  });
  expect(root.children).toHaveLength(11);
  expect(root.children?.map(({ kind }) => kind)).toEqual([
    'text',
    'image',
    'ellipse',
    'rect',
    'line',
    'polygon',
    'polyline',
    'path',
    'qrCode',
    'barcode',
    'polygon',
  ]);
  expect(root.children?.map(({ displayName }) => displayName)).toEqual([
    '文本 1',
    '图片 1',
    '椭圆 1',
    '矩形 1',
    '直线 1',
    '多边形 1',
    '折线 1',
    '路径 1',
    '二维码 1',
    '条形码 1',
    '星形 2',
  ]);

  const children = (root.children ?? []).map((child, index) => (
    authoredNode(child, `visual child ${index}`)
  ));
  expect(new Set(children.map(({ nodeId }) => nodeId)).size).toBe(11);
  for (const child of children) {
    expect(child.nodeId).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/,
    );
    expect(child.bindings).toEqual([]);
    expect(child).not.toHaveProperty('children');
  }

  const text = visualChild(children, 0, 'Text');
  expect(text).toEqual({
    nodeId: expected.textId,
    kind: 'text',
    displayName: '文本 1',
    bindings: [],
    placement: fixedVisualPlacement(60, 20),
    runs: [{
      text: '会员价 ¥19.90',
      fontRef: { assetId: expected.fontAssetId },
      fontSizePt: 12,
      color: '#000000FF',
      decoration: 'NONE',
      letterSpacingPt: 0,
    }],
    writingMode: 'HORIZONTAL_TB',
    horizontalAlign: 'LEFT',
    verticalAlign: 'TOP',
    lineBreak: 'WORD',
    overflow: 'CLIP',
    lineHeight: { type: 'FACTOR', factor: 1.2 },
    padding: { topMm: 0, rightMm: 0, bottomMm: 0, leftMm: 0 },
    fitMode: 'NONE',
  });

  const image = visualChild(children, 1, 'Image');
  expect(image).toEqual({
    nodeId: expected.imageNodeId,
    kind: 'image',
    displayName: '图片 1',
    bindings: [],
    placement: fixedVisualPlacement(40, 30),
    imageRef: { assetId: expected.imageAssetId },
    fit: 'COVER',
    sampling: 'LINEAR',
  });

  const ellipse = visualChild(children, 2, 'Ellipse');
  expect(ellipse).toEqual({
    nodeId: expected.ellipseId,
    kind: 'ellipse',
    displayName: '椭圆 1',
    bindings: [],
    placement: {
      type: 'ABSOLUTE',
      widthMode: 'FIXED',
      heightMode: 'FIXED',
      ...expected.ellipseGeometry,
    },
    fill: { color: '#2563EBFF' },
  });

  expect(visualChild(children, 3, 'Rect')).toMatchObject({
    kind: 'rect',
    placement: fixedVisualPlacement(25.4, 25.4),
    fill: { color: '#2563EBFF' },
  });
  expectVisualKeys(children[3], ['fill']);

  expect(visualChild(children, 4, 'Line')).toMatchObject({
    kind: 'line',
    placement: fixedVisualPlacement(40, 10),
    start: { xMm: 0, yMm: 0 },
    end: { xMm: 40, yMm: 10 },
    stroke: defaultVisualStroke(),
  });
  expectVisualKeys(children[4], ['start', 'end', 'stroke']);

  expect(visualChild(children, 5, 'Polygon')).toMatchObject({
    kind: 'polygon',
    placement: fixedVisualPlacement(30, 25),
    points: [
      { xMm: 15, yMm: 0 },
      { xMm: 30, yMm: 25 },
      { xMm: 0, yMm: 25 },
    ],
    fill: { color: '#2563EBFF' },
  });
  expectVisualKeys(children[5], ['points', 'fill']);

  expect(visualChild(children, 6, 'Polyline')).toMatchObject({
    kind: 'polyline',
    placement: fixedVisualPlacement(30, 20),
    points: [
      { xMm: 0, yMm: 20 },
      { xMm: 15, yMm: 0 },
      { xMm: 30, yMm: 20 },
    ],
    stroke: defaultVisualStroke(),
  });
  expectVisualKeys(children[6], ['points', 'stroke']);

  const pathNode = visualChild(children, 7, 'Path');
  expect(pathNode).toMatchObject({
    kind: 'path',
    placement: fixedVisualPlacement(32, 24),
    commands: [
      { type: 'MOVE_TO', xMm: 0, yMm: 24 },
      {
        type: 'CUBIC_TO',
        c1xMm: 8,
        c1yMm: 0,
        c2xMm: 24,
        c2yMm: 0,
        xMm: 32,
        yMm: 24,
      },
      { type: 'CLOSE' },
    ],
    fill: { color: '#2563EBFF' },
    fillRule: 'NONZERO',
  });
  expect(pathNode).not.toHaveProperty('pathData');
  expectVisualKeys(pathNode, ['commands', 'fill', 'fillRule']);

  expect(visualChild(children, 8, 'QRCode')).toMatchObject({
    kind: 'qrCode',
    placement: fixedVisualPlacement(25, 25),
    content: 'RenderWeave',
    errorCorrectionLevel: 'M',
    foregroundColor: '#000000FF',
    backgroundColor: '#FFFFFFFF',
  });
  expectVisualKeys(children[8], [
    'content', 'errorCorrectionLevel', 'foregroundColor', 'backgroundColor',
  ]);

  expect(visualChild(children, 9, 'Barcode')).toMatchObject({
    kind: 'barcode',
    placement: fixedVisualPlacement(50, 20),
    format: 'CODE_128',
    value: 'RENDERWEAVE',
    foregroundColor: '#000000FF',
    backgroundColor: '#FFFFFFFF',
  });
  expectVisualKeys(children[9], [
    'format', 'value', 'foregroundColor', 'backgroundColor',
  ]);

  const shape = visualChild(children, 10, 'Shape Polygon');
  expect(shape).toMatchObject({
    kind: 'polygon',
    displayName: '星形 2',
    placement: fixedVisualPlacement(30, 30),
    fill: { color: '#2563EBFF' },
  });
  expect(shape.points).toHaveLength(10);
  expect(shape).not.toHaveProperty('shape');
  expect(shape).not.toHaveProperty('preset');
  expectVisualKeys(shape, ['points', 'fill']);
}

function visualChild(
  children: readonly AuthoredNodeBody[],
  index: number,
  label: string,
): AuthoredNodeBody {
  return authoredNode(children[index], label);
}

function fixedVisualPlacement(widthMm: number, heightMm: number): Record<string, unknown> {
  return {
    type: 'ABSOLUTE',
    xMm: 25.4,
    yMm: 25.4,
    widthMode: 'FIXED',
    widthMm,
    heightMode: 'FIXED',
    heightMm,
  };
}

function defaultVisualStroke(): Record<string, unknown> {
  return { color: '#172033FF', widthMm: 0.5, cap: 'ROUND', join: 'ROUND' };
}

function expectVisualKeys(
  value: AuthoredNodeBody | undefined,
  visualKeys: readonly string[],
): void {
  const node = authoredNode(value, 'visual node key set');
  expect(Object.keys(node).sort()).toEqual([
    'nodeId',
    'kind',
    'displayName',
    'bindings',
    'placement',
    ...visualKeys,
  ].sort());
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

function requiredAuthoredNode(designDsl: Record<string, unknown>, nodeId: string): AuthoredNodeBody {
  const visit = (value: unknown): AuthoredNodeBody | null => {
    if (!value || typeof value !== 'object' || Array.isArray(value)) return null;
    const candidate = value as Record<string, unknown>;
    if (candidate.nodeId === nodeId) return authoredNode(candidate, `node ${nodeId}`);
    if (!Array.isArray(candidate.children)) return null;
    for (const child of candidate.children) {
      const found = visit(child);
      if (found) return found;
    }
    return null;
  };
  const found = visit(designDsl.designRoot);
  if (!found) throw new Error(`Expected authored node ${nodeId}`);
  return found;
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
