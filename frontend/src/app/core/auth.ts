import { HttpClient, HttpErrorResponse, HttpHeaders, HttpInterceptorFn, HttpRequest } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { firstValueFrom, from, switchMap, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { Profile, Role, STAFF_ROLES, TokenResponse } from './models';

const SESSION_HEADER = new HttpHeaders({ 'X-GridIQ-Session': '1' });
const ANONYMOUS_PATHS = [
  '/api/v1/auth/login',
  '/api/v1/auth/refresh',
  '/api/v1/auth/logout',
  '/api/v1/auth/register',
  '/api/v1/auth/forgot-password',
  '/api/v1/auth/reset-password',
  '/api/v1/auth/verify-email',
];

/**
 * Holds the short-lived access token in memory only. The rotating refresh token lives in an HttpOnly cookie,
 * so a page reload restores the session with one refresh call and nothing sensitive touches localStorage.
 */
@Injectable({ providedIn: 'root' })
export class AuthStore {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);
  private readonly tokenSignal = signal<string | null>(null);
  private readonly userSignal = signal<Profile | null>(null);
  private refreshing: Promise<boolean> | null = null;
  private refreshTimer: ReturnType<typeof setTimeout> | undefined;
  readonly ready: Promise<void>;

  readonly user = this.userSignal.asReadonly();
  readonly token = this.tokenSignal.asReadonly();
  readonly signedIn = computed(() => this.userSignal() !== null);
  readonly role = computed(() => this.userSignal()?.role ?? null);
  readonly isStaff = computed(() => STAFF_ROLES.includes(this.role() as Role));

  constructor() {
    this.ready = this.refresh().then(() => undefined);
  }

  async login(email: string, password: string): Promise<Profile> {
    const session = await firstValueFrom(
      this.http.post<TokenResponse>('/api/v1/auth/login', { email, password }),
    );
    this.accept(session);
    return session.user;
  }

  /** Rotates the refresh cookie. Concurrent callers share one request. */
  refresh(): Promise<boolean> {
    if (!this.refreshing) {
      this.refreshing = firstValueFrom(
        this.http.post<TokenResponse | null>('/api/v1/auth/refresh', null, { headers: SESSION_HEADER }),
      )
        .then((session) => {
          if (!session) {
            this.clear();
            return false;
          }
          this.accept(session);
          return true;
        })
        .catch(() => {
          this.clear();
          return false;
        })
        .finally(() => (this.refreshing = null));
    }
    return this.refreshing;
  }

  async logout(): Promise<void> {
    try {
      await firstValueFrom(this.http.post('/api/v1/auth/logout', null, { headers: SESSION_HEADER }));
    } finally {
      this.clear();
      await this.router.navigateByUrl('/auth/login');
    }
  }

  updateProfile(profile: Profile): void {
    this.userSignal.set(profile);
  }

  expire(returnUrl?: string): void {
    if (returnUrl?.startsWith('/auth')) returnUrl = undefined;
    this.clear();
    void this.router.navigate(['/auth/login'], {
      queryParams: returnUrl ? { returnUrl, expired: 1 } : { expired: 1 },
    });
  }

  homeFor(role: Role | null): string {
    return role === 'CUSTOMER' ? '/app/dashboard' : role ? '/ops/dashboard' : '/auth/login';
  }

  private accept(session: TokenResponse): void {
    this.tokenSignal.set(session.accessToken);
    this.userSignal.set(session.user);
    clearTimeout(this.refreshTimer);
    const refreshIn = Math.max(30, session.expiresIn * 0.8) * 1000;
    this.refreshTimer = setTimeout(() => void this.refresh(), refreshIn);
  }

  private clear(): void {
    clearTimeout(this.refreshTimer);
    this.tokenSignal.set(null);
    this.userSignal.set(null);
  }
}

function correlationId(): string {
  return crypto.randomUUID?.() ?? Math.random().toString(36).slice(2);
}

function withToken(request: HttpRequest<unknown>, token: string | null): HttpRequest<unknown> {
  const headers: Record<string, string> = { 'X-Request-Id': correlationId() };
  if (token) headers['Authorization'] = `Bearer ${token}`;
  return request.clone({ setHeaders: headers });
}

/** Adds the bearer token and correlation ID, and retries once after a silent refresh on HTTP 401. */
export const authInterceptor: HttpInterceptorFn = (request, next) => {
  // Anonymous calls return before injecting AuthStore, whose constructor itself issues the refresh request.
  if (!request.url.startsWith('/api/') || ANONYMOUS_PATHS.some((path) => request.url.startsWith(path))) {
    return next(request.url.startsWith('/api/') ? withToken(request, null) : request);
  }
  const auth = inject(AuthStore);
  const router = inject(Router);
  const token = auth.token();
  return next(withToken(request, token)).pipe(
    catchError((error: unknown) => {
      // Only a request that carried a token can be rescued by a refresh (not one racing a sign-out).
      if (!token || !(error instanceof HttpErrorResponse) || error.status !== 401) {
        return throwError(() => error);
      }
      return from(auth.refresh()).pipe(
        switchMap((refreshed) => {
          if (!refreshed) {
            auth.expire(router.url);
            return throwError(() => error);
          }
          return next(withToken(request, auth.token()));
        }),
      );
    }),
  );
};

export const authGuard: CanActivateFn = async (_route, state) => {
  const auth = inject(AuthStore);
  const router = inject(Router);
  await auth.ready;
  return auth.signedIn() ? true : router.createUrlTree(['/auth/login'], { queryParams: { returnUrl: state.url } });
};

export function roleGuard(...roles: Role[]): CanActivateFn {
  return async () => {
    const auth = inject(AuthStore);
    const router = inject(Router);
    await auth.ready;
    if (!auth.signedIn()) return router.createUrlTree(['/auth/login']);
    return roles.includes(auth.role() as Role) ? true : router.createUrlTree([auth.homeFor(auth.role())]);
  };
}

export const guestGuard: CanActivateFn = async () => {
  const auth = inject(AuthStore);
  const router = inject(Router);
  await auth.ready;
  return auth.signedIn() ? router.createUrlTree([auth.homeFor(auth.role())]) : true;
};
