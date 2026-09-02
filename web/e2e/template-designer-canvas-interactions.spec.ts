import { expect, test } from '@playwright/test';

test.describe('template designer canvas interactions', () => {
  test('wheel zoom works on the primary Variant A canvas', async ({ page }) => {
    await page.goto('/prototype/template-designer?variant=A');

    const zoom = page.getByLabel('画布缩放');
    await expect(zoom).toHaveValue('175');

    await page.locator('.rwtd-v2-canvas-viewport').hover();
    await page.mouse.wheel(0, -360);

    await expect(zoom).toHaveValue('200');
  });

  test('an ellipse visual follows a vertical resize without escaping its box', async ({ page }) => {
    await page.goto('/prototype/template-designer?variant=A');
    await page.getByRole('button', { name: '元素', exact: true }).click();

    const ellipseCard = page.getByRole('button', { name: /椭圆/ }).last();
    const stage = page.locator('.rwtd-v2-canvas-stage');
    await ellipseCard.scrollIntoViewIfNeeded();
    const stageBox = await stage.boundingBox();
    expect(stageBox).not.toBeNull();
    await ellipseCard.dragTo(stage, {
      targetPosition: {
        x: Math.round(stageBox!.width * 0.58),
        y: Math.round(stageBox!.height * 0.42),
      },
    });

    const ellipse = page.locator('.rwtd-v2-node.kind-ellipse.is-primary');
    const before = await ellipse.boundingBox();
    expect(before).not.toBeNull();

    const southHandle = page.locator('.rwtd-v2-editor-overlay .handle-s');
    const handleBox = await southHandle.boundingBox();
    expect(handleBox).not.toBeNull();
    await page.mouse.move(handleBox!.x + handleBox!.width / 2, handleBox!.y + handleBox!.height / 2);
    await page.mouse.down();
    await page.mouse.move(handleBox!.x + handleBox!.width / 2, handleBox!.y - 36, { steps: 8 });
    await page.mouse.up();

    const geometry = await ellipse.evaluate((node) => {
      const host = node.getBoundingClientRect();
      const visual = node.querySelector('svg')!.getBoundingClientRect();
      return {
        hostHeight: host.height,
        hostTop: host.top,
        visualHeight: visual.height,
        visualTop: visual.top,
        visualBottom: visual.bottom,
        hostBottom: host.bottom,
      };
    });

    expect(geometry.hostHeight).toBeLessThan(before!.height - 20);
    expect(geometry.visualHeight).toBeLessThanOrEqual(geometry.hostHeight + 0.5);
    expect(geometry.visualTop).toBeGreaterThanOrEqual(geometry.hostTop - 0.5);
    expect(geometry.visualBottom).toBeLessThanOrEqual(geometry.hostBottom + 0.5);
  });

  test('reference source details stay out of the main tree and remain inspectable', async ({ page }) => {
    await page.goto('/prototype/template-designer?variant=A');
    await page.getByRole('button', { name: '数据源', exact: true }).click();

    const sourceTree = page.getByRole('tree', { name: /字段树/ });
    await expect(sourceTree.getByRole('button', { name: /展开品牌|折叠品牌/ })).toHaveCount(0);
    await expect(sourceTree.getByText('品牌名称', { exact: true })).toHaveCount(0);

    const viewBrand = page.getByRole('button', { name: '查看数据源 品牌' });
    const brandRow = viewBrand.locator('..');
    const sourceName = brandRow.locator('.rwtd-v2-source-field-copy > strong');
    const sourceKey = brandRow.locator('.rwtd-v2-source-key code');
    await expect(sourceName).toHaveText('品牌');
    await expect(sourceKey).toHaveText('brand');
    expect(Number.parseFloat(await sourceName.evaluate((node) => getComputedStyle(node).fontSize))).toBeGreaterThanOrEqual(11);
    expect(Number.parseFloat(await sourceKey.evaluate((node) => getComputedStyle(node).fontSize))).toBeGreaterThanOrEqual(8);

    await viewBrand.click();
    const dialog = page.getByRole('dialog', { name: '品牌' });
    await expect(dialog.getByRole('heading', { name: '引用结构' })).toBeVisible();
    await expect(dialog.getByText('品牌名称', { exact: true })).toBeVisible();
    await expect(dialog.getByText('name', { exact: true })).toBeVisible();

    await dialog.getByRole('button', { name: '关闭数据源详情' }).click();
    await page.getByRole('button', { name: '查看数据源 优惠卡列表' }).click();
    const listDialog = page.getByRole('dialog', { name: '优惠卡列表' });
    await expect(listDialog.getByRole('heading', { name: '引用结构' })).toBeVisible();
    await expect(listDialog.getByText('优惠名称', { exact: true })).toBeVisible();
    await expect(listDialog.getByText('优惠价', { exact: true })).toBeVisible();
    await expect(listDialog.getByText('角标', { exact: true })).toBeVisible();
  });
});
