import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { LucideDownload, LucidePlay, LucideX } from '@lucide/angular';
import { firstValueFrom } from 'rxjs';
import { AuthStore } from '../../core/auth';
import { BillDetail, BillSummary, ConsumerDetails, Page, Payment } from '../../core/models';
import { Toasts, problemMessage } from '../../core/ui-state';
import { exportCsv } from '../../shared/csv';
import { download, idempotencyKey } from '../../shared/download';
import { FORMAT_PIPES } from '../../shared/format';
import { Dialog, EmptyState, ErrorBanner, KpiCard, Pager, StatusBadge } from '../../shared/ui';

interface Run {
  id: string;
  billingYear: number;
  billingMonth: number;
  status: string;
  metersProcessed: number;
  billsGenerated: number;
  totalBilledInr: number;
  errors: string;
  startedAt: string;
  completedAt: string | null;
}

interface RunResult {
  run: Run | null;
  preview: { meterSerial: string; consumerNumber: string; netBillableKwh: number; totalInr: number }[];
  errors: { meterSerial: string; code: string; message: string }[];
  totalBilledInr: number;
}

type Tab = 'bills' | 'runs' | 'payments';

@Component({
  imports: [FormsModule, Dialog, EmptyState, ErrorBanner, KpiCard, Pager, StatusBadge, ...FORMAT_PIPES, LucideDownload, LucidePlay,
    LucideX],
  template: `
    <div class="flex flex-wrap items-end justify-between gap-3">
      <div>
        <h1 class="h-page">Billing & runs</h1>
        <p class="mt-1 text-sm text-muted">Issued invoices are immutable. Corrections are made with credit and debit notes.</p>
      </div>
      <div class="tabs" role="tablist" aria-label="Billing views">
        <button type="button" role="tab" class="tab" [attr.aria-selected]="tab() === 'bills'" (click)="setTab('bills')">Bills</button>
        @if (canWrite()) {
          <button type="button" role="tab" class="tab" [attr.aria-selected]="tab() === 'runs'" (click)="setTab('runs')">Billing runs</button>
          <button type="button" role="tab" class="tab" [attr.aria-selected]="tab() === 'payments'" (click)="setTab('payments')">Payments</button>
        }
      </div>
    </div>
    @if (error()) { <div class="mt-4"><app-error [message]="error()!" (retry)="reload()" /></div> }

    @switch (tab()) {
      @case ('bills') {
        <form class="mt-5 flex flex-wrap gap-2" role="search" (ngSubmit)="page.set(0); reload()">
          <label class="sr-only" for="bill-q">Bill number</label>
          <input id="bill-q" class="input w-56" name="q" [(ngModel)]="query" placeholder="Bill number" />
          <label class="sr-only" for="bill-status">Status</label>
          <select id="bill-status" class="input w-auto" name="status" [(ngModel)]="status">
            <option value="">All statuses</option><option value="OPEN">Unpaid</option><option value="OVERDUE">Overdue</option>
            <option value="ISSUED">Issued</option><option value="PAID">Paid</option>
          </select>
          <label class="sr-only" for="bill-period">Period</label>
          <input id="bill-period" class="input w-auto" type="month" name="period" [(ngModel)]="periodFilter" />
          <button type="submit" class="btn-secondary">Filter</button>
          <button type="button" class="btn-secondary btn-sm ml-auto self-center" [disabled]="!bills()?.content?.length" (click)="exportBills()">
            <svg lucideDownload [size]="14" aria-hidden="true"></svg> Export CSV</button>
        </form>
        @if (accountId()) {
          <p class="mt-3 flex flex-wrap items-center gap-2 text-sm">
            <span class="badge bg-brand-soft text-brand">Account: {{ accountLabel() ?? accountId() }}</span>
            <button type="button" class="btn-ghost btn-sm" (click)="clearAccount()" aria-label="Clear the account filter">
              <svg lucideX [size]="14" aria-hidden="true"></svg> Clear</button>
          </p>
        }
        <div class="table-wrap mt-4">
          <table class="table">
            <caption class="sr-only">Bills</caption>
            <thead><tr><th>Bill</th><th>Period</th><th class="text-right">Energy</th><th class="text-right">Total</th><th class="text-right">Due now</th><th>Due</th><th>Status</th></tr></thead>
            <tbody>
              @for (bill of bills()?.content ?? []; track bill.id) {
                <tr class="clickable" (click)="open(bill.id)">
                  <td><button type="button" class="font-medium text-brand hover:underline" (click)="open(bill.id); $event.stopPropagation()">{{ bill.billNumber }}</button></td>
                  <td>{{ bill.period | period }}</td>
                  <td class="text-right">{{ bill.netBillableKwh | kwh }}</td>
                  <td class="text-right">{{ bill.totalAmount | inr }}</td>
                  <td class="text-right font-medium">{{ bill.totalDue | inr }}</td>
                  <td>{{ bill.dueDate | day }}</td>
                  <td><app-badge [status]="bill.status" /></td>
                </tr>
              } @empty { <tr><td colspan="7"><app-empty title="No bills match these filters" /></td></tr> }
            </tbody>
          </table>
        </div>
        <app-pager [page]="page()" [size]="20" [total]="bills()?.totalElements ?? 0" (pageChange)="page.set($event); reload()" />
      }
      @case ('runs') {
        <section class="card-pad mt-5" aria-labelledby="run-heading">
          <h2 id="run-heading" class="h-section">Run monthly billing</h2>
          <p class="mt-1 text-sm text-muted">Preview first. Executing is idempotent: retrying the same run never issues a bill twice.</p>
          <div class="mt-4 flex flex-wrap items-end gap-3">
            <div><label class="label" for="run-month">Billing month</label>
              <input id="run-month" class="input" type="month" [(ngModel)]="runMonth" [max]="maxMonth" /></div>
            <button type="button" class="btn-secondary" [disabled]="busy()" (click)="run(true)">Preview</button>
            <button type="button" class="btn-primary" [disabled]="busy()" (click)="run(false)"><svg lucidePlay [size]="15" aria-hidden="true"></svg> Execute run</button>
          </div>
          @if (result(); as r) {
            <div class="mt-5 grid gap-3 sm:grid-cols-3">
              <app-kpi [label]="r.run ? 'Run ' + (r.run.status | humanize) : 'Preview'" [value]="(r.run ? r.run.billsGenerated : r.preview.length) + ' bills'" />
              <app-kpi label="Amount" [value]="r.totalBilledInr | inr" />
              <app-kpi label="Exceptions" [value]="r.errors.length + ''" [tone]="r.errors.length ? 'warn' : 'ok'" />
            </div>
            @if (r.errors.length) {
              <ul class="mt-3 space-y-1 text-sm">
                @for (item of r.errors; track item.meterSerial) { <li><span class="num font-medium">{{ item.meterSerial }}</span> · {{ item.code | humanize }}: <span class="text-muted">{{ item.message }}</span></li> }
              </ul>
            }
            @if (r.preview.length) {
              <div class="table-wrap mt-3"><table class="table"><thead><tr><th>Meter</th><th>Consumer</th><th class="text-right">Billable</th><th class="text-right">Amount</th></tr></thead>
                <tbody>@for (row of r.preview; track row.meterSerial) {
                  <tr><td class="num">{{ row.meterSerial }}</td><td class="num">{{ row.consumerNumber }}</td><td class="text-right">{{ row.netBillableKwh | kwh }}</td><td class="text-right">{{ row.totalInr | inr }}</td></tr>
                }</tbody></table></div>
            }
          }
        </section>
        <div class="table-wrap mt-4">
          <table class="table">
            <caption class="sr-only">Recent billing runs</caption>
            <thead><tr><th>Period</th><th>Started</th><th class="text-right">Meters</th><th class="text-right">Bills</th><th class="text-right">Billed</th><th>Status</th></tr></thead>
            <tbody>
              @for (run of runs(); track run.id) {
                <tr><td>{{ run.billingYear }}-{{ pad(run.billingMonth) }}</td><td>{{ run.startedAt | moment }}</td>
                  <td class="text-right">{{ run.metersProcessed }}</td><td class="text-right">{{ run.billsGenerated }}</td>
                  <td class="text-right">{{ run.totalBilledInr | inr }}</td><td><app-badge [status]="run.status" /></td></tr>
              } @empty { <tr><td colspan="6"><app-empty title="No billing runs yet" message="The automatic cycle runs at 02:00 IST on the 1st of each month." /></td></tr> }
            </tbody>
          </table>
        </div>
      }
      @case ('payments') {
        <div class="mt-5 flex justify-end">
          <button type="button" class="btn-secondary btn-sm" [disabled]="!payments()?.content?.length" (click)="exportPayments()">
            <svg lucideDownload [size]="14" aria-hidden="true"></svg> Export CSV</button>
        </div>
        <div class="table-wrap mt-3">
          <table class="table">
            <caption class="sr-only">Payments</caption>
            <thead><tr><th>Reference</th><th>Created</th><th>Method</th><th>Gateway</th><th class="text-right">Amount</th><th>Webhook</th><th>Status</th></tr></thead>
            <tbody>
              @for (payment of payments()?.content ?? []; track payment.id) {
                <tr><td class="font-medium">{{ payment.paymentReference }}</td><td>{{ payment.createdAt | moment }}</td>
                  <td>{{ payment.paymentMethod | humanize }}</td><td class="num text-muted">{{ payment.gatewayPaymentId ?? payment.gatewayOrderId }}</td>
                  <td class="text-right font-medium">{{ payment.amount | inr }}</td>
                  <td>{{ payment.webhookVerified ? 'Verified' : '—' }}</td><td><app-badge [status]="payment.status" /></td></tr>
              } @empty { <tr><td colspan="7"><app-empty title="No payments" /></td></tr> }
            </tbody>
          </table>
        </div>
        <app-pager [page]="page()" [size]="25" [total]="payments()?.totalElements ?? 0" (pageChange)="page.set($event); reload()" />
      }
    }

    <app-dialog [open]="!!detail()" [drawer]="true" [heading]="detail()?.bill?.billNumber ?? ''"
                [subheading]="detail() ? detail()!.consumerName + ' · ' + detail()!.consumerNumber : ''" (closed)="detail.set(null)">
      @if (detail(); as d) {
        <div class="space-y-5 text-sm">
          <div class="flex flex-wrap items-center gap-2"><app-badge [status]="d.bill.status" /><span class="text-muted">{{ d.bill.period | period }} · due {{ d.bill.dueDate | day }}</span>
            <button type="button" class="btn-secondary btn-sm ml-auto" (click)="pdf(d)"><svg lucideDownload [size]="14" aria-hidden="true"></svg> PDF</button></div>
          <table class="w-full"><tbody>
            @for (line of d.lineItems; track line.lineOrder) {
              <tr class="border-b border-line"><td class="py-1.5">{{ line.description }}</td><td class="num py-1.5 text-right">{{ line.amount | inr }}</td></tr>
            }
            <tr><td class="py-2 font-semibold">Invoice total</td><td class="num py-2 text-right font-semibold">{{ d.bill.totalAmount | inr }}</td></tr>
            @for (note of d.notes; track note.id) {
              <tr><td class="py-1 text-muted">{{ note.noteNumber }} · {{ note.reason }}</td><td class="num py-1 text-right">{{ (note.noteType === 'CREDIT' ? -note.amount : note.amount) | inr }}</td></tr>
            }
            <tr class="border-t-2 border-line"><td class="py-2 font-semibold">Due now</td><td class="num py-2 text-right font-semibold">{{ d.bill.totalDue | inr }}</td></tr>
          </tbody></table>
          @if (canWrite() && d.bill.status !== 'VOID') {
            <form class="space-y-3 rounded-xl border border-line p-4" (ngSubmit)="adjust(d)">
              <p class="font-semibold">Issue an adjustment</p>
              <div class="grid gap-3 sm:grid-cols-2">
                <div><label class="label" for="note-type">Type</label>
                  <select id="note-type" class="input" name="noteType" [(ngModel)]="note.noteType"><option value="CREDIT">Credit note</option><option value="DEBIT">Debit note</option></select></div>
                <div><label class="label" for="note-amount">Amount (₹)</label>
                  <input id="note-amount" class="input num" type="number" min="0.01" step="0.01" name="amount" [(ngModel)]="note.amount" required /></div>
              </div>
              <div><label class="label" for="note-reason">Reason</label>
                <input id="note-reason" class="input" name="reason" [(ngModel)]="note.reason" maxlength="500" required /></div>
              <button type="submit" class="btn-primary" [disabled]="busy() || !note.amount || !note.reason.trim()">Issue {{ note.noteType === 'CREDIT' ? 'credit' : 'debit' }} note</button>
            </form>
          }
        </div>
      }
    </app-dialog>
  `,
})
export class BillingOps {
  readonly accountId = input<string>();
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);
  /** Consumer name and number for the account filter pill; falls back to the raw ID. */
  protected readonly accountLabel = signal<string | null>(null);
  private readonly toasts = inject(Toasts);
  private readonly auth = inject(AuthStore);
  protected readonly canWrite = computed(() => ['BILLING_OFFICER', 'SUPER_ADMIN'].includes(this.auth.role() ?? ''));
  protected readonly tab = signal<Tab>('bills');
  protected readonly page = signal(0);
  protected readonly bills = signal<Page<BillSummary> | null>(null);
  protected readonly payments = signal<Page<Payment> | null>(null);
  protected readonly runs = signal<Run[]>([]);
  protected readonly result = signal<RunResult | null>(null);
  protected readonly detail = signal<BillDetail | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected readonly maxMonth = this.previousMonth();
  protected runMonth = this.previousMonth();
  protected query = '';
  protected status = '';
  protected periodFilter = '';
  protected note = { noteType: 'CREDIT', amount: null as number | null, reason: '' };
  private runKey = idempotencyKey();

  constructor() {
    // Tracks only the accountId route input; tab and page changes call reload() themselves, once.
    effect(() => {
      const account = this.accountId();
      untracked(() => {
        this.page.set(0);
        void this.reload();
        void this.resolveAccount(account);
      });
    });
  }

  private async resolveAccount(id: string | undefined): Promise<void> {
    this.accountLabel.set(null);
    if (!id) return;
    try {
      const details = await firstValueFrom(this.http.get<ConsumerDetails>(`/api/v1/ops/consumers/${id}`));
      if (this.accountId() === id) this.accountLabel.set(`${details.account.fullName} · ${details.account.consumerNumber}`);
    } catch {
      // The pill keeps showing the ID.
    }
  }

  protected clearAccount(): void {
    void this.router.navigate([], { queryParams: { accountId: null }, queryParamsHandling: 'merge' });
  }

  protected exportBills(): void {
    const period = this.periodFilter ? `-${this.periodFilter}` : '';
    exportCsv(`gridiq-ops-bills${period}-page-${this.page() + 1}.csv`, this.bills()?.content ?? [], [
      { header: 'Bill number', value: (b) => b.billNumber },
      { header: 'Account ID', value: (b) => b.consumerAccountId },
      { header: 'Meter', value: (b) => b.meterSerial },
      { header: 'Period', value: (b) => b.period },
      { header: 'Net billable kWh', value: (b) => b.netBillableKwh },
      { header: 'Total (INR)', value: (b) => b.totalAmount },
      { header: 'Paid (INR)', value: (b) => b.paidAmount },
      { header: 'Due now (INR)', value: (b) => b.totalDue },
      { header: 'Due date', value: (b) => b.dueDate },
      { header: 'Status', value: (b) => b.status },
    ]);
  }

  protected exportPayments(): void {
    exportCsv(`gridiq-ops-payments-page-${this.page() + 1}.csv`, this.payments()?.content ?? [], [
      { header: 'Reference', value: (p) => p.paymentReference },
      { header: 'Created', value: (p) => p.createdAt },
      { header: 'Completed', value: (p) => p.completedAt },
      { header: 'Method', value: (p) => p.paymentMethod },
      { header: 'Gateway', value: (p) => p.gatewayProvider },
      { header: 'Amount (INR)', value: (p) => p.amount },
      { header: 'Webhook verified', value: (p) => p.webhookVerified },
      { header: 'Status', value: (p) => p.status },
    ]);
  }

  protected pad(value: number): string {
    return String(value).padStart(2, '0');
  }

  protected setTab(tab: Tab): void {
    this.tab.set(tab);
    this.page.set(0);
    void this.reload();
  }

  async reload(): Promise<void> {
    this.error.set(null);
    try {
      if (this.tab() === 'runs') {
        this.runs.set(await firstValueFrom(this.http.get<Run[]>('/api/v1/ops/bills/runs')));
      } else if (this.tab() === 'payments') {
        this.payments.set(await firstValueFrom(this.http.get<Page<Payment>>(`/api/v1/ops/payments?size=25&page=${this.page()}`)));
      } else {
        const params = new URLSearchParams({ page: String(this.page()), size: '20', q: this.query.trim() });
        if (this.status) params.set('status', this.status);
        if (this.accountId()) params.set('accountId', this.accountId()!);
        if (this.periodFilter) {
          const [year, month] = this.periodFilter.split('-');
          params.set('year', String(+year));
          params.set('month', String(+month));
        }
        this.bills.set(await firstValueFrom(this.http.get<Page<BillSummary>>(`/api/v1/ops/bills?${params}`)));
      }
    } catch (error) {
      this.error.set(problemMessage(error, 'Billing data could not be loaded.'));
    }
  }

  protected async open(id: string): Promise<void> {
    try {
      this.note = { noteType: 'CREDIT', amount: null, reason: '' };
      this.detail.set(await firstValueFrom(this.http.get<BillDetail>(`/api/v1/ops/bills/${id}`)));
    } catch (error) {
      this.toasts.error(error);
    }
  }

  protected async adjust(detail: BillDetail): Promise<void> {
    this.busy.set(true);
    try {
      this.detail.set(await firstValueFrom(this.http.post<BillDetail>(`/api/v1/ops/bills/${detail.bill.id}/adjustments`, this.note)));
      this.toasts.success('Adjustment issued and posted to the ledger.');
      this.note = { noteType: 'CREDIT', amount: null, reason: '' };
      void this.reload();
    } catch (error) {
      this.toasts.error(error);
    } finally {
      this.busy.set(false);
    }
  }

  protected async run(dryRun: boolean): Promise<void> {
    const [year, month] = this.runMonth.split('-').map(Number);
    this.busy.set(true);
    try {
      const headers = dryRun ? undefined : new HttpHeaders({ 'Idempotency-Key': this.runKey });
      this.result.set(await firstValueFrom(this.http.post<RunResult>('/api/v1/ops/bills/runs', { year, month, dryRun }, { headers })));
      if (!dryRun) {
        this.runKey = idempotencyKey();
        this.toasts.success('Billing run finished.');
        void this.reload();
      }
    } catch (error) {
      this.toasts.error(error);
    } finally {
      this.busy.set(false);
    }
  }

  protected async pdf(detail: BillDetail): Promise<void> {
    await download(this.http, `/api/v1/ops/bills/${detail.bill.id}/pdf`, `${detail.bill.billNumber}.pdf`).catch((e) => this.toasts.error(e));
  }

  private previousMonth(): string {
    const now = new Date(Date.now() + 5.5 * 3600 * 1000);
    return new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth() - 1, 1)).toISOString().slice(0, 7);
  }
}
