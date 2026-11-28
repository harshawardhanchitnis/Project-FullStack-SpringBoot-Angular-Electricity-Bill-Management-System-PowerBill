import { expect, test } from '@playwright/test';
import { signIn, watchConsole } from './support';

test('signing out and in as another customer shows only the new account', async ({ page }) => {
  const errors = watchConsole(page);
  await signIn(page, 'customer03', /\/app\/dashboard$/);
  const subtitle = page.locator('h1 + p');
  await expect(subtitle).toContainText(/M-/);
  const firstMeter = (await subtitle.textContent())!.split(' · ')[0].trim();

  await page.getByRole('button', { name: 'Sign out' }).click();
  await expect(page).toHaveURL(/\/auth\/login/);
  await signIn(page, 'customer05', /\/app\/dashboard$/);
  await expect(subtitle).toContainText(/M-/);
  await expect(subtitle).not.toContainText(firstMeter);
  await page.getByRole('link', { name: 'Account & meters' }).click();
  await expect(page.getByText(firstMeter)).toHaveCount(0);
  expect(errors).toEqual([]);
});

test('the command palette opens with Ctrl+K and jumps to a page', async ({ page }) => {
  await signIn(page, 'billing', /\/ops\/dashboard$/);
  await page.keyboard.press('Control+k');
  const palette = page.getByRole('dialog', { name: 'Go to or search' });
  await expect(palette).toBeVisible();
  await palette.getByLabel('Search pages and records').fill('tariff');
  await expect(palette.getByRole('option', { name: /Tariff studio/ })).toBeVisible();
  await page.keyboard.press('Enter');
  await expect(page).toHaveURL(/\/ops\/tariffs$/);
  await expect(palette).toBeHidden();

  await page.keyboard.press('Control+k');
  await palette.getByLabel('Search pages and records').fill('bills');
  await page.keyboard.press('ArrowDown');
  await page.keyboard.press('Escape');
  await expect(palette).toBeHidden();
});

test('a field engineer inspects a meter in the VEE tools', async ({ page }) => {
  const errors = watchConsole(page);
  await signIn(page, 'field', /\/ops\/dashboard$/);
  await page.getByRole('link', { name: 'VEE & anomalies' }).click();
  await page.getByRole('tab', { name: 'VEE & telemetry' }).click();
  await page.getByLabel('Meter serial').fill('M-DEMO-03-1');
  await page.getByRole('button', { name: 'Find meter' }).click();
  await page.getByRole('list', { name: 'Matching meters' }).getByRole('button').first().click();
  await expect(page.getByRole('heading', { name: /Intervals · M-DEMO-03-1/ })).toBeVisible();
  // The demo seed plants a flagged voltage sag and a communications outage within the last few days.
  await page.getByLabel('Window').selectOption({ label: 'Last 7 days' });
  await expect(page.getByText(/\d+ readings · 3 flagged · 4 missing/)).toBeVisible();
  await expect(page.getByRole('button', { name: 'Revise' })).toHaveCount(3);
  await expect(page.getByRole('button', { name: 'Estimate' })).toHaveCount(4);
  await page.getByLabel('All hours').check();
  await expect(page.getByRole('table', { name: 'Hourly intervals' }).locator('tbody tr').first()).toBeVisible();
  await expect(page.getByRole('heading', { name: 'AMI simulator' })).toBeVisible();
  expect(errors).toEqual([]);
});

test('the support desk remembers the Kanban view', async ({ page }) => {
  await signIn(page, 'support', /\/ops\/dashboard$/);
  await page.getByRole('link', { name: 'Support desk & outages' }).click();
  await page.getByRole('tab', { name: 'Kanban' }).click();
  for (const column of ['Open', 'Assigned', 'In progress', 'Resolved']) {
    await expect(page.getByRole('heading', { name: new RegExp(`^${column}`) })).toBeVisible();
  }
  await page.reload();
  await expect(page.getByRole('tab', { name: 'Kanban' })).toHaveAttribute('aria-selected', 'true');
  await page.getByRole('tab', { name: 'Table' }).click();
  await expect(page.locator('tbody tr.clickable').first()).toBeVisible();
});

test('a customer turns auto-pay on and off', async ({ page }) => {
  await signIn(page, 'customer06', /\/app\/dashboard$/);
  await page.getByRole('link', { name: 'Account & meters' }).click();
  const toggle = page.getByRole('checkbox', { name: /Auto-pay new bills/ });
  await expect(toggle).not.toBeChecked();
  await toggle.check();
  await expect(page.getByRole('status').filter({ hasText: 'Auto-pay is on' })).toBeVisible();
  await page.reload();
  await expect(toggle).toBeChecked();
  await toggle.uncheck();
  await expect(page.getByRole('status').filter({ hasText: 'Auto-pay is off' })).toBeVisible();
  await page.reload();
  await expect(toggle).not.toBeChecked();
});
