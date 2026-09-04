import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Page } from '@playwright/test';

const FIXTURE = '/e2e/fixtures/template-editor-a11y.html';

test.describe('Template Editor E9 browser accessibility', () => {
  test('keeps complete operations and zero serious/critical axe findings at supported widths', async ({ page }) => {
    const browserErrors = captureBrowserErrors(page);

    for (const viewport of [
      { width: 1440, height: 900 },
      { width: 1280, height: 720 },
      { width: 1024, height: 768 },
    ]) {
      await page.setViewportSize(viewport);
      await page.goto(FIXTURE);
      await expect(page.getByRole('main', { name: 'Template 编辑工作区' })).toBeVisible();
      await expect(page.getByText('Template 编辑器需要更宽的工作区')).toBeHidden();
      await expect(page.getByRole('button', { name: '保存 canonical 本地草稿' })).toBeVisible();
      await expectNoHorizontalOverflow(page);
      await expectMinimumButtonTargets(page);
      await expectNoSeriousOrCriticalAxe(page);
    }

    const inspectorToggle = page.getByRole('button', { name: '检视器' });
    await inspectorToggle.focus();
    await page.keyboard.press('Enter');
    await expect(inspectorToggle).toHaveAttribute('aria-pressed', 'false');
    await expect(page.getByRole('complementary', { name: '属性检视器' })).toHaveCount(0);
    await page.keyboard.press('Enter');
    await expect(inspectorToggle).toHaveAttribute('aria-pressed', 'true');
    await expect(page.getByRole('complementary', { name: '属性检视器' })).toBeVisible();
    expect(browserErrors).toEqual([]);
  });

  test('supports skip navigation, roving tree keys, failure focus and strict problem location', async ({ page }) => {
    const browserErrors = captureBrowserErrors(page);
    await page.setViewportSize({ width: 1280, height: 720 });
    await page.goto(FIXTURE);

    await page.keyboard.press('Tab');
    await expect(page.getByRole('link', { name: '跳到主要内容' })).toBeFocused();
    await page.keyboard.press('Enter');
    await expect(page.getByRole('main', { name: 'Template 编辑工作区' })).toBeFocused();

    const treeItems = page.getByRole('treeitem');
    await expect(treeItems).toHaveCount(3);
    await treeItems.nth(0).focus();
    await page.keyboard.press('ArrowDown');
    await expect(treeItems.nth(1)).toBeFocused();
    await expect(treeItems.nth(1)).toHaveAttribute('aria-selected', 'true');
    expect(await treeItems.nth(1).evaluate((element) => {
      const style = getComputedStyle(element);
      return style.outlineStyle !== 'none' && style.outlineWidth !== '0px';
    })).toBe(true);
    await page.keyboard.press('End');
    await expect(treeItems.nth(2)).toBeFocused();
    await page.keyboard.press('Home');
    await expect(treeItems.nth(0)).toBeFocused();
    await expect(page.locator('[role="treeitem"][tabindex="0"]')).toHaveCount(1);

    await page.getByRole('button', { name: '保存 canonical 本地草稿' }).click();
    const summary = page.locator('.te-invalid-save-confirmation');
    await expect(summary).toBeVisible();
    await expect(summary).toBeFocused();
    await expect(page.locator('.te-entry-panel')).not.toHaveAttribute('aria-live', /.+/);
    await expectNoSeriousOrCriticalAxe(page);

    await page.getByRole('button', { name: /定位到节点“内容区”/ }).click();
    const locatedNode = page.getByRole('treeitem', { name: /内容区/ });
    await expect(locatedNode).toBeFocused();
    await expect(locatedNode).toHaveAttribute('aria-selected', 'true');
    await expect(page.locator('[data-template-editor-announcer]')).toContainText('具体属性没有独立表单控件');
    expect(browserErrors).toEqual([]);
  });

  test('keeps the production authoring and recovery journey keyboard-complete', async ({ page }) => {
    const browserErrors = captureBrowserErrors(page);
    await page.setViewportSize({ width: 1280, height: 720 });
    await page.goto(`${FIXTURE}?initial=clean`);

    const elementsEntry = page.getByRole('button', { name: '元素' });
    await elementsEntry.focus();
    await page.keyboard.press('Enter');
    const addRect = page.getByRole('button', { name: '添加矩形' });
    await addRect.focus();
    await page.keyboard.press('Enter');

    const row = page.getByRole('treeitem', { name: /矩形 2/ });
    await row.focus();
    await expect(row).toBeFocused();
    await page.keyboard.press('F2');
    const rename = page.getByRole('textbox', { name: '重命名 矩形 2' });
    await rename.fill('键盘矩形');
    await page.keyboard.press('Enter');
    await expect(page.getByRole('treeitem', { name: /键盘矩形/ })).toBeVisible();

    const xCoordinate = page.getByRole('textbox', { name: 'X 坐标', exact: true });
    await xCoordinate.focus();
    await page.keyboard.press('Control+A');
    await page.keyboard.type('18');
    await page.keyboard.press('Enter');
    await expect(xCoordinate).toHaveValue('18');

    const bindX = page.getByRole('button', { name: '绑定X 坐标' });
    await bindX.focus();
    await page.keyboard.press('Enter');
    await expect(page.getByRole('dialog', { name: '绑定X 坐标' })).toBeVisible();
    const offsetSource = page.getByRole('radio', { name: /水平偏移.*\/offset.*数值/ });
    await offsetSource.focus();
    await offsetSource.press('Space');
    await expect(offsetSource).toBeChecked();
    const createBinding = page.getByRole('button', { name: '创建绑定' });
    await createBinding.focus();
    await page.keyboard.press('Enter');
    const bindingsTab = page.getByRole('tab', { name: /绑定.*1 个绑定/ });
    await expect(bindingsTab).toBeVisible();
    await bindingsTab.focus();

    await page.keyboard.press('Control+S');
    await expect(page.getByRole('heading', { name: '确认仍保存为 INVALID' })).toBeVisible();
    const cancelInvalid = page.getByRole('button', { name: '取消 INVALID 保存' });
    await cancelInvalid.focus();
    await page.keyboard.press('Enter');
    await expect(page.getByRole('heading', { name: '确认仍保存为 INVALID' })).toHaveCount(0);
    await expect.poll(() => page.evaluate(() => Object.keys(localStorage)
      .some((key) => key.startsWith('renderweave.template-local-recovery.v1:')))).toBe(true);

    await page.reload();
    await expect(page.getByRole('heading', { name: '发现此设备上的本地恢复草稿' })).toBeVisible();
    const restore = page.getByRole('button', { name: '恢复本地草稿' });
    await restore.focus();
    await expect(restore).toBeFocused();
    await page.keyboard.press('Enter');
    await expect(page.getByText('已恢复此设备上的本地草稿')).toBeVisible();
    await expect(page.getByRole('treeitem', { name: /键盘矩形/ })).toBeVisible();
    await expect(page.getByRole('textbox', { name: 'X 坐标', exact: true })).toHaveValue('18');
    await expect(page.getByRole('tab', { name: /绑定.*1 个绑定/ })).toBeVisible();
    await expectNoSeriousOrCriticalAxe(page);
    expect(browserErrors).toEqual([]);
  });

  test('holds the 1024 CSS-pixel layout at 2x device scale as the automated 200% equivalent', async ({ browser }) => {
    const context = await browser.newContext({
      viewport: { width: 1024, height: 768 },
      deviceScaleFactor: 2,
    });
    const page = await context.newPage();
    const browserErrors = captureBrowserErrors(page);
    try {
      await page.goto(FIXTURE);
      expect(await page.evaluate(() => window.devicePixelRatio)).toBe(2);
      expect(await page.evaluate(() => window.innerWidth)).toBe(1024);
      await expect(page.getByRole('main', { name: 'Template 编辑工作区' })).toBeVisible();
      await expect(page.getByText('Template 编辑器需要更宽的工作区')).toBeHidden();
      await expectNoHorizontalOverflow(page);
      await expectNoSeriousOrCriticalAxe(page);
      expect(browserErrors).toEqual([]);
    } finally {
      await context.close();
    }
  });

  test('shows only the truthful unsupported state below 1024 and removes editor controls from Tab order', async ({ page }) => {
    const browserErrors = captureBrowserErrors(page);
    await page.setViewportSize({ width: 900, height: 768 });
    await page.goto(FIXTURE);

    await expect(page.getByText('Template 编辑器需要更宽的工作区')).toBeVisible();
    await expect(page.getByRole('main', { name: 'Template 编辑工作区' })).toBeHidden();
    await expect(page.getByRole('link', { name: '跳到主要内容' })).toBeHidden();
    await expectNoHorizontalOverflow(page);
    await expectNoSeriousOrCriticalAxe(page);

    for (let index = 0; index < 12; index += 1) {
      await page.keyboard.press('Tab');
      expect(await page.evaluate(() => document.activeElement?.closest(
        '.te-chrome, .te-workbench, .skip-link',
      ) === null)).toBe(true);
    }
    expect(browserErrors).toEqual([]);
  });
});

function captureBrowserErrors(page: Page): string[] {
  const errors: string[] = [];
  page.on('console', (message) => {
    if (message.type() === 'error') errors.push(message.text());
  });
  page.on('pageerror', (error) => errors.push(error.message));
  return errors;
}

async function expectNoSeriousOrCriticalAxe(page: Page): Promise<void> {
  const accessibility = await new AxeBuilder({ page })
    .include('.template-editor-root')
    .withTags(['wcag2a', 'wcag2aa', 'wcag21aa', 'wcag22aa'])
    .analyze();
  expect(accessibility.violations.filter((violation) =>
    violation.impact === 'serious' || violation.impact === 'critical')).toEqual([]);
}

async function expectNoHorizontalOverflow(page: Page): Promise<void> {
  expect(await page.evaluate(() => ({
    body: document.body.scrollWidth <= document.body.clientWidth,
    document: document.documentElement.scrollWidth <= document.documentElement.clientWidth,
  }))).toEqual({ body: true, document: true });
}

async function expectMinimumButtonTargets(page: Page): Promise<void> {
  expect(await page.locator('.template-editor-root button').evaluateAll((buttons) => buttons
    .filter((button) => {
      const style = getComputedStyle(button);
      return style.display !== 'none' && style.visibility !== 'hidden';
    })
    .map((button) => {
      const bounds = button.getBoundingClientRect();
      return { label: button.getAttribute('aria-label') ?? button.textContent, bounds };
    })
    .filter(({ bounds }) => bounds.width < 44 || bounds.height < 44)
    .map(({ label, bounds }) => ({ label, width: bounds.width, height: bounds.height })))).toEqual([]);
}
