import { HttpClient } from '@angular/common/http';
import { Component, OnInit, inject, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { problemMessage } from '../../core/ui-state';

/** Email links carry their one-time token in the URL fragment, which never reaches servers or logs. */
function fragmentToken(): string | null {
  const match = /token=([A-Za-z0-9_-]{43})/.exec(window.location.hash);
  if (match) history.replaceState(null, '', window.location.pathname);
  return match?.[1] ?? null;
}

@Component({
  imports: [ReactiveFormsModule, RouterLink],
  template: `
    <h1 class="h-page">Reset your password</h1>
    @if (sent()) {
      <p role="status" class="mt-4 rounded-lg bg-ok-soft px-3 py-3 text-sm">
        If an account exists for that email, a reset link is on its way. The link expires in 20 minutes.
      </p>
    } @else {
      <p class="mt-1 text-sm text-muted">Enter your account email and we will send a reset link.</p>
      @if (error()) { <p role="alert" class="mt-4 rounded-lg bg-bad-soft px-3 py-2 text-sm">{{ error() }}</p> }
      <form class="mt-6 space-y-4" [formGroup]="form" (ngSubmit)="submit()" novalidate>
        <div>
          <label class="label" for="forgot-email">Email</label>
          <input id="forgot-email" class="input" type="email" formControlName="email" autocomplete="email" />
        </div>
        <button type="submit" class="btn-primary w-full" [disabled]="busy() || form.invalid">Send reset link</button>
      </form>
    }
    <p class="mt-6 text-center text-sm"><a routerLink="/auth/login" class="font-medium text-brand hover:underline">Back to sign in</a></p>
  `,
})
export class ForgotPassword {
  private readonly http = inject(HttpClient);
  protected readonly sent = signal(false);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly form = inject(NonNullableFormBuilder).group({
    email: ['', [Validators.required, Validators.email]],
  });

  protected async submit(): Promise<void> {
    this.busy.set(true);
    try {
      await firstValueFrom(this.http.post('/api/v1/auth/forgot-password', this.form.getRawValue()));
      this.sent.set(true);
    } catch (error) {
      this.error.set(problemMessage(error));
    } finally {
      this.busy.set(false);
    }
  }
}

@Component({
  imports: [ReactiveFormsModule, RouterLink],
  template: `
    <h1 class="h-page">Choose a new password</h1>
    @if (done()) {
      <p role="status" class="mt-4 rounded-lg bg-ok-soft px-3 py-3 text-sm">Your password was changed and other sessions were signed out.</p>
      <a routerLink="/auth/login" class="btn-primary mt-6 w-full">Sign in</a>
    } @else if (!token) {
      <p role="alert" class="mt-4 rounded-lg bg-bad-soft px-3 py-3 text-sm">This reset link is incomplete. Request a new one.</p>
      <a routerLink="/auth/forgot-password" class="btn-secondary mt-6 w-full">Request a new link</a>
    } @else {
      @if (error()) { <p role="alert" class="mt-4 rounded-lg bg-bad-soft px-3 py-2 text-sm">{{ error() }}</p> }
      <form class="mt-6 space-y-4" [formGroup]="form" (ngSubmit)="submit()" novalidate>
        <div>
          <label class="label" for="new-password">New password</label>
          <input id="new-password" class="input" type="password" formControlName="password" autocomplete="new-password" />
          <p class="hint">At least 12 characters.</p>
        </div>
        <div>
          <label class="label" for="confirm-password">Confirm password</label>
          <input id="confirm-password" class="input" type="password" formControlName="confirm" autocomplete="new-password" />
          @if (mismatch()) { <p class="field-error">The passwords do not match.</p> }
        </div>
        <button type="submit" class="btn-primary w-full" [disabled]="busy()">Change password</button>
      </form>
    }
  `,
})
export class ResetPassword {
  private readonly http = inject(HttpClient);
  protected readonly token = fragmentToken();
  protected readonly done = signal(false);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly mismatch = signal(false);
  protected readonly form = inject(NonNullableFormBuilder).group({
    password: ['', [Validators.required, Validators.minLength(12), Validators.maxLength(128)]],
    confirm: ['', Validators.required],
  });

  protected async submit(): Promise<void> {
    const { password, confirm } = this.form.getRawValue();
    this.mismatch.set(password !== confirm);
    if (this.form.invalid || password !== confirm) {
      this.error.set(this.form.invalid ? 'Use at least 12 characters.' : null);
      return;
    }
    this.busy.set(true);
    try {
      await firstValueFrom(this.http.post('/api/v1/auth/reset-password', { token: this.token, password }));
      this.done.set(true);
    } catch (error) {
      this.error.set(problemMessage(error, 'This link is invalid or expired.'));
    } finally {
      this.busy.set(false);
    }
  }
}

@Component({
  imports: [RouterLink],
  template: `
    <h1 class="h-page">Email verification</h1>
    @switch (state()) {
      @case ('working') { <p role="status" class="mt-4 text-sm text-muted">Verifying your email…</p> }
      @case ('done') { <p role="status" class="mt-4 rounded-lg bg-ok-soft px-3 py-3 text-sm">Your email address is verified.</p> }
      @default { <p role="alert" class="mt-4 rounded-lg bg-bad-soft px-3 py-3 text-sm">This link is invalid or expired.</p> }
    }
    <a routerLink="/auth/login" class="btn-primary mt-6 w-full">Continue to GridIQ</a>
  `,
})
export class VerifyEmail implements OnInit {
  private readonly http = inject(HttpClient);
  protected readonly state = signal<'working' | 'done' | 'failed'>('working');

  ngOnInit(): void {
    const token = fragmentToken();
    if (!token) {
      this.state.set('failed');
      return;
    }
    firstValueFrom(this.http.post('/api/v1/auth/verify-email', { token }))
      .then(() => this.state.set('done'))
      .catch(() => this.state.set('failed'));
  }
}
