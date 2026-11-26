import { HttpClient } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { LucidePlus, LucideSearch } from '@lucide/angular';
import { firstValueFrom } from 'rxjs';
import { AuthStore } from '../../core/auth';
import { Account, BillSummary, Complaint, ConsumerDetails, LedgerLine, Page } from '../../core/models';
import { Toasts, problemMessage } from '../../core/ui-state';
import { FORMAT_PIPES } from '../../shared/format';
import { STATES } from '../auth/register';
import { Dialog, EmptyState, ErrorBanner, Pager, StatusBadge } from '../../shared/ui';

function lastMonth(): { year: number; month: number } {
  const now = new Date(Date.now() + 5.5 * 3600 * 1000);
  const date = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth() - 1, 1));
  return { year: date.getUTCFullYear(), month: date.getUTCMonth() + 1 };
}

@Component({
  imports: [FormsModule, RouterLink, Dialog, EmptyState, ErrorBanner, Pager, StatusBadge, ...FORMAT_PIPES, LucidePlus, LucideSearch],
  template: `
    <div class="flex flex-wrap items-end justify-between gap-3">
      <div>
        <h1 class="h-page">Consumers & meters</h1>
        <p class="mt-1 text-sm text-muted">Search accounts and open a 360° view of premises, meters, bills, ledger and tickets.</p>
      </div>
      <button type="button" class="btn-primary" (click)="openCreate()"><svg lucidePlus [size]="16" aria-hidden="true"></svg> New consumer</button>
    </div>

    <form class="mt-5 flex flex-wrap gap-2" role="search" (ngSubmit)="search(0)">
      <label class="sr-only" for="consumer-q">Search consumers</label>
      <div class="relative min-w-56 flex-1">
        <svg lucideSearch [size]="16" class="pointer-events-none absolute left-3 top-3 text-faint" aria-hidden="true"></svg>
        <input id="consumer-q" class="input pl-9" name="q" [(ngModel)]="query" placeholder="Name, email, consumer number or mobile" />
      </div>
      <label class="sr-only" for="consumer-type">Account type</label>
      <select id="consumer-type" class="input w-auto" name="type" [(ngModel)]="type">
        <option value="">All types</option><option value="RESIDENTIAL">Residential</option><option value="COMMERCIAL">Commercial</option><option value="INDUSTRIAL">Industrial</option>
      </select>
      <label class="sr-only" for="consumer-status">Status</label>
      <select id="consumer-status" class="input w-auto" name="status" [(ngModel)]="status">
        <option value="">All statuses</option><option value="ACTIVE">Active</option><option value="SUSPENDED">Suspended</option><option value="DISCONNECTED">Disconnected</option>
      </select>
      <button type="submit" class="btn-secondary">Search</button>
    </form>

    @if (error()) { <div class="mt-4"><app-error [message]="error()!" (retry)="search(page())" /></div> }
    <div class="table-wrap mt-4">
      <table class="table">
        <caption class="sr-only">Consumer accounts</caption>
        <thead><tr><th>Consumer</th><th>Number</th><th>Type</th><th>Contact</th><th>Since</th><th>Status</th></tr></thead>
        <tbody>
          @for (account of results()?.content ?? []; track account.id) {
            <tr class="clickable" (click)="open(account.id)">
              <td><button type="button" class="font-medium text-brand hover:underline" (click)="open(account.id); $event.stopPropagation()">{{ account.fullName }}</button></td>
              <td class="num">{{ account.consumerNumber }}</td>
              <td>{{ account.accountType | humanize }}</td>
              <td class="text-muted">{{ account.billingEmail }}<span class="block num">{{ account.mobileNumber }}</span></td>
              <td>{{ account.createdAt | day }}</td>
              <td><app-badge [status]="account.status" /></td>
            </tr>
          } @empty {
            <tr><td colspan="6"><app-empty title="No consumers match" message="Try a different search." /></td></tr>
          }
        </tbody>
      </table>
    </div>
    <app-pager [page]="page()" [size]="20" [total]="results()?.totalElements ?? 0" (pageChange)="search($event)" />

    <app-dialog [open]="!!detail()" [drawer]="true" [heading]="detail()?.account?.fullName ?? ''"
                [subheading]="detail() ? detail()!.account.consumerNumber + ' · ' + detail()!.account.accountType : ''" (closed)="detail.set(null)">
      @if (detail(); as d) {
        <div class="space-y-6 text-sm">
          <section class="flex flex-wrap items-center gap-2">
            <app-badge [status]="d.account.status" />
            <span class="min-w-0 wrap-anywhere text-muted">{{ d.account.billingEmail }} · {{ d.account.mobileNumber }}</span>
            <label class="sr-only" for="status-change">Change status</label>
            <select id="status-change" class="input ml-auto w-auto" [ngModel]="d.account.status" (ngModelChange)="changeStatus(d.account, $event)">
              <option value="ACTIVE">Active</option><option value="SUSPENDED">Suspended</option><option value="DISCONNECTED">Disconnected</option>
            </select>
          </section>
          <section>
            <h3 class="h-section mb-2">Premises & meters</h3>
            @for (premise of d.premises; track premise.id) {
              <div class="mb-2 rounded-lg border border-line p-3">
                <p class="font-medium">{{ premise.label }} · <span class="text-muted">{{ premise.addressLine }}, {{ premise.city }} {{ premise.pincode }}</span></p>
                @for (meter of metersAt(d, premise.id); track meter.id) {
                  <div class="mt-2 flex flex-wrap items-center gap-2 rounded-md bg-sunken px-3 py-2">
                    <span class="num font-medium">{{ meter.meterSerial }}</span>
                    <span class="text-muted">{{ meter.connectionType | humanize }} · {{ meter.sanctionedLoadKw }} kW{{ meter.solarNetMetering ? ' · ' + meter.solarCapacityKw + ' kW solar' : '' }}</span>
                    @if (canBill()) {
                      <button type="button" class="btn-secondary btn-sm ml-auto" [disabled]="busy()" (click)="generate(meter.id)">Bill {{ billMonthLabel }}</button>
                    }
                  </div>
                }
              </div>
            }
          </section>
          <section>
            <div class="mb-2 flex items-center justify-between">
              <h3 class="h-section">Recent bills</h3>
              <a class="text-sm font-medium text-brand hover:underline" routerLink="/ops/billing" [queryParams]="{ accountId: d.account.id }">All bills</a>
            </div>
            <ul class="divide-y divide-line rounded-lg border border-line">
              @for (bill of bills(); track bill.id) {
                <li class="flex flex-wrap items-center gap-2 px-3 py-2">
                  <span class="font-medium">{{ bill.billNumber }}</span><span class="text-muted">{{ bill.period | period }}</span>
                  <span class="num ml-auto">{{ bill.totalAmount | inr }}</span><app-badge [status]="bill.status" />
                </li>
              } @empty { <li class="px-3 py-3 text-muted">No bills.</li> }
            </ul>
          </section>
          <section>
            <h3 class="h-section mb-2">Ledger</h3>
            <ul class="divide-y divide-line rounded-lg border border-line">
              @for (line of ledger(); track line.id) {
                <li class="flex gap-2 px-3 py-2"><span class="text-faint w-24 shrink-0">{{ line.createdAt | day }}</span>
                  <span class="min-w-0 flex-1 truncate">{{ line.description }}</span>
                  <span class="num" [class.text-ok]="line.entryType === 'CREDIT'">{{ line.entryType === 'CREDIT' ? '−' : '' }}{{ line.amount | inr }}</span>
                  <span class="num w-24 text-right text-muted">{{ line.runningBalance | inr }}</span></li>
              } @empty { <li class="px-3 py-3 text-muted">No ledger entries.</li> }
            </ul>
          </section>
          <section>
            <h3 class="h-section mb-2">Complaints</h3>
            <ul class="space-y-2">
              @for (complaint of complaints(); track complaint.id) {
                <li class="flex flex-wrap items-center gap-2"><span class="font-medium">{{ complaint.ticketNumber }}</span>
                  <span class="text-muted">{{ complaint.subject }}</span><app-badge class="ml-auto" [status]="complaint.status" /></li>
              } @empty { <li class="text-muted">No complaints.</li> }
            </ul>
          </section>
        </div>
      }
    </app-dialog>

    <app-dialog [open]="createOpen()" heading="New consumer" subheading="Creates the customer login, account, premise and a default meter." (closed)="createOpen.set(false)">
      <form id="create-consumer" class="grid gap-3 sm:grid-cols-2" (ngSubmit)="create()">
        <div class="sm:col-span-2"><label class="label" for="n-name">Full name</label><input id="n-name" class="input" name="fullName" [(ngModel)]="draft.fullName" required /></div>
        <div><label class="label" for="n-email">Email</label><input id="n-email" class="input" type="email" name="email" [(ngModel)]="draft.email" required /></div>
        <div><label class="label" for="n-mobile">Mobile</label><input id="n-mobile" class="input num" name="mobile" [(ngModel)]="draft.mobileNumber" required /></div>
        <div><label class="label" for="n-type">Account type</label>
          <select id="n-type" class="input" name="type" [(ngModel)]="draft.accountType"><option value="RESIDENTIAL">Residential</option><option value="COMMERCIAL">Commercial</option><option value="INDUSTRIAL">Industrial</option></select></div>
        <div><label class="label" for="n-password">Temporary password</label><input id="n-password" class="input" type="password" name="password" [(ngModel)]="draft.password" minlength="12" autocomplete="new-password" required /></div>
        <div class="sm:col-span-2"><label class="label" for="n-address">Service address</label><input id="n-address" class="input" name="address" [(ngModel)]="draft.addressLine" required /></div>
        <div><label class="label" for="n-city">City</label><input id="n-city" class="input" name="city" [(ngModel)]="draft.city" required /></div>
        <div><label class="label" for="n-state">State</label><select id="n-state" class="input" name="state" [(ngModel)]="draft.state">
          @for (state of states; track state) { <option [value]="state">{{ state }}</option> }</select></div>
        <div><label class="label" for="n-pin">Pincode</label><input id="n-pin" class="input num" name="pincode" [(ngModel)]="draft.pincode" required /></div>
        @if (formError()) { <p role="alert" class="rounded-lg bg-bad-soft p-3 text-sm sm:col-span-2">{{ formError() }}</p> }
      </form>
      <div dialog-footer class="flex justify-end gap-2 border-t border-line p-4">
        <button type="button" class="btn-secondary" (click)="createOpen.set(false)">Cancel</button>
        <button type="submit" form="create-consumer" class="btn-primary" [disabled]="busy()">Create consumer</button>
      </div>
    </app-dialog>
  `,
})
export class Consumers {
  private readonly http = inject(HttpClient);
  private readonly toasts = inject(Toasts);
  private readonly auth = inject(AuthStore);
  protected readonly states = STATES;
  protected readonly results = signal<Page<Account> | null>(null);
  protected readonly page = signal(0);
  protected readonly error = signal<string | null>(null);
  protected readonly detail = signal<ConsumerDetails | null>(null);
  protected readonly bills = signal<BillSummary[]>([]);
  protected readonly ledger = signal<LedgerLine[]>([]);
  protected readonly complaints = signal<Complaint[]>([]);
  protected readonly createOpen = signal(false);
  protected readonly busy = signal(false);
  protected readonly formError = signal<string | null>(null);
  protected readonly canBill = computed(() => ['BILLING_OFFICER', 'SUPER_ADMIN'].includes(this.auth.role() ?? ''));
  protected readonly billMonth = lastMonth();
  protected readonly billMonthLabel = new Date(Date.UTC(this.billMonth.year, this.billMonth.month - 1, 1))
    .toLocaleDateString('en-IN', { month: 'short', year: 'numeric', timeZone: 'UTC' });
  protected query = '';
  protected type = '';
  protected status = '';
  protected draft = this.blank();

  constructor() {
    void this.search(0);
  }

  async search(page: number): Promise<void> {
    this.page.set(page);
    this.error.set(null);
    const params = new URLSearchParams({ q: this.query.trim(), page: String(page), size: '20' });
    if (this.type) params.set('type', this.type);
    if (this.status) params.set('status', this.status);
    try {
      this.results.set(await firstValueFrom(this.http.get<Page<Account>>(`/api/v1/ops/consumers?${params}`)));
    } catch (error) {
      this.error.set(problemMessage(error, 'Consumers could not be loaded.'));
    }
  }

  protected metersAt(details: ConsumerDetails, premiseId: string) {
    return details.meters.filter((meter) => meter.premiseId === premiseId);
  }

  protected async open(id: string): Promise<void> {
    try {
      const [details, ledger, complaints] = await Promise.all([
        firstValueFrom(this.http.get<ConsumerDetails>(`/api/v1/ops/consumers/${id}`)),
        firstValueFrom(this.http.get<LedgerLine[]>(`/api/v1/ops/consumers/${id}/ledger?limit=12`)).catch(() => []),
        firstValueFrom(this.http.get<Page<Complaint>>(`/api/v1/ops/complaints?accountId=${id}&size=10`)).then((p) => p.content).catch(() => []),
      ]);
      this.detail.set(details);
      this.ledger.set(ledger);
      this.complaints.set(complaints);
      const bills = await firstValueFrom(this.http.get<Page<BillSummary>>(`/api/v1/ops/bills?accountId=${id}&size=6`)).catch(() => null);
      this.bills.set(bills?.content ?? []);
    } catch (error) {
      this.toasts.error(error);
    }
  }

  protected async changeStatus(account: Account, status: string): Promise<void> {
    if (status === account.status) return;
    if (!confirm(`Change ${account.fullName} to ${status.toLowerCase()}? This is recorded in the audit trail.`)) {
      this.detail.set({ ...this.detail()! });
      return;
    }
    try {
      await firstValueFrom(this.http.patch(`/api/v1/ops/consumers/${account.id}/status`, { status, version: account.version }));
      this.toasts.success('Account status updated.');
      await this.open(account.id);
      void this.search(this.page());
    } catch (error) {
      this.toasts.error(error);
    }
  }

  protected async generate(meterId: string): Promise<void> {
    this.busy.set(true);
    try {
      const result = await firstValueFrom(this.http.post<{ bill: BillSummary }>('/api/v1/ops/bills/generate', { meterId, ...this.billMonth }));
      this.toasts.success(`Issued ${result.bill.billNumber}.`);
      if (this.detail()) await this.open(this.detail()!.account.id);
    } catch (error) {
      this.toasts.error(error);
    } finally {
      this.busy.set(false);
    }
  }

  protected openCreate(): void {
    this.draft = this.blank();
    this.formError.set(null);
    this.createOpen.set(true);
  }

  protected async create(): Promise<void> {
    this.busy.set(true);
    const d = this.draft;
    try {
      const created = await firstValueFrom(this.http.post<ConsumerDetails>('/api/v1/ops/consumers', {
        accountType: d.accountType,
        customer: { email: d.email, password: d.password, fullName: d.fullName, mobileNumber: d.mobileNumber,
          premise: { label: 'Primary service', addressLine: d.addressLine, city: d.city, state: d.state, pincode: d.pincode, regionZone: d.city } },
      }));
      this.createOpen.set(false);
      this.toasts.success(`Created ${created.account.consumerNumber}.`);
      await this.search(0);
      this.detail.set(created);
    } catch (error) {
      this.formError.set(problemMessage(error));
    } finally {
      this.busy.set(false);
    }
  }

  private blank() {
    return { fullName: '', email: '', mobileNumber: '', accountType: 'RESIDENTIAL', password: '', addressLine: '',
      city: '', state: 'Maharashtra', pincode: '' };
  }
}
