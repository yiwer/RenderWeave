/* Throwaway screenshot smoke for /prototype/template-designer — not part of the repo test suite. */
import { chromium } from 'file:///D:/Yiwer/code/RenderWeave/web/node_modules/playwright/index.mjs';
import { mkdirSync } from 'node:fs';

const base = 'http://localhost:5199/prototype/template-designer';
const out = 'D:/Yiwer/code/RenderWeave/.scratch/shots';
mkdirSync(out, { recursive: true });

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1600, height: 950 } });
const errors = [];
page.on('pageerror', (err) => errors.push(`pageerror: ${err.message}`));
page.on('console', (msg) => {
  if (msg.type() === 'error') errors.push(`console: ${msg.text()}`);
});

for (const variant of ['A', 'B', 'C']) {
  await page.goto(`${base}?variant=${variant}`, { waitUntil: 'networkidle' });
  await page.screenshot({ path: `${out}/td-${variant}-clean.png` });
}

// scenario states on variant A: asset-deleted + save confirm dialog
await page.goto(`${base}?variant=A`, { waitUntil: 'networkidle' });
await page.getByRole('button', { name: 'Asset 已删除' }).click();
await page.getByRole('button', { name: '保存', exact: true }).click();
await page.screenshot({ path: `${out}/td-A-invalid-confirm.png` });
await page.getByRole('button', { name: '二次确认 · 保存为 INVALID' }).click();
await page.screenshot({ path: `${out}/td-A-invalid-saved.png` });

// preview success on clean
await page.getByRole('button', { name: '干净', exact: true }).click();
await page.getByRole('button', { name: '权威预览' }).first().click();
await page.waitForTimeout(1300);
await page.getByRole('tab', { name: '权威预览' }).click();
await page.screenshot({ path: `${out}/td-A-preview.png` });

// variant B dock position re-check
await page.goto(`${base}?variant=B`, { waitUntil: 'networkidle' });
await page.screenshot({ path: `${out}/td-B-clean.png` });

console.log(JSON.stringify({ errors }, null, 2));
await browser.close();
