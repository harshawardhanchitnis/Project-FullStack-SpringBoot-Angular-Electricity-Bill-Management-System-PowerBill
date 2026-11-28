import { expect, test } from '@playwright/test';
import { watchConsole } from './support';

test('serves the SPA with strict security headers and a clean console', async ({ page }) => {
  const errors = watchConsole(page);
  const response = await page.goto('/auth/login');
  const headers = response!.headers();
  expect(headers['content-security-policy']).toContain("default-src 'self'");
  expect(headers['content-security-policy']).toContain("frame-ancestors 'none'");
  expect(headers['x-frame-options']).toBe('DENY');
  expect(headers['x-content-type-options']).toBe('nosniff');
  expect(headers['referrer-policy']).toBe('strict-origin-when-cross-origin');
  await expect(page.getByRole('heading', { name: 'Sign in' })).toBeVisible();
  expect(errors).toEqual([]);
});

test('keeps management endpoints and the ML service off the public edge', async ({ request }) => {
  for (const path of ['/actuator/prometheus', '/ml/health', '/v3/api-docs', '/swagger-ui.html']) {
    expect((await request.get(path)).status(), path).toBe(404);
  }
  expect((await request.get('/actuator/health/readiness')).ok()).toBe(true);
});

test('returns correlated problem details and rejects foreign origins', async ({ request, baseURL }) => {
  const denied = await request.get('/api/v1/bills', { headers: { 'X-Request-Id': 'e2e-problem' } });
  expect(denied.status()).toBe(401);
  expect(denied.headers()['content-type']).toContain('application/problem+json');
  expect(await denied.json()).toMatchObject({ status: 401, correlationId: 'e2e-problem' });

  const body = { email: 'nobody@example.test', password: 'not-the-password' };
  const sameOrigin = await request.post('/api/v1/auth/login', { data: body, headers: { Origin: baseURL! } });
  expect(sameOrigin.status()).toBe(401);
  expect(await sameOrigin.json()).toMatchObject({ detail: 'Invalid email or password.' });
  const foreign = await request.post('/api/v1/auth/login', { data: body, headers: { Origin: 'https://attacker.example' } });
  expect(foreign.status()).toBe(403);
});

test('deep links fall back to the SPA and unknown pages show a 404 view', async ({ page }) => {
  await page.goto('/no-such-page');
  await expect(page.getByRole('heading', { name: 'Page not found' })).toBeVisible();
  await page.goto('/app/bills');
  await expect(page).toHaveURL(/\/auth\/login\?returnUrl=%2Fapp%2Fbills/);
});
