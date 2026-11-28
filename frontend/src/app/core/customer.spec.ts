import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { rangeBounds } from '../features/customer/energy';
import { AuthStore, authInterceptor } from './auth';
import { CustomerStore } from './customer';
import { ConsumerDetails, TokenResponse } from './models';
import { NotificationStore } from './ui-state';

function session(id: string): TokenResponse {
  return {
    accessToken: `token-${id}`, tokenType: 'Bearer', expiresIn: 900,
    user: { id, email: `${id}@example.test`, fullName: id, mobileNumber: '9876543210', role: 'CUSTOMER',
      emailVerified: true, themePreference: 'SYSTEM', budgetAlerts: true },
  };
}

function details(account: string, meters: string[]): ConsumerDetails {
  return {
    account: { id: account },
    premises: [],
    meters: meters.map((id) => ({ id, premiseId: 'p1', meterSerial: `M-${id}` })),
  } as unknown as ConsumerDetails;
}

describe('CustomerStore', () => {
  let http: HttpTestingController;
  let auth: AuthStore;
  let customer: CustomerStore;

  beforeEach(async () => {
    sessionStorage.clear();
    TestBed.configureTestingModule({
      providers: [provideRouter([{ path: 'auth/login', children: [] }]), provideHttpClient(withInterceptors([authInterceptor])), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    auth = TestBed.inject(AuthStore);
    customer = TestBed.inject(CustomerStore);
    http.expectOne('/api/v1/auth/refresh').flush(session('customer01'));
    await auth.ready;
    TestBed.tick();
  });

  afterEach(() => http.verify());

  it('drops the previous customer account and meter when another customer signs in', async () => {
    const first = customer.load();
    http.expectOne('/api/v1/consumers/me').flush(details('a1', ['m1', 'm2']));
    await first;
    customer.select('m2');
    expect(customer.meter()?.id).toBe('m2');

    auth.expire();
    TestBed.tick();
    expect(customer.details()).toBeNull();
    expect(customer.meterId()).toBeNull();
    expect(Object.keys(sessionStorage).some((key) => key.startsWith('gridiq-meter'))).toBe(false);

    const login = auth.login('customer06@example.test', 'irrelevant-password');
    http.expectOne('/api/v1/auth/login').flush(session('customer06'));
    await login;
    TestBed.tick();
    const second = customer.load();
    http.expectOne('/api/v1/consumers/me').flush(details('a6', ['m6']));
    await second;
    expect(customer.details()?.account.id).toBe('a6');
    expect(customer.meter()?.id).toBe('m6');
  });

  it('ignores a slow response that arrives after the user changed', async () => {
    const stale = customer.load();
    const request = http.expectOne('/api/v1/consumers/me');
    auth.expire();
    TestBed.tick();
    request.flush(details('a1', ['m1']));
    await stale;
    expect(customer.details()).toBeNull();
  });

  it('remembers the selected meter per account for page reloads', async () => {
    const load = customer.load();
    http.expectOne('/api/v1/consumers/me').flush(details('a1', ['m1', 'm2']));
    await load;
    customer.select('m2');
    expect(sessionStorage.getItem('gridiq-meter:a1')).toBe('m2');
  });
});

describe('NotificationStore event parsing', () => {
  let store: NotificationStore;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideRouter([{ path: 'auth/login', children: [] }]), provideHttpClient(withInterceptors([authInterceptor])), provideHttpClientTesting()],
    });
    store = TestBed.inject(NotificationStore);
    TestBed.inject(HttpTestingController).expectOne('/api/v1/auth/refresh').flush(null, { status: 401, statusText: 'No' });
  });

  it('adds a live notification and updates the unread count', () => {
    store.handle('event: notification\ndata: {"notification":{"id":"n1","title":"Bill","message":"Ready"},"unread":3}');
    expect(store.items().map((item) => item.id)).toEqual(['n1']);
    expect(store.unread()).toBe(3);
  });

  it('skips malformed frames instead of breaking the stream', () => {
    expect(() => store.handle('event: notification\ndata: {not json')).not.toThrow();
    expect(() => store.handle('event: notification\ndata: {"unread":1}')).not.toThrow();
    expect(store.items()).toEqual([]);
    expect(store.unread()).toBe(1);
  });
});

describe('Energy export ranges', () => {
  const days = (range: 'week' | 'month' | 'year') => {
    const { from, to } = rangeBounds(range);
    return Math.round((to.getTime() - from.getTime()) / 86_400_000);
  };

  it('covers the same window as the usage chart', () => {
    expect(days('week')).toBe(7);
    expect(days('month')).toBe(30);
    expect(days('year')).toBeGreaterThanOrEqual(334);
    expect(rangeBounds('year').from.getUTCDate()).toBe(1);
  });
});
