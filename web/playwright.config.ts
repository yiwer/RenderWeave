import { defineConfig, devices } from '@playwright/test';

const port = process.env.RENDERWEAVE_WEB_PORT ?? '4173';
const baseURL = `http://127.0.0.1:${port}`;
const outputDir = process.env.RENDERWEAVE_PLAYWRIGHT_OUTPUT_DIR ?? 'test-results';
const htmlOutputDir = process.env.RENDERWEAVE_PLAYWRIGHT_HTML_DIR ?? 'playwright-report';

export default defineConfig({
  testDir: './e2e',
  outputDir,
  fullyParallel: false,
  forbidOnly: true,
  retries: 0,
  reporter: [['list'], ['html', { open: 'never', outputFolder: htmlOutputDir }]],
  use: {
    baseURL,
    trace: 'retain-on-failure',
  },
  projects: [
    {
      name: 'chromium-canary',
      use: { ...devices['Desktop Chrome'], viewport: { width: 1280, height: 720 } },
    },
  ],
  webServer: {
    command: `npm run dev -- --host 127.0.0.1 --port ${port}`,
    url: `${baseURL}/prototype/schema-studio?variant=A`,
    reuseExistingServer: true,
  },
});
