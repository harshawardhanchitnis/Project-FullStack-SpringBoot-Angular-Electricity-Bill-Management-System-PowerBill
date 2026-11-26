import { expect, test } from '@playwright/test';

for (const width of [375, 768, 1440]) {
  test(`connects to the live API at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    const errors: string[] = [];
    page.on('pageerror', (error) => errors.push(error.message));
    page.on('console', (message) => {
      if (message.type() === 'error') errors.push(message.text());
    });

    await page.goto('/');
    await expect(page.getByRole('heading', { name: 'GridIQ' })).toBeVisible();
    await expect(page.getByRole('status')).toHaveText('Connected');
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(width);
    const button = page.getByRole('button', { name: 'Check connection' });
    await page.keyboard.press('Tab');
    await expect(button).toBeFocused();
    const apiResponse = page.waitForResponse((response) =>
      response.url().endsWith('/api/v1/status'),
    );
    await page.keyboard.press('Enter');
    expect((await apiResponse).ok()).toBe(true);
    await expect(page.getByRole('status')).toHaveText('Connected');
    expect(errors).toEqual([]);
  });
}

test('recovers after a failed Core API request', async ({ page }) => {
  await page.route(
    '**/api/v1/status',
    (route) => route.fulfill({ status: 503 }),
    { times: 1 },
  );
  await page.goto('/');
  await expect(page.getByRole('status')).toHaveText('Core API unavailable.');
  await page.getByRole('button', { name: 'Check connection' }).click();
  await expect(page.getByRole('status')).toHaveText('Connected');
});

test('deep links use the SPA and management routes stay internal', async ({ page, request }) => {
  await page.goto('/unknown-page');
  await expect(page.getByRole('heading', { name: 'Page not found' })).toBeVisible();
  await page.getByRole('link', { name: 'Back to GridIQ' }).click();
  await expect(page.getByRole('status')).toHaveText('Connected');
  expect((await request.get('/actuator/prometheus')).status()).toBe(404);
  expect((await request.get('/ml/health')).status()).toBe(404);
});

test('API boundaries return a correlated problem response', async ({ request }) => {
  const correlationId = 'gridiq-stack-smoke';
  const headers = { 'X-Request-Id': correlationId };
  const status = await request.get('/api/v1/status', { headers });
  expect(status.ok()).toBe(true);
  expect(await status.json()).toEqual({ status: 'UP', service: 'gridiq-core' });
  expect(status.headers()['x-request-id']).toBe(correlationId);

  const denied = await request.post('/api/v1/status', { headers });
  expect(denied.status()).toBe(403);
  expect(denied.headers()['content-type']).toContain('application/problem+json');
  expect(await denied.json()).toMatchObject({
    status: 403,
    type: 'about:blank',
    instance: '/api/v1/status',
    correlationId,
  });
});
