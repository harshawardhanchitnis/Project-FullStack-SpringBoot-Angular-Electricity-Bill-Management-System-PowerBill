import { defineConfig, devices } from '@playwright/test';

/**
 * End-to-end tests run against the Compose stack started with DEMO_MODE=true.
 * Set E2E_DEMO_PASSWORD to the stack's DEMO_PASSWORD and E2E_BASE_URL when it is not on port 8080.
 */
export default defineConfig({
  testDir: './e2e',
  timeout: 60_000,
  expect: { timeout: 10_000 },
  workers: 2,
  retries: process.env['CI'] ? 1 : 0,
  use: {
    baseURL: process.env['E2E_BASE_URL'] ?? 'http://127.0.0.1:8080',
    trace: 'retain-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
});
