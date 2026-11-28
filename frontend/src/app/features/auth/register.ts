import { HttpClient } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { LucideEye, LucideEyeOff } from '@lucide/angular';
import { firstValueFrom } from 'rxjs';
import { AuthStore } from '../../core/auth';
import { Toasts, problemMessage } from '../../core/ui-state';

export const STATES = [
  'Andhra Pradesh', 'Assam', 'Bihar', 'Chhattisgarh', 'Delhi', 'Goa', 'Gujarat', 'Haryana', 'Himachal Pradesh',
  'Jharkhand', 'Karnataka', 'Kerala', 'Madhya Pradesh', 'Maharashtra', 'Odisha', 'Punjab', 'Rajasthan',
  'Tamil Nadu', 'Telangana', 'Uttar Pradesh', 'Uttarakhand', 'West Bengal',
];

export function passwordStrength(value: string): { score: number; label: string } {
  let score = 0;
  if (value.length >= 12) score++;
  if (value.length >= 16) score++;
  if (/[a-z]/.test(value) && /[A-Z]/.test(value)) score++;
  if (/\d/.test(value)) score++;
  if (/[^A-Za-z0-9]/.test(value)) score++;
  const labels = ['Too short', 'Weak', 'Fair', 'Good', 'Strong', 'Very strong'];
  return { score: value.length < 12 ? 0 : score, label: value.length < 12 ? labels[0] : labels[score] };
}

@Component({
  imports: [ReactiveFormsModule, RouterLink, LucideEye, LucideEyeOff],
  template: `
    <h1 class="h-page">Create your account</h1>
    <p class="mt-1 text-sm text-muted">Register your home connection. We create your consumer account and meter.</p>
    @if (error()) { <p role="alert" class="mt-5 rounded-lg bg-bad-soft px-3 py-2 text-sm">{{ error() }}</p> }
    <form class="mt-6 space-y-4" [formGroup]="form" (ngSubmit)="submit()" novalidate>
      <div class="grid gap-4 sm:grid-cols-2">
        <div class="sm:col-span-2">
          <label class="label" for="fullName">Full name</label>
          <input id="fullName" class="input" formControlName="fullName" autocomplete="name" [attr.aria-invalid]="invalid('fullName')" />
          @if (invalid('fullName')) { <p class="field-error">Enter your name.</p> }
        </div>
        <div>
          <label class="label" for="reg-email">Email</label>
          <input id="reg-email" class="input" type="email" formControlName="email" autocomplete="email" [attr.aria-invalid]="invalid('email')" />
          @if (invalid('email')) { <p class="field-error">Enter a valid email.</p> }
        </div>
        <div>
          <label class="label" for="mobile">Mobile number</label>
          <input id="mobile" class="input num" inputmode="numeric" formControlName="mobileNumber" autocomplete="tel-national"
                 placeholder="9876543210" [attr.aria-invalid]="invalid('mobileNumber')" />
          @if (invalid('mobileNumber')) { <p class="field-error">Use a 10-digit Indian mobile number.</p> }
        </div>
        <div class="sm:col-span-2">
          <label class="label" for="reg-password">Password</label>
          <div class="relative">
            <input id="reg-password" class="input pr-11" [type]="reveal() ? 'text' : 'password'" formControlName="password" autocomplete="new-password"
                   aria-describedby="strength" [attr.aria-invalid]="invalid('password')" />
            <button type="button" class="absolute inset-y-0 right-0 flex w-10 items-center justify-center text-faint hover:text-ink"
                    [attr.aria-label]="reveal() ? 'Hide password' : 'Show password'" (click)="reveal.set(!reveal())">
              @if (reveal()) { <svg lucideEyeOff [size]="17" aria-hidden="true"></svg> }
              @else { <svg lucideEye [size]="17" aria-hidden="true"></svg> }
            </button>
          </div>
          <div class="mt-2 flex gap-1" aria-hidden="true">
            @for (step of [1, 2, 3, 4, 5]; track step) {
              <span class="h-1.5 flex-1 rounded-full" [class]="strength().score >= step ? strengthColor() : 'bg-sunken'"></span>
            }
          </div>
          <p id="strength" class="hint">{{ strength().label }} · at least 12 characters</p>
        </div>
      </div>
      <fieldset class="space-y-4 rounded-xl border border-line p-4" formGroupName="premise">
        <legend class="px-1 text-sm font-semibold">Service address</legend>
        <div>
          <label class="label" for="address">Address</label>
          <input id="address" class="input" formControlName="addressLine" autocomplete="street-address"
                 [attr.aria-invalid]="premiseInvalid('addressLine')" aria-describedby="address-error" />
          @if (premiseInvalid('addressLine')) { <p id="address-error" class="field-error">Enter the service address.</p> }
        </div>
        <div class="grid gap-4 sm:grid-cols-3">
          <div>
            <label class="label" for="city">City</label>
            <input id="city" class="input" formControlName="city" autocomplete="address-level2"
                   [attr.aria-invalid]="premiseInvalid('city')" aria-describedby="city-error" />
            @if (premiseInvalid('city')) { <p id="city-error" class="field-error">Enter the city.</p> }
          </div>
          <div>
            <label class="label" for="state">State</label>
            <select id="state" class="input" formControlName="state">
              @for (state of states; track state) { <option [value]="state">{{ state }}</option> }
            </select>
          </div>
          <div>
            <label class="label" for="pincode">Pincode</label>
            <input id="pincode" class="input num" inputmode="numeric" formControlName="pincode" autocomplete="postal-code"
                   [attr.aria-invalid]="premiseInvalid('pincode')" />
            @if (premiseInvalid('pincode')) { <p class="field-error">6 digits</p> }
          </div>
        </div>
      </fieldset>
      <button type="submit" class="btn-primary w-full" [disabled]="busy()">{{ busy() ? 'Creating account…' : 'Create account' }}</button>
    </form>
    <p class="mt-6 text-center text-sm text-muted">Already registered? <a routerLink="/auth/login" class="font-medium text-brand hover:underline">Sign in</a></p>
  `,
})
export class Register {
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthStore);
  private readonly router = inject(Router);
  private readonly toasts = inject(Toasts);
  protected readonly states = STATES;
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly reveal = signal(false);
  private submitted = false;
  protected readonly form = inject(NonNullableFormBuilder).group({
    fullName: ['', [Validators.required, Validators.maxLength(120)]],
    email: ['', [Validators.required, Validators.email, Validators.maxLength(254)]],
    mobileNumber: ['', [Validators.required, Validators.pattern(/^[6-9]\d{9}$/)]],
    password: ['', [Validators.required, Validators.minLength(12), Validators.maxLength(128)]],
    premise: inject(NonNullableFormBuilder).group({
      label: ['Home'],
      addressLine: ['', [Validators.required, Validators.maxLength(255)]],
      city: ['', [Validators.required, Validators.maxLength(100)]],
      state: ['Maharashtra', Validators.required],
      pincode: ['', [Validators.required, Validators.pattern(/^[1-9]\d{5}$/)]],
      regionZone: [''],
    }),
  });
  private readonly password = toSignal(this.form.controls.password.valueChanges, { initialValue: '' });
  protected readonly strength = computed(() => passwordStrength(this.password()));
  protected readonly strengthColor = computed(() =>
    this.strength().score <= 1 ? 'bg-bad' : this.strength().score <= 3 ? 'bg-warn' : 'bg-ok');

  protected invalid(name: 'fullName' | 'email' | 'mobileNumber' | 'password'): boolean {
    const control = this.form.controls[name];
    return control.invalid && (control.touched || this.submitted);
  }

  protected premiseInvalid(name: 'addressLine' | 'city' | 'pincode'): boolean {
    const control = this.form.controls.premise.controls[name];
    return control.invalid && (control.touched || this.submitted);
  }

  protected async submit(): Promise<void> {
    this.submitted = true;
    this.error.set(null);
    this.form.markAllAsTouched();
    if (this.form.invalid) return;
    this.busy.set(true);
    const value = this.form.getRawValue();
    value.premise.regionZone = value.premise.city;
    try {
      await firstValueFrom(this.http.post('/api/v1/auth/register', value));
      await this.auth.login(value.email, value.password);
      this.toasts.success('Welcome to GridIQ. Check your inbox to verify your email.');
      await this.router.navigateByUrl('/app/dashboard');
    } catch (error) {
      this.error.set(problemMessage(error, 'We could not create your account.'));
    } finally {
      this.busy.set(false);
    }
  }
}
