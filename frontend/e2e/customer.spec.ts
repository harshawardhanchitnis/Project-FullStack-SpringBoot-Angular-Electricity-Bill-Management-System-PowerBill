import { expect, test } from '@playwright/test';
import { expectNoHorizontalScroll, signIn, watchConsole } from './support';

test.describe.configure({ mode: 'serial' });

test('a customer reviews usage, pays a bill atomically and asks the Copilot', async ({ page }) => {
  const errors = watchConsole(page);
  await signIn(page, 'customer07', /\/app\/dashboard$/);
  await expect(page.getByText('Projected bill this month')).toBeVisible();
  await expect(page.getByRole('meter', { name: /Energy used this cycle/ })).toBeVisible();
  await expect(page.getByRole('img', { name: /Monthly imported energy/ })).toBeVisible();

  await page.getByRole('link', { name: 'Bills & payments' }).click();
  const rows = page.locator('tbody tr.clickable');
  await expect(rows.first()).toBeVisible();
  const before = await rows.count();

  await rows.first().getByRole('button').click();
  const drawer = page.getByRole('dialog');
  await expect(drawer.getByText('How this bill was calculated')).toBeVisible();
  const download = page.waitForEvent('download');
  await drawer.getByRole('button', { name: 'Invoice PDF' }).click();
  expect((await download).suggestedFilename()).toMatch(/^INV-\d{6}-[A-Z0-9]+\.pdf$/);
  await drawer.getByRole('button', { name: /^Pay ₹/ }).click();

  const checkout = page.getByRole('dialog', { name: 'Secure checkout' });
  await checkout.getByLabel('Net banking').check();
  await checkout.getByRole('button', { name: 'Continue' }).click();
  await expect(checkout.getByText('Sandbox gateway')).toBeVisible();
  await checkout.getByRole('button', { name: 'Approve payment' }).click();
  await expect(checkout.getByText('Payment successful')).toBeVisible();
  await checkout.getByRole('button', { name: 'Done' }).click();
  await expect(rows).toHaveCount(before - 1);
  await expect(page.getByRole('status').filter({ hasText: 'Payment received' }).first()).toBeVisible();

  await page.getByRole('tab', { name: 'Payments' }).click();
  await expect(page.getByRole('cell', { name: /^PAY-/ }).first()).toBeVisible();

  await page.getByRole('button', { name: 'Copilot' }).click();
  const copilot = page.getByRole('dialog', { name: 'Energy Copilot' });
  await copilot.getByLabel('Your question').fill('What will this month cost?');
  await copilot.getByRole('button', { name: 'Send' }).click();
  await expect(copilot.getByText(/projected bill is/i)).toBeVisible({ timeout: 45_000 });
  await expect(copilot.getByText(/Answered by deterministic/)).toBeVisible();
  expect(errors).toEqual([]);
});

test('a customer raises a complaint linked to a bill and follows it', async ({ page }) => {
  await signIn(page, 'customer08', /\/app\/dashboard$/);
  await page.getByRole('link', { name: 'Support & outages' }).click();
  await page.getByRole('button', { name: 'New complaint' }).click();
  const wizard = page.getByRole('dialog', { name: 'New complaint' });
  await wizard.getByLabel(/Billing question/).check();
  await wizard.getByLabel('Subject').fill('Late fee looks too high');
  await wizard.getByLabel('Describe the issue').fill('The surcharge on my last invoice seems higher than the published rate.');
  await wizard.getByRole('button', { name: 'Submit complaint' }).click();
  const detail = page.getByRole('dialog', { name: 'Late fee looks too high' });
  await expect(detail.getByText(/Due in/)).toBeVisible();
  await detail.getByLabel('Reply').fill('Adding my meter photo later today.');
  await detail.getByRole('button', { name: 'Send' }).click();
  await expect(detail.getByText('Adding my meter photo later today.')).toBeVisible();
});

for (const width of [375, 768, 1440]) {
  test(`customer pages fit a ${width}px viewport with a clean console`, async ({ page }) => {
    // CSP violations are reported as console errors, so this also proves style-src 'self' holds on every page.
    const errors = watchConsole(page);
    await page.setViewportSize({ width, height: 900 });
    await signIn(page, 'customer02', /\/app\/dashboard$/);
    await expect(page.getByText('Projected bill this month')).toBeVisible();
    await expectNoHorizontalScroll(page);
    for (const path of ['/app/bills', '/app/energy', '/app/support', '/app/account']) {
      await page.goto(path);
      await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
      await expectNoHorizontalScroll(page);
    }
    expect(errors).toEqual([]);
  });
}

test('a new customer registers and lands on the dashboard', async ({ page }) => {
  const email = `e2e-${Date.now()}@example.test`;
  await page.goto('/auth/register');
  await page.getByLabel('Full name').fill('Priya Nair');
  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Mobile number').fill('9811122233');
  await page.getByLabel('Password', { exact: true }).fill('A-strong-pass-2026!');
  await page.getByLabel('Address').fill('12 Lake View Road');
  await page.getByLabel('City').fill('Pune');
  await page.getByLabel('Pincode').fill('411001');
  await page.getByRole('button', { name: 'Create account' }).click();
  await expect(page).toHaveURL(/\/app\/dashboard$/);
  await page.getByRole('link', { name: 'Account & meters' }).click();
  await expect(page.getByText(email)).toBeVisible();
  await expect(page.getByText('Not verified')).toBeVisible();
});
