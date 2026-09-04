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
const STRUCTURAL_PUBLIC_DEFINITION_ID = '94000000-0000-4000-8000-000000000001';
const STRUCTURAL_PUBLIC_DEFINITION_NAME = '商品名称填充';
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

interface ProjectedInlineRect {
  left: string;
  top: string;
  width: string;
  height: string;
}

interface StructuralAuthoringSetup {
  parent: TemplateCurrentBody;
  child: TemplateCurrentBody;
  childDisplayName: string;
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
    // The deliberately INVALID complete-wire fixture may probe unresolved Font/Image
    // AssetRefs. Treat any resulting 404 reports as allowed browser noise without
    // requiring a transport-dependent count.
    expect(browserErrors.filter((message) => (
      !message.includes('status of 422') && !message.includes('status of 404')
    ))).toEqual([]);
  });

  test('authors Frame, Stack, Grid and Rect through the production shell and reloads exact layout', async ({ page }) => {
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

    await page.getByRole('button', { name: '容器', exact: true }).click();
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
    await page.getByRole('button', { name: '容器', exact: true }).click();
    await page.getByRole('button', { name: '添加堆叠容器' }).click();
    const stackRow = page.getByRole('treeitem', { name: /堆叠 1/ });
    const stackId = requiredAttribute(await stackRow.getAttribute('data-template-editor-node-id'));

    await frameRow.click();
    await page.getByRole('button', { name: '容器', exact: true }).click();
    await page.getByRole('button', { name: '添加网格容器' }).click();
    const gridRow = page.getByRole('treeitem', { name: /网格 1/ });
    const gridId = requiredAttribute(await gridRow.getAttribute('data-template-editor-node-id'));
    await page.getByLabel('列轨道', { exact: true }).fill('20, 1*');
    await page.getByLabel('列轨道', { exact: true }).press('Enter');
    await page.getByLabel('列间距', { exact: true }).fill('2');
    await page.getByLabel('列间距', { exact: true }).press('Enter');

    await page.getByRole('button', { name: '元素' }).click();
    await page.getByRole('button', { name: '添加矩形' }).click();
    const gridRectRow = page.getByRole('treeitem', { name: /矩形 3/ });
    const gridRectId = requiredAttribute(
      await gridRectRow.getAttribute('data-template-editor-node-id'),
    );
    await page.getByLabel('宽度模式', { exact: true }).click();
    await page.getByRole('listbox', { name: '宽度模式' })
      .getByRole('option', { name: '填充可用空间' })
      .click();
    await page.getByLabel('网格列', { exact: true }).fill('1');
    await page.getByLabel('网格列', { exact: true }).press('Enter');
    const gridRectProjection = await readProjectedInlineRect(page, gridRectId);

    await firstRectRow.press('F2');
    const rename = page.getByRole('textbox', { name: '重命名 矩形 1' });
    await rename.fill('堆叠项');
    await rename.press('Enter');
    const renamedRectRow = page.getByRole('treeitem', { name: /堆叠项/ });
    await expect(renamedRectRow).toHaveAttribute('data-template-editor-node-id', firstRectId);

    // Rect 2 is still ABSOLUTE under Frame here. Exercise direct canvas geometry
    // before moving the other Rect into the live Stack layout.
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
    const stackedRectProjection = await readProjectedInlineRect(page, firstRectId);

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
      gridId,
      gridRectId,
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
    await expect(page.getByRole('treeitem', { name: /网格 1/ })).toBeVisible();
    await expect.poll(() => readProjectedInlineRect(page, firstRectId))
      .toEqual(stackedRectProjection);
    await expect.poll(() => readProjectedInlineRect(page, gridRectId)).toEqual(gridRectProjection);
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
      gridId,
      gridRectId,
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
      `[data-template-canvas-node-id="${ellipseId}"] [data-template-visual-kind="ellipse"] ellipse[data-template-vector-layer="fill"]`,
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

  test('authors Repeat, nested TemplateUse fills and Conditional suppression through the production shell', async ({ page }) => {
    test.setTimeout(120_000);
    const browserErrors = captureBrowserErrors(page);
    await page.setViewportSize({ width: 1440, height: 900 });

    const setup = await createTemplateForStructuralAuthoring(page.request);
    const initialOpen = page.waitForResponse(templateCurrentResponse(setup.parent.templateId));
    await page.goto(`/templates/${setup.parent.templateId}`, { waitUntil: 'domcontentloaded' });
    expect((await initialOpen).status()).toBe(200);
    await expect(page.getByRole('main', { name: 'Template 编辑工作区' })).toBeVisible();
    await expect(page.getByText('revision 0', { exact: true })).toBeVisible();

    const rootRow = page.locator('[role="treeitem"][data-kind="canvas"]');

    // Scalar Repeat: the source is the exact list<text> field, while its authored
    // PACK subtree remains one copy regardless of the local occurrence projection.
    await rootRow.click();
    await page.getByRole('button', { name: '容器', exact: true }).click();
    await page.getByRole('button', { name: '添加循环容器' }).click();
    const scalarRepeatRow = page.locator('[role="treeitem"][data-kind="repeat"]').nth(0);
    await expect(scalarRepeatRow).toHaveAttribute('aria-selected', 'true');
    const scalarRepeatId = requiredAttribute(
      await scalarRepeatRow.getAttribute('data-template-editor-node-id'),
    );
    await selectOptionContaining(page, page.getByLabel('循环列表属性', { exact: true }), '/tags');
    await selectOptionContaining(page, page.getByLabel('单项排列方向', { exact: true }), '纵向');
    await commitInput(page.getByLabel('单项间距', { exact: true }), '1.5');
    await selectOptionContaining(page, page.getByLabel('循环布局方式', { exact: true }), '网格');
    await commitInput(page.getByLabel('循环网格列数', { exact: true }), '2');
    await commitInput(page.getByLabel('循环列间距', { exact: true }), '3');
    await commitInput(page.getByLabel('循环行间距', { exact: true }), '4');

    await page.getByRole('button', { name: '元素' }).click();
    await page.getByRole('button', { name: '添加矩形' }).click();
    const firstScalarChild = page.getByRole('treeitem', { selected: true });
    const firstScalarChildId = requiredAttribute(
      await firstScalarChild.getAttribute('data-template-editor-node-id'),
    );
    await scalarRepeatRow.click();
    await page.getByRole('button', { name: '元素' }).click();
    await page.getByRole('button', { name: '添加矩形' }).click();
    const secondScalarChild = page.getByRole('treeitem', { selected: true });
    const secondScalarChildId = requiredAttribute(
      await secondScalarChild.getAttribute('data-template-editor-node-id'),
    );

    // Reference Repeat: choosing an exact-schema READY child creates one explicit
    // TemplateUse whose selector is the whole loop context. Each PUBLIC target is
    // filled from a statically compatible field in that same lexical loop domain.
    await rootRow.click();
    await page.getByRole('button', { name: '容器', exact: true }).click();
    await page.getByRole('button', { name: '添加循环容器' }).click();
    const referenceRepeatRow = page.locator('[role="treeitem"][data-kind="repeat"]').nth(1);
    const referenceRepeatId = requiredAttribute(
      await referenceRepeatRow.getAttribute('data-template-editor-node-id'),
    );
    await selectOptionContaining(page, page.getByLabel('循环列表属性', { exact: true }), '/products');
    await selectOptionContaining(page, page.getByLabel('循环单项模板', { exact: true }), setup.childDisplayName);
    const templateUseRow = page.locator('[role="treeitem"][data-kind="templateUse"]');
    await expect(templateUseRow).toBeVisible();
    const templateUseId = requiredAttribute(
      await templateUseRow.getAttribute('data-template-editor-node-id'),
    );
    await templateUseRow.click();
    await expect(page.getByText(setup.childDisplayName, { exact: true })).toBeVisible();
    await selectOptionContaining(
      page, page.getByLabel(`${STRUCTURAL_PUBLIC_DEFINITION_NAME} 来源`, { exact: true }),
      '/label',
    );

    // Conditional preview input is editor-local. FALSE and ABSENT suppress the
    // projected branch without deleting the authored child in the structure tree.
    await rootRow.click();
    await page.getByRole('button', { name: '容器', exact: true }).click();
    await page.getByRole('button', { name: '添加条件容器' }).click();
    const conditionalRow = page.locator('[role="treeitem"][data-kind="conditional"]');
    const conditionalId = requiredAttribute(
      await conditionalRow.getAttribute('data-template-editor-node-id'),
    );
    await selectOptionContaining(page, page.getByLabel('条件数据源', { exact: true }), '/showDetails');
    await selectOptionContaining(page, page.getByLabel('条件缺失策略', { exact: true }), '按 FALSE 剪枝');
    await page.getByRole('button', { name: '元素' }).click();
    await page.getByRole('button', { name: '添加矩形' }).click();
    const selectedConditionalChildRow = page.getByRole('treeitem', { selected: true });
    const conditionalChildId = requiredAttribute(
      await selectedConditionalChildRow.getAttribute('data-template-editor-node-id'),
    );
    const conditionalChildRow = page.locator(
      `[role="treeitem"][data-template-editor-node-id="${conditionalChildId}"]`,
    );
    await conditionalRow.click();
    const previewInput = page.getByRole('group', { name: '条件预览输入' });
    const conditionalCanvasChild = page.locator(
      `[data-template-canvas-authored-node][data-template-canvas-node-id="${conditionalChildId}"]`,
    );
    await previewInput.getByRole('button', { name: 'TRUE', exact: true }).click();
    await expect(conditionalCanvasChild).toBeVisible();
    await previewInput.getByRole('button', { name: 'FALSE', exact: true }).click();
    await expect(conditionalCanvasChild).toHaveCount(0);
    await expect(conditionalChildRow).toBeVisible();
    await previewInput.getByRole('button', { name: 'ABSENT', exact: true }).click();
    await expect(conditionalCanvasChild).toHaveCount(0);
    await expect(conditionalChildRow).toBeVisible();

    const saveResponsePromise = page.waitForResponse(
      templateSaveResponse(setup.parent.templateId, 200),
    );
    await page.getByRole('button', { name: '保存 canonical 本地草稿' }).click();
    const saved = await (await saveResponsePromise).json() as TemplateCurrentBody;
    expect(saved).toMatchObject({ revision: 1, readiness: 'READY' });
    expectStructuralAuthoringResult(saved, {
      scalarRepeatId,
      scalarChildIds: [firstScalarChildId, secondScalarChildId],
      referenceRepeatId,
      templateUseId,
      childTemplateId: setup.child.templateId,
      conditionalId,
      conditionalChildId,
    });

    const reloadResponse = page.waitForResponse(templateCurrentResponse(setup.parent.templateId));
    await page.reload({ waitUntil: 'domcontentloaded' });
    expect((await reloadResponse).status()).toBe(200);
    await expect(page.getByText('revision 1', { exact: true })).toBeVisible();
    for (const nodeId of [
      scalarRepeatId,
      firstScalarChildId,
      secondScalarChildId,
      referenceRepeatId,
      templateUseId,
      conditionalId,
      conditionalChildId,
    ]) {
      await expect(page.locator(
        `[role="treeitem"][data-template-editor-node-id="${nodeId}"]`,
      )).toBeVisible();
    }

    const reloadedResponse = await page.request.get(
      `/api/v1/templates/${setup.parent.templateId}`,
    );
    expect(reloadedResponse.status()).toBe(200);
    const reloaded = await reloadedResponse.json() as TemplateCurrentBody;
    expect(reloaded.contentHash).toBe(saved.contentHash);
    expect(reloaded.designDsl).toEqual(saved.designDsl);
    expectStructuralAuthoringResult(reloaded, {
      scalarRepeatId,
      scalarChildIds: [firstScalarChildId, secondScalarChildId],
      referenceRepeatId,
      templateUseId,
      childTemplateId: setup.child.templateId,
      conditionalId,
      conditionalChildId,
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

async function createTemplateForStructuralAuthoring(
  request: APIRequestContext,
): Promise<StructuralAuthoringSetup> {
  const suffix = `${Date.now().toString(36)}-${process.pid.toString(36)}`;
  const itemSchemaKey = `t226-item-${suffix}`;
  const parentSchemaKey = `t226-parent-${suffix}`;
  const childDisplayName = `T226 商品项 ${suffix}`;

  await publishStaticSchema(request, itemSchemaKey, {
    dslVersion: 'renderweave-schema/1.0',
    displayName: `T226 商品项 ${suffix}`,
    fields: [{
      fieldKey: 'label',
      displayName: '商品名称',
      required: true,
      value: { type: 'text' },
    }],
  });
  await publishStaticSchema(request, parentSchemaKey, {
    dslVersion: 'renderweave-schema/1.0',
    displayName: `T226 结构输入 ${suffix}`,
    fields: [
      {
        fieldKey: 'tags',
        displayName: '标签',
        required: true,
        value: { type: 'array', items: { type: 'text' } },
      },
      {
        fieldKey: 'products',
        displayName: '商品',
        required: true,
        value: {
          type: 'array',
          items: {
            type: 'reference',
            ref: { schemaKey: itemSchemaKey, versionTag: 'v1' },
          },
        },
      },
      {
        fieldKey: 'showDetails',
        displayName: '显示详情',
        required: false,
        value: { type: 'boolean' },
      },
    ],
  });

  const child = await createTemplateWithSchema(request, itemSchemaKey, {
    dslVersion: 'renderweave-design/1.0',
    expressionProfile: 'renderweave-expression/1.0',
    displayName: childDisplayName,
    definitions: [{
      definitionId: STRUCTURAL_PUBLIC_DEFINITION_ID,
      kind: 'custom',
      displayName: STRUCTURAL_PUBLIC_DEFINITION_NAME,
      exposure: 'PUBLIC',
      valueType: 'text',
      defaultValue: '未命名商品',
    }],
    designRoot: {
      nodeId: '94000000-0000-4000-8000-000000000002',
      kind: 'canvas',
      widthMm: 40,
      heightMm: 20,
      bindings: [],
      children: [],
    },
  });
  expect(child).toMatchObject({ revision: 0, readiness: 'READY' });

  const parent = await createTemplateWithSchema(request, parentSchemaKey, {
    dslVersion: 'renderweave-design/1.0',
    expressionProfile: 'renderweave-expression/1.0',
    displayName: `T226 结构 authoring ${suffix}`,
    definitions: [],
    designRoot: {
      nodeId: '95000000-0000-4000-8000-000000000001',
      kind: 'canvas',
      widthMm: 210,
      heightMm: 297,
      bindings: [],
      children: [],
    },
  });
  expect(parent).toMatchObject({ revision: 0, readiness: 'READY' });
  return { parent, child, childDisplayName };
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
      releaseNote: 'T226 structural authoring live E2E',
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

async function readProjectedInlineRect(
  page: Page,
  nodeId: string,
): Promise<ProjectedInlineRect> {
  return page.locator(
    `[data-template-canvas-authored-node][data-template-canvas-node-id="${nodeId}"]`,
  ).evaluate((element) => {
    const style = (element as HTMLElement).style;
    return {
      left: style.left,
      top: style.top,
      width: style.width,
      height: style.height,
    };
  });
}

async function numericInputValue(page: Page, label: string): Promise<number> {
  const raw = await page.getByLabel(label, { exact: true }).inputValue();
  const value = Number(raw);
  if (!Number.isFinite(value)) throw new Error(`${label} did not expose a finite number: ${raw}`);
  return value;
}

async function commitInput(input: Locator, value: string): Promise<void> {
  await input.fill(value);
  await input.press('Enter');
}

async function selectOptionContaining(page: Page, control: Locator, expectedText: string): Promise<void> {
  const ariaLabel = await control.getAttribute('aria-label');
  if (!ariaLabel) throw new Error(`SelectField for ${expectedText} has no aria-label`);
  await control.click();
  const option = page.getByRole('listbox', { name: ariaLabel }).getByRole('option').filter({ hasText: expectedText });
  await expect(option).toHaveCount(1);
  await option.click();
}

function expectStructuralAuthoringResult(
  current: TemplateCurrentBody,
  expected: {
    scalarRepeatId: string;
    scalarChildIds: [string, string];
    referenceRepeatId: string;
    templateUseId: string;
    childTemplateId: string;
    conditionalId: string;
    conditionalChildId: string;
  },
): void {
  const root = authoredNode(current.designDsl.designRoot, 'designRoot');
  expect(root.children?.map((child) => child.nodeId)).toEqual([
    expected.scalarRepeatId,
    expected.referenceRepeatId,
    expected.conditionalId,
  ]);

  const scalarRepeat = requiredAuthoredNode(current.designDsl, expected.scalarRepeatId);
  expect(scalarRepeat).toMatchObject({
    kind: 'repeat',
    items: { kind: 'context', domain: 'invocation', pointer: '/tags' },
    absentPolicy: 'EMPTY',
    itemLayout: { kind: 'STACK', direction: 'COLUMN', gapMm: 1.5 },
    instanceLayout: { kind: 'GRID', columns: 2, columnGapMm: 3, rowGapMm: 4 },
  });
  expect(scalarRepeat.children?.map((child) => child.nodeId)).toEqual(expected.scalarChildIds);
  for (const childId of expected.scalarChildIds) {
    expect(requiredAuthoredNode(current.designDsl, childId)).toMatchObject({
      kind: 'rect',
      placement: { type: 'PACK' },
    });
  }

  const referenceRepeat = requiredAuthoredNode(current.designDsl, expected.referenceRepeatId);
  expect(referenceRepeat).toMatchObject({
    kind: 'repeat',
    items: { kind: 'context', domain: 'invocation', pointer: '/products' },
    absentPolicy: 'EMPTY',
  });
  expect(referenceRepeat.loopId).toEqual(expect.stringMatching(
    /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/,
  ));
  expect(referenceRepeat.children?.map((child) => child.nodeId)).toEqual([
    expected.templateUseId,
  ]);

  const templateUse = requiredAuthoredNode(current.designDsl, expected.templateUseId);
  expect(templateUse).toMatchObject({
    kind: 'templateUse',
    templateRef: { templateId: expected.childTemplateId },
    contextSelector: {
      kind: 'context',
      domain: { kind: 'loop', loopId: referenceRepeat.loopId },
      pointer: '',
      contextAbsentPolicy: 'SKIP',
    },
    fills: [{
      targetDefinitionId: STRUCTURAL_PUBLIC_DEFINITION_ID,
      source: {
        kind: 'context',
        domain: { kind: 'loop', loopId: referenceRepeat.loopId },
        pointer: '/label',
      },
    }],
    placement: { type: 'PACK' },
  });
  expect(templateUse.children).toBeUndefined();

  const conditional = requiredAuthoredNode(current.designDsl, expected.conditionalId);
  expect(conditional).toMatchObject({
    kind: 'conditional',
    condition: { kind: 'context', domain: 'invocation', pointer: '/showDetails' },
    absentPolicy: 'FALSE',
  });
  expect(conditional.children?.map((child) => child.nodeId)).toEqual([
    expected.conditionalChildId,
  ]);
  expect(requiredAuthoredNode(current.designDsl, expected.conditionalChildId)).toMatchObject({
    kind: 'rect',
    placement: { type: 'ABSOLUTE' },
  });
}

function expectCoreAuthoringResult(
  current: TemplateCurrentBody,
  expected: {
    frameId: string;
    stackId: string;
    gridId: string;
    gridRectId: string;
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
    expected.gridId,
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

  const grid = authoredNode(frame.children?.[1], 'Grid child');
  expect(grid).toMatchObject({
    nodeId: expected.gridId,
    kind: 'grid',
    displayName: '网格 1',
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
    rows: [{ type: 'FRACTION', weight: 1 }],
    columns: [
      { type: 'FIXED', valueMm: 20 },
      { type: 'FRACTION', weight: 1 },
    ],
    rowGapMm: 0,
    columnGapMm: 2,
  });
  expect(grid.children).toHaveLength(1);
  expect(authoredNode(grid.children?.[0], 'Grid Rect child')).toMatchObject({
    nodeId: expected.gridRectId,
    kind: 'rect',
    displayName: '矩形 3',
    placement: {
      type: 'GRID',
      row: 0,
      column: 1,
      widthMode: 'FILL',
      heightMode: 'FIXED',
      heightMm: 25.4,
    },
  });

  expect(authoredNode(frame.children?.[2], 'absolute Rect child')).toMatchObject({
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
