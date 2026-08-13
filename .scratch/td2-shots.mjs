/* Throwaway screenshot smoke for the binding-editor iteration. */
import { chromium } from 'file:///D:/Yiwer/code/RenderWeave/web/node_modules/playwright/index.mjs';
import { mkdirSync } from 'node:fs';

const base = 'http://localhost:5173/prototype/template-designer';
const out = 'D:/Yiwer/code/RenderWeave/.scratch/shots';
mkdirSync(out, { recursive: true });

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1600, height: 950 } });
const errors = [];
page.on('pageerror', (err) => errors.push(`pageerror: ${err.message}`));
page.on('console', (msg) => {
  if (msg.type() === 'error') errors.push(`console: ${msg.text()}`);
});

// 1. library grouping (containers / elements / compose)
await page.goto(`${base}?variant=A`, { waitUntil: 'networkidle' });
await page.getByRole('button', { name: '节点库' }).click();
await page.screenshot({ path: `${out}/td2-A-library.png` });

// 2. editable property bar + bound style; open binding editor on titleText
await page.getByRole('button', { name: '结构树' }).click();
await page.getByRole('button', { name: /titleText 标题/ }).first().click();
await page.getByRole('button', { name: '编辑 runs[0].text 的绑定' }).click();
await page.screenshot({ path: `${out}/td2-binding-editor.png` });

// 3. unbind → row returns to baseline, tree link indicator disappears
await page.getByRole('button', { name: '取消绑定' }).click();
await page.screenshot({ path: `${out}/td2-unbound.png` });

// 4. re-bind via 绑定 CTA with a field source
await page.getByRole('button', { name: '为 runs[0].text 添加绑定' }).click();
await page.getByPlaceholder('/title').fill('/tags[0]');
await page.getByRole('button', { name: '保存绑定' }).click();
await page.screenshot({ path: `${out}/td2-rebound.png` });

console.log(JSON.stringify({ errors }, null, 2));
await browser.close();
