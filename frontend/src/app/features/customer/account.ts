import { HttpClient } from '@angular/common/http';
import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { LucideBadgeCheck, LucideGauge, LucideSunMedium } from '@lucide/angular';
import { firstValueFrom } from 'rxjs';
import { AuthStore } from '../../core/auth';
import { CustomerStore } from '../../core/customer';
import { Account, Profile, Theme } from '../../core/models';
import { ThemeStore, Toasts, problemMessage } from '../../core/ui-state';
import { FORMAT_PIPES } from '../../shared/format';
import { StatusBadge } from '../../shared/ui';

@Component({
  imports: [FormsModule, StatusBadge, ...FORMAT_PIPES, LucideBadgeCheck, LucideGauge, LucideSunMedium],
  template: `
    <h1 class="h-page">Account & meters</h1>
    @if (customer.details(); as details) {
      <div class="mt-6 grid gap-4 lg:grid-cols-3">
        <section class="card-pad" aria-labelledby="profile-heading">
          <h2 id="profile-heading" class="h-section">Profile</h2>
          <dl class="mt-3 space-y-3 text-sm">
            <div><dt class="text-faint">Name</dt><dd class="font-medium">{{ auth.user()?.fullName }}</dd></div>
            <div><dt class="text-faint">Sign-in email</dt>
              <dd class="flex items-center gap-2 font-medium">{{ auth.user()?.email }}
                @if (auth.user()?.emailVerified) { <span class="badge bg-ok-soft text-ok"><svg lucideBadgeCheck [size]="12" aria-hidden="true"></svg> Verified</span> }
                @else { <span class="badge bg-warn-soft text-warn">Not verified</span> }</dd></div>
            <div><dt class="text-faint">Consumer number</dt><dd class="num font-medium">{{ details.account.consumerNumber }}</dd></div>
            <div><dt class="text-faint">Account</dt><dd class="flex items-center gap-2">{{ details.account.accountType | humanize }} <app-badge [status]="details.account.status" /></dd></div>
            <div><dt class="text-faint">Customer since</dt><dd>{{ details.account.createdAt | day }}</dd></div>
          </dl>
        </section>

        <section class="card-pad" aria-labelledby="contact-heading">
          <h2 id="contact-heading" class="h-section">Billing contact & budget</h2>
          <form class="mt-3 space-y-3" (ngSubmit)="saveContact(details.account)">
            <div><label class="label" for="billing-email">Billing email</label>
              <input id="billing-email" class="input" type="email" name="billingEmail" [(ngModel)]="contact.billingEmail" required /></div>
            <div><label class="label" for="billing-mobile">Mobile number</label>
              <input id="billing-mobile" class="input num" name="mobileNumber" [(ngModel)]="contact.mobileNumber" pattern="[6-9][0-9]{9}" required /></div>
            <div><label class="label" for="budget">Monthly budget (₹)</label>
              <input id="budget" class="input num" type="number" min="0" step="50" name="budget" [(ngModel)]="contact.monthlyBudgetInr" />
              <p class="hint">We alert you mid-cycle when the forecast exceeds this amount.</p></div>
            <button type="submit" class="btn-primary" [disabled]="busy()">Save</button>
          </form>
        </section>

        <section class="card-pad" aria-labelledby="pref-heading">
          <h2 id="pref-heading" class="h-section">Preferences & security</h2>
          <div class="mt-3 space-y-3 text-sm">
            <div><label class="label" for="theme">Theme</label>
              <select id="theme" class="input" [ngModel]="theme.theme()" (ngModelChange)="setTheme($event)">
                <option value="SYSTEM">Match device</option><option value="LIGHT">Light</option><option value="DARK">Dark</option>
              </select></div>
            <label class="flex items-center gap-3">
              <input type="checkbox" class="size-4 accent-[var(--brand)]" [checked]="auth.user()?.budgetAlerts" (change)="toggleAlerts($event)" />
              Bill-shock and budget alerts
            </label>
          </div>
          <form class="mt-5 space-y-3 border-t border-line pt-4" (ngSubmit)="changePassword()">
            <p class="font-medium">Change password</p>
            <div><label class="label" for="current-password">Current password</label>
              <input id="current-password" class="input" type="password" name="current" [(ngModel)]="password.current" autocomplete="current-password" /></div>
            <div><label class="label" for="next-password">New password</label>
              <input id="next-password" class="input" type="password" name="next" [(ngModel)]="password.next" autocomplete="new-password" minlength="12" />
              <p class="hint">At least 12 characters. Other devices are signed out.</p></div>
            <button type="submit" class="btn-secondary" [disabled]="busy() || password.next.length < 12 || !password.current">Change password</button>
          </form>
        </section>
      </div>

      <h2 class="h-section mt-8">Premises & meters</h2>
      <div class="mt-3 grid gap-4 md:grid-cols-2">
        @for (premise of details.premises; track premise.id) {
          <section class="card-pad">
            <p class="font-semibold">{{ premise.label }}</p>
            <p class="text-sm text-muted">{{ premise.addressLine }}, {{ premise.city }}, {{ premise.state }} {{ premise.pincode }}</p>
            <ul class="mt-3 space-y-2">
              @for (meter of metersAt(premise.id); track meter.id) {
                <li class="flex flex-wrap items-center gap-2 rounded-lg bg-sunken p-3 text-sm">
                  <svg lucideGauge [size]="16" class="text-brand" aria-hidden="true"></svg>
                  <span class="num font-medium">{{ meter.meterSerial }}</span>
                  <span class="text-muted">{{ meter.connectionType | humanize }} · {{ meter.sanctionedLoadKw }} kW</span>
                  @if (meter.solarNetMetering) { <span class="badge bg-ok-soft text-ok"><svg lucideSunMedium [size]="12" aria-hidden="true"></svg> {{ meter.solarCapacityKw }} kW solar</span> }
                  <app-badge class="ml-auto" [status]="meter.status" />
                </li>
              }
            </ul>
          </section>
        }
      </div>
    } @else {
      <div class="mt-6 grid gap-4 lg:grid-cols-3"><div class="skeleton h-64"></div><div class="skeleton h-64"></div><div class="skeleton h-64"></div></div>
    }
  `,
})
export class AccountPage {
  protected readonly auth = inject(AuthStore);
  protected readonly customer = inject(CustomerStore);
  protected readonly theme = inject(ThemeStore);
  private readonly http = inject(HttpClient);
  private readonly toasts = inject(Toasts);
  protected readonly busy = signal(false);
  protected contact = { billingEmail: '', mobileNumber: '', monthlyBudgetInr: null as number | null };
  protected password = { current: '', next: '' };

  constructor() {
    void this.customer.load(true).then((details) => {
      this.contact = { billingEmail: details.account.billingEmail, mobileNumber: details.account.mobileNumber,
        monthlyBudgetInr: details.account.monthlyBudgetInr };
    });
  }

  protected metersAt(premiseId: string) {
    return this.customer.meters().filter((meter) => meter.premiseId === premiseId);
  }

  protected async saveContact(account: Account): Promise<void> {
    this.busy.set(true);
    try {
      await firstValueFrom(this.http.put<Account>('/api/v1/consumers/me', { ...this.contact, version: account.version }));
      await this.customer.load(true);
      this.toasts.success('Billing contact saved.');
    } catch (error) {
      this.toasts.error(error);
      await this.customer.load(true);
    } finally {
      this.busy.set(false);
    }
  }

  protected setTheme(theme: Theme): void {
    void this.theme.choose(theme).catch((error) => this.toasts.error(error));
  }

  protected async toggleAlerts(event: Event): Promise<void> {
    const budgetAlerts = (event.target as HTMLInputElement).checked;
    try {
      this.auth.updateProfile(await firstValueFrom(this.http.patch<Profile>('/api/v1/auth/me/preferences', { budgetAlerts })));
      this.toasts.success(budgetAlerts ? 'Budget alerts on.' : 'Budget alerts off.');
    } catch (error) {
      this.toasts.error(error);
    }
  }

  protected async changePassword(): Promise<void> {
    this.busy.set(true);
    try {
      await firstValueFrom(this.http.post('/api/v1/auth/change-password',
        { currentPassword: this.password.current, newPassword: this.password.next }));
      this.toasts.success('Password changed. Sign in with your new password.');
      this.auth.expire();
    } catch (error) {
      this.toasts.show(problemMessage(error), 'error');
    } finally {
      this.busy.set(false);
    }
  }
}
