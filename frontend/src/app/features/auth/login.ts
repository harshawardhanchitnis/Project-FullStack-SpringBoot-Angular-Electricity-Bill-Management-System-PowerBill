import { HttpClient } from '@angular/common/http';
import { Component, inject, input, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { LucideEye, LucideEyeOff } from '@lucide/angular';
import { firstValueFrom } from 'rxjs';
import { AuthStore } from '../../core/auth';
import { problemMessage } from '../../core/ui-state';

const PERSONAS = [
  { label: 'Residential customer', email: 'customer01@demo.powerbill.invalid' },
  { label: 'Solar prosumer', email: 'customer06@demo.powerbill.invalid' },
  { label: 'Commercial account', email: 'customer10@demo.powerbill.invalid' },
  { label: 'Billing officer', email: 'billing@demo.powerbill.invalid' },
  { label: 'Support agent', email: 'support@demo.powerbill.invalid' },
  { label: 'Field engineer', email: 'field@demo.powerbill.invalid' },
  { label: 'Super administrator', email: 'admin@demo.powerbill.invalid' },
];

@Component({
  imports: [ReactiveFormsModule, RouterLink, LucideEye, LucideEyeOff],
  template: `
    <h1 class="h-page">Sign in</h1>
    <p class="mt-1 text-sm text-muted">Customers and utility staff use the same sign-in.</p>
    @if (expired()) {
      <p role="status" class="mt-5 rounded-lg bg-warn-soft px-3 py-2 text-sm text-ink">Your session ended. Sign in again.</p>
    }
    @if (error()) {
      <p role="alert" class="mt-5 rounded-lg bg-bad-soft px-3 py-2 text-sm text-ink">{{ error() }}</p>
    }
    <form class="mt-6 space-y-4" [formGroup]="form" (ngSubmit)="submit()" novalidate>
      <div>
        <label class="label" for="email">Email</label>
        <input id="email" class="input" type="email" formControlName="email" autocomplete="username"
               [attr.aria-invalid]="invalid('email')" aria-describedby="email-error" />
        @if (invalid('email')) { <p id="email-error" class="field-error">Enter a valid email address.</p> }
      </div>
      <div>
        <div class="flex items-center justify-between">
          <label class="label" for="password">Password</label>
          <a routerLink="/auth/forgot-password" class="mb-1.5 text-xs font-medium text-brand hover:underline">Forgot password?</a>
        </div>
        <div class="relative">
          <input id="password" class="input pr-11" [type]="reveal() ? 'text' : 'password'" formControlName="password"
                 autocomplete="current-password" [attr.aria-invalid]="invalid('password')" />
          <button type="button" class="absolute inset-y-0 right-0 flex w-10 items-center justify-center text-faint hover:text-ink"
                  [attr.aria-label]="reveal() ? 'Hide password' : 'Show password'" (click)="reveal.set(!reveal())">
            @if (reveal()) { <svg lucideEyeOff [size]="17" aria-hidden="true"></svg> }
            @else { <svg lucideEye [size]="17" aria-hidden="true"></svg> }
          </button>
        </div>
        @if (invalid('password')) { <p class="field-error">Enter your password.</p> }
      </div>
      <button type="submit" class="btn-primary w-full" [disabled]="busy()">{{ busy() ? 'Signing in…' : 'Sign in' }}</button>
    </form>
    <p class="mt-6 text-center text-sm text-muted">
      New customer? <a routerLink="/auth/register" class="font-medium text-brand hover:underline">Create an account</a>
    </p>
    @if (demo()) {
      <details class="mt-8 rounded-xl border border-line bg-surface p-4 text-sm">
        <summary class="cursor-pointer font-medium text-ink">Demo personas</summary>
        <p class="mt-2 text-xs text-muted">Choose a persona to fill its email, then enter the demo password configured in your local .env file.</p>
        <div class="mt-3 flex flex-wrap gap-2">
          @for (persona of personas; track persona.email) {
            <button type="button" class="btn-secondary btn-sm" (click)="fill(persona.email)">{{ persona.label }}</button>
          }
        </div>
      </details>
    }
  `,
})
export class Login {
  readonly returnUrl = input<string>();
  readonly expired = input<string>();
  private readonly auth = inject(AuthStore);
  private readonly router = inject(Router);
  private readonly http = inject(HttpClient);
  protected readonly personas = PERSONAS;
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly reveal = signal(false);
  protected readonly demo = signal(false);
  private submitted = false;
  protected readonly form = inject(NonNullableFormBuilder).group({
    email: ['', [Validators.required, Validators.email, Validators.maxLength(254)]],
    password: ['', [Validators.required, Validators.maxLength(128)]],
  });

  constructor() {
    firstValueFrom(this.http.get<{ demoMode?: boolean }>('/api/v1/status'))
      .then((status) => this.demo.set(status.demoMode === true))
      .catch(() => undefined);
  }

  protected invalid(name: 'email' | 'password'): boolean {
    const control = this.form.controls[name];
    return control.invalid && (control.touched || this.submitted);
  }

  protected fill(email: string): void {
    this.form.controls.email.setValue(email);
    document.getElementById('password')?.focus();
  }

  protected async submit(): Promise<void> {
    this.submitted = true;
    this.error.set(null);
    if (this.form.invalid) return;
    this.busy.set(true);
    try {
      const user = await this.auth.login(this.form.controls.email.value.trim(), this.form.controls.password.value);
      const target = this.returnUrl();
      const home = this.auth.homeFor(user.role);
      const safe = target && target.startsWith(home.slice(0, 4)) ? target : home;
      await this.router.navigateByUrl(safe);
    } catch (error) {
      this.error.set(problemMessage(error, 'Invalid email or password.'));
    } finally {
      this.busy.set(false);
    }
  }
}
