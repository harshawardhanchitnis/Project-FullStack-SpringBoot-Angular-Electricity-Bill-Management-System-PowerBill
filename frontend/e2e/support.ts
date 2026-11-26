import { Page, expect } from '@playwright/test';

export const DEMO_PASSWORD = process.env['E2E_DEMO_PASSWORD'] ?? '';

export function demoEmail(name: string): string {
  return `${name}@demo.powerbill.invalid`;
}

/** Collects uncaught errors and console errors so each journey can assert a clean console. */
export function watchConsole(page: Page): string[] {
  const errors: string[] = [];
  page.on('pageerror', (error) => errors.push(error.message));
  page.on('console', (message) => {
    if (message.type() === 'error') errors.push(message.text());
  });
  return errors;
}

export async function signIn(page: Page, name: string, landing: RegExp): Promise<void> {
  if (!DEMO_PASSWORD) throw new Error('Set E2E_DEMO_PASSWORD to the DEMO_PASSWORD used by the Compose stack.');
  await page.goto('/auth/login');
  await page.getByLabel('Email').fill(demoEmail(name));
  await page.getByLabel('Password', { exact: true }).fill(DEMO_PASSWORD);
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
  await expect(page).toHaveURL(landing);
}

export async function expectNoHorizontalScroll(page: Page): Promise<void> {
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
  expect(overflow).toBeLessThanOrEqual(1);
}
