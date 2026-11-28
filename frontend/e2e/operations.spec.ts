import { expect, test } from '@playwright/test';
import { expectNoHorizontalScroll, signIn, watchConsole } from './support';

test('a support agent triages a ticket with the AI assistant and replies', async ({ page }) => {
  const errors = watchConsole(page);
  await signIn(page, 'support', /\/ops\/dashboard$/);
  await expect(page.getByText('Collected · last 12 months')).toBeVisible();
  await page.getByRole('link', { name: 'Support desk & outages' }).click();
  await page.getByLabel('Priority', { exact: true }).selectOption('CRITICAL');
  await page.getByRole('button', { name: 'Apply' }).click();
  await page.locator('tbody tr.clickable').first().getByRole('button').click();
  const drawer = page.getByRole('dialog');
  await drawer.getByRole('button', { name: 'Analyse' }).click();
  await expect(drawer.getByText('Likely cause:')).toBeVisible({ timeout: 45_000 });
  await drawer.getByRole('button', { name: 'Use as reply' }).click();
  await drawer.getByRole('button', { name: 'Send reply' }).click();
  await expect(drawer.getByRole('listitem').filter({ hasText: 'Support agent' }).last()).toBeVisible();
  expect(errors).toEqual([]);
});

test('a billing officer previews a billing run and reviews receivables', async ({ page }) => {
  await signIn(page, 'billing', /\/ops\/dashboard$/);
  await page.getByRole('link', { name: 'Billing & runs' }).click();
  await page.getByRole('tab', { name: 'Billing runs' }).click();
  await page.getByRole('button', { name: 'Preview' }).click();
  await expect(page.locator('app-kpi').filter({ hasText: 'Preview' })).toBeVisible();
  await page.getByRole('tab', { name: 'Bills' }).click();
  await page.getByLabel('Status').selectOption('OVERDUE');
  await page.getByRole('button', { name: 'Filter' }).click();
  await expect(page.locator('tbody tr.clickable').first()).toBeVisible();
  await page.getByRole('link', { name: 'Tariff studio' }).click();
  await expect(page.getByText(/DEMO-DOMESTIC · version 1/)).toBeVisible();
});

test('a field engineer works the anomaly queue', async ({ page }) => {
  await signIn(page, 'field', /\/ops\/dashboard$/);
  await page.getByRole('link', { name: 'VEE & anomalies' }).click();
  await page.getByRole('tab', { name: 'All' }).click();
  await expect(page.getByRole('heading', { name: 'VEE & anomalies' })).toBeVisible();
  await expect(page.getByRole('link', { name: 'Tariff studio' })).toHaveCount(0);
  await page.goto('/ops/users');
  await expect(page).toHaveURL(/\/ops\/dashboard$/);
});

for (const width of [375, 768, 1440]) {
  test(`operations pages fit a ${width}px viewport with a clean console`, async ({ page }) => {
    const errors = watchConsole(page);
    await page.setViewportSize({ width, height: 900 });
    await signIn(page, 'admin', /\/ops\/dashboard$/);
    await expect(page.getByRole('heading', { name: /Regional performance/ })).toBeVisible();
    await expectNoHorizontalScroll(page);
    for (const path of ['/ops/consumers', '/ops/billing', '/ops/tariffs', '/ops/anomalies', '/ops/support', '/ops/audit', '/ops/users']) {
      await page.goto(path);
      await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
      await expectNoHorizontalScroll(page);
    }
    expect(errors).toEqual([]);
  });
}

test('a super administrator audits activity at phone width', async ({ page }) => {
  await page.setViewportSize({ width: 375, height: 900 });
  await signIn(page, 'admin', /\/ops\/dashboard$/);
  await page.goto('/ops/audit');
  await page.getByLabel('Filter').fill('login');
  await page.getByRole('button', { name: 'Filter' }).click();
  await expect(page.getByRole('cell', { name: 'Login succeeded' }).first()).toBeVisible();
  await expectNoHorizontalScroll(page);
  await page.goto('/ops/users');
  await expect(page.getByRole('cell', { name: 'billing@demo.powerbill.invalid' })).toBeVisible();
});
