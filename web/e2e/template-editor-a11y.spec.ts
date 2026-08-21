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
