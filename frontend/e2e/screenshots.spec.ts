import { Page, expect, test } from '@playwright/test';
import { signIn } from './support';

/**
 * Captures the README showcase screenshots into docs/screenshots. Opt-in, because it overwrites tracked images:
 * CAPTURE_SCREENSHOTS=1 E2E_DEMO_PASSWORD=... npx playwright test screenshots
 */
test.skip(!process.env['CAPTURE_SCREENSHOTS'], 'Set CAPTURE_SCREENSHOTS=1 to refresh docs/screenshots.');
test.use({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 2 });

const OUT = '../docs/screenshots';

/** Waits for skeletons to clear and chart animations to settle before a capture. */
async function settle(page: Page): Promise<void> {
  await expect(page.locator('.skeleton')).toHaveCount(0, { timeout: 30_000 });
  await page.waitForTimeout(1200);
}

for (const scheme of ['light', 'dark'] as const) {
  test(`customer dashboard (${scheme})`, async ({ page }) => {
    await page.emulateMedia({ colorScheme: scheme });
    await signIn(page, 'customer01', /\/app\/dashboard$/);
    await expect(page.getByRole('img', { name: /cumulative energy this cycle/i })).toBeVisible({ timeout: 45_000 });
    await settle(page);
    await page.screenshot({ path: `${OUT}/customer-dashboard-${scheme}.png` });
  });
}

test('energy and simulator', async ({ page }) => {
  await page.emulateMedia({ colorScheme: 'light' });
  await signIn(page, 'customer01', /\/app\/dashboard$/);
  await page.getByRole('link', { name: 'Energy & simulator' }).click();
  await page.getByRole('button', { name: 'Run simulation' }).click();
  await expect(page.getByText('Yearly savings')).toBeVisible({ timeout: 30_000 });
  await settle(page);
  await page.screenshot({ path: `${OUT}/energy-simulator.png`, fullPage: true });
});

test('executive overview', async ({ page }) => {
  await page.emulateMedia({ colorScheme: 'dark' });
  await signIn(page, 'admin', /\/ops\/dashboard$/);
  await expect(page.getByRole('heading', { name: /Regional performance/ })).toBeVisible({ timeout: 30_000 });
  await settle(page);
  await page.screenshot({ path: `${OUT}/executive-overview.png`, fullPage: true });
});

test('copilot drawer', async ({ page }) => {
  await page.emulateMedia({ colorScheme: 'light' });
  await signIn(page, 'customer01', /\/app\/dashboard$/);
  await page.getByRole('button', { name: 'Copilot', exact: true }).click();
  const copilot = page.getByRole('dialog', { name: 'Energy Copilot' });
  await copilot.getByLabel('Your question').fill('Why is my bill higher this month?');
  await copilot.getByRole('button', { name: 'Send' }).click();
  await expect(copilot.getByText(/Answered by/)).toBeVisible({ timeout: 45_000 });
  await page.waitForTimeout(600);
  await page.screenshot({ path: `${OUT}/copilot-drawer.png` });
});
