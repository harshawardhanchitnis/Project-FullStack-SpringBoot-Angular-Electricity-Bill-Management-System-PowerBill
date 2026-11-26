import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { AuthStore, authInterceptor } from './auth';
import { TokenResponse } from './models';

function session(token: string): TokenResponse {
  return {
    accessToken: token, tokenType: 'Bearer', expiresIn: 900,
    user: { id: 'u1', email: 'a@example.test', fullName: 'Ananya K', mobileNumber: '9876543210', role: 'CUSTOMER',
      emailVerified: true, themePreference: 'SYSTEM', budgetAlerts: true },
  };
}

describe('AuthStore and authInterceptor', () => {
  let http: HttpTestingController;
  let store: AuthStore;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(withInterceptors([authInterceptor])), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    store = TestBed.inject(AuthStore);
  });

  afterEach(() => http.verify());

  it('restores the session from the refresh cookie on start-up', async () => {
    const refresh = http.expectOne('/api/v1/auth/refresh');
    expect(refresh.request.headers.get('X-GridIQ-Session')).toBe('1');
    expect(refresh.request.headers.has('Authorization')).toBe(false);
    refresh.flush(session('first'));
    await store.ready;
    expect(store.signedIn()).toBe(true);
    expect(store.role()).toBe('CUSTOMER');
  });

  it('refreshes once for concurrent 401 responses and retries with the new token', async () => {
    http.expectOne('/api/v1/auth/refresh').flush(session('expired'));
    await store.ready;
    const client = TestBed.inject(HttpClient);
    const first = firstValueFrom(client.get<{ ok: boolean }>('/api/v1/bills'));
    const second = firstValueFrom(client.get<{ ok: boolean }>('/api/v1/payments'));

    for (const url of ['/api/v1/bills', '/api/v1/payments']) {
      const request = http.expectOne(url);
      expect(request.request.headers.get('Authorization')).toBe('Bearer expired');
      expect(request.request.headers.get('X-Request-Id')).toBeTruthy();
      request.flush({}, { status: 401, statusText: 'Unauthorized' });
    }
    await Promise.resolve();
    http.expectOne('/api/v1/auth/refresh').flush(session('fresh'));
    await new Promise((resolve) => setTimeout(resolve));
    for (const url of ['/api/v1/bills', '/api/v1/payments']) {
      const retry = http.expectOne(url);
      expect(retry.request.headers.get('Authorization')).toBe('Bearer fresh');
      retry.flush({ ok: true });
    }
    expect(await first).toEqual({ ok: true });
    expect(await second).toEqual({ ok: true });
  });

  it('signs out when the refresh cookie is no longer valid', async () => {
    http.expectOne('/api/v1/auth/refresh').flush(null, { status: 401, statusText: 'Unauthorized' });
    await store.ready;
    expect(store.signedIn()).toBe(false);
    expect(store.homeFor('SUPPORT_AGENT')).toBe('/ops/dashboard');
    expect(store.homeFor('CUSTOMER')).toBe('/app/dashboard');
  });
});
