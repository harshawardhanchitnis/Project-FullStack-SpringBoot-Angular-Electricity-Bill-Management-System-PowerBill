import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { Router } from '@angular/router';
import { LucideCheck, LucideDownload, LucideShieldCheck } from '@lucide/angular';
import { firstValueFrom } from 'rxjs';
import { BillDetail, BillSummary, LedgerLine, Page, Payment } from '../../core/models';
import { NotificationStore, Toasts, problemMessage } from '../../core/ui-state';
import { CustomerStore } from '../../core/customer';
import { exportCsv } from '../../shared/csv';
import { download, idempotencyKey } from '../../shared/download';
import { FORMAT_PIPES, inr } from '../../shared/format';
import { FormsModule } from '@angular/forms';
import { Dialog, EmptyState, ErrorBanner, Pager, StatusBadge } from '../../shared/ui';

type Tab = 'unpaid' | 'paid' | 'payments' | 'statement';
type Method = 'UPI' | 'CREDIT_CARD' | 'DEBIT_CARD' | 'NET_BANKING';

const METHODS: { value: Method; label: string }[] = [
  { value: 'UPI', label: 'UPI' },
  { value: 'CREDIT_CARD', label: 'Credit card' },
  { value: 'DEBIT_CARD', label: 'Debit card' },
  { value: 'NET_BANKING', label: 'Net banking' },
];

@Component({
  imports: [FormsModule, Dialog, EmptyState, ErrorBanner, Pager, StatusBadge, ...FORMAT_PIPES, LucideDownload, LucideCheck,
    LucideShieldCheck],
  template: `
    <div class="flex flex-wrap items-end justify-between gap-3">
      <div>
        <h1 class="h-page">Bills & payments</h1>
        <p class="mt-1 text-sm text-muted">Every invoice is itemized from validated hourly meter readings.</p>
      </div>
      <div class="tabs" role="tablist" aria-label="Bill views">
        @for (item of tabs; track item.value) {
          <button type="button" role="tab" class="tab" [attr.aria-selected]="current() === item.value" (click)="setTab(item.value)">
            {{ item.label }}
          </button>
        }
      </div>
    </div>

    @if (error()) { <div class="mt-5"><app-error [message]="error()!" (retry)="reload()" /></div> }

    @if (current() !== 'statement') {
      <div class="mt-5 flex flex-wrap items-end gap-2">
        @if (current() !== 'payments') {
          @if (customer.meters().length > 1) {
            <div><label class="label" for="bill-meter">Meter</label>
              <select id="bill-meter" class="input w-auto" [ngModel]="meterFilter()" (ngModelChange)="filter({ meter: $event })">
                <option value="">All meters</option>
                @for (meter of customer.meters(); track meter.id) { <option [value]="meter.id">{{ meter.meterSerial }}</option> }
              </select></div>
          }
          <div><label class="label" for="bill-year">Year</label>
            <select id="bill-year" class="input w-auto" [ngModel]="yearFilter()" (ngModelChange)="filter({ year: $event })">
              <option value="">All years</option>
              @for (year of years; track year) { <option [value]="year">{{ year }}</option> }
            </select></div>
        }
        <button type="button" class="btn-secondary btn-sm ml-auto" [disabled]="!exportable()" (click)="exportCurrent()">
          <svg lucideDownload [size]="14" aria-hidden="true"></svg> Export CSV</button>
      </div>
    }

    @switch (current()) {
      @case ('payments') {
        <div class="table-wrap mt-3">
          <table class="table">
            <caption class="sr-only">Payment history</caption>
            <thead><tr><th>Reference</th><th>Date</th><th>Method</th><th class="text-right">Amount</th><th>Status</th><th><span class="sr-only">Receipt</span></th></tr></thead>
            <tbody>
              @for (payment of payments()?.content ?? []; track payment.id) {
                <tr>
                  <td class="font-medium">{{ payment.paymentReference }}</td>
                  <td>{{ (payment.completedAt ?? payment.createdAt) | moment }}</td>
                  <td>{{ payment.paymentMethod | humanize }}</td>
                  <td class="text-right font-medium">{{ payment.amount | inr }}</td>
                  <td><app-badge [status]="payment.status" /></td>
                  <td class="text-right">
                    @if (payment.status === 'SUCCEEDED') {
                      <button type="button" class="btn-ghost btn-sm" (click)="receipt(payment)">
                        <svg lucideDownload [size]="14" aria-hidden="true"></svg> Receipt
                      </button>
                    }
                  </td>
                </tr>
              } @empty {
                <tr><td colspan="6"><app-empty title="No payments yet" message="Payments you make appear here with receipts." /></td></tr>
              }
            </tbody>
          </table>
        </div>
      }
      @case ('statement') {
        <div class="table-wrap mt-5">
          <table class="table">
            <caption class="sr-only">Account statement</caption>
            <thead><tr><th>Date</th><th>Entry</th><th class="text-right">Debit</th><th class="text-right">Credit</th><th class="text-right">Balance</th></tr></thead>
            <tbody>
              @for (line of ledger(); track line.id) {
                <tr>
                  <td>{{ line.createdAt | day }}</td>
                  <td>{{ line.description }}</td>
                  <td class="text-right">{{ line.entryType === 'DEBIT' ? (line.amount | inr) : '' }}</td>
                  <td class="text-right text-ok">{{ line.entryType === 'CREDIT' ? (line.amount | inr) : '' }}</td>
                  <td class="text-right font-medium">{{ line.runningBalance | inr }}</td>
                </tr>
              } @empty {
                <tr><td colspan="5"><app-empty title="No ledger entries" /></td></tr>
              }
            </tbody>
          </table>
        </div>
      }
      @default {
        <div class="table-wrap mt-3">
          <table class="table">
            <caption class="sr-only">{{ current() === 'unpaid' ? 'Unpaid bills' : 'Paid bills' }}</caption>
            <thead>
              <tr>
                @if (current() === 'unpaid') {
                  <th class="w-10"><input type="checkbox" class="size-4 accent-[var(--brand)]" aria-label="Select all unpaid bills"
                                          [checked]="allSelected()" (change)="toggleAll()" /></th>
                }
                <th>Bill</th><th>Period</th><th>Meter</th><th class="text-right">Energy</th>
                <th class="text-right">{{ current() === 'unpaid' ? 'Due now' : 'Total' }}</th><th>Due date</th><th>Status</th>
              </tr>
            </thead>
            <tbody>
              @for (bill of bills()?.content ?? []; track bill.id) {
                <tr class="clickable" (click)="open(bill.id)">
                  @if (current() === 'unpaid') {
                    <td (click)="$event.stopPropagation()">
                      <input type="checkbox" class="size-4 accent-[var(--brand)]" [attr.aria-label]="'Select ' + bill.billNumber"
                             [checked]="selected().has(bill.id)" (change)="toggle(bill)" />
                    </td>
                  }
                  <td><button type="button" class="font-medium text-brand hover:underline" (click)="open(bill.id); $event.stopPropagation()">{{ bill.billNumber }}</button></td>
                  <td>{{ bill.period | period }}</td>
                  <td class="text-muted">{{ bill.meterSerial }}</td>
                  <td class="text-right">{{ bill.netBillableKwh | kwh }}</td>
                  <td class="text-right font-medium">
                    {{ (current() === 'unpaid' ? bill.totalDue : bill.totalAmount) | inr }}
                    @if (bill.accruedLateFee > 0) { <span class="block text-xs font-normal text-bad">incl. {{ bill.accruedLateFee | inr }} late fee</span> }
                  </td>
                  <td>{{ bill.dueDate | day }}</td>
                  <td><app-badge [status]="bill.status" /></td>
                </tr>
              } @empty {
                <tr><td [attr.colspan]="current() === 'unpaid' ? 8 : 7">
                  <app-empty [title]="current() === 'unpaid' ? 'You are all paid up' : 'No paid bills yet'"
                             [message]="current() === 'unpaid' ? 'New bills appear here on the 1st of each month.' : ''" />
                </td></tr>
              }
            </tbody>
          </table>
        </div>
        <app-pager [page]="page()" [size]="20" [total]="bills()?.totalElements ?? 0" (pageChange)="page.set($event); reload()" />
      }
    }

    @if (selected().size > 0 && current() === 'unpaid') {
      <div class="sticky bottom-4 z-10 mt-6 flex flex-wrap items-center justify-between gap-3 rounded-xl border border-line bg-raised p-4 shadow-lg">
        <p class="text-sm"><span class="font-semibold">{{ selected().size }} bill{{ selected().size > 1 ? 's' : '' }}</span> selected ·
          <span class="num font-semibold">{{ selectedTotal() | inr }}</span> due</p>
        <button type="button" class="btn-primary" (click)="startCheckout()">Pay {{ selectedTotal() | inr }}</button>
      </div>
    }

    <app-dialog [open]="!!detail()" [drawer]="true" [heading]="detail()?.bill?.billNumber ?? 'Bill'"
                [subheading]="detail() ? ((detail()!.bill.period | period) + ' · ' + detail()!.tariffName) : ''" (closed)="close()">
      @if (detail(); as d) {
        <div class="space-y-5 text-sm">
          <div class="flex flex-wrap items-center gap-2">
            <app-badge [status]="d.bill.status" />
            <span class="text-muted">Due {{ d.bill.dueDate | day }}</span>
            <button type="button" class="btn-secondary btn-sm ml-auto" (click)="pdf(d)">
              <svg lucideDownload [size]="14" aria-hidden="true"></svg> Invoice PDF
            </button>
          </div>
          <dl class="grid grid-cols-2 gap-3">
            <div class="rounded-lg bg-sunken p-3"><dt class="text-xs text-faint">Imported</dt><dd class="num mt-1 font-semibold">{{ d.bill.importKwh | kwh }}</dd></div>
            <div class="rounded-lg bg-sunken p-3"><dt class="text-xs text-faint">Net billable</dt><dd class="num mt-1 font-semibold">{{ d.bill.netBillableKwh | kwh }}</dd></div>
            @if (d.bill.exportKwh > 0 || d.openingCreditKwh > 0) {
              <div class="rounded-lg bg-ok-soft p-3"><dt class="text-xs text-faint">Solar exported</dt><dd class="num mt-1 font-semibold">{{ d.bill.exportKwh | kwh }}</dd></div>
              <div class="rounded-lg bg-ok-soft p-3"><dt class="text-xs text-faint">Credit carried forward</dt><dd class="num mt-1 font-semibold">{{ d.carriedForwardCreditKwh | kwh }}</dd></div>
            }
          </dl>
          <section>
            <h3 class="h-section mb-2">How this bill was calculated</h3>
            <table class="w-full text-sm">
              <tbody>
                @for (line of d.lineItems; track line.lineOrder) {
                  <tr class="border-b border-line">
                    <td class="py-2">{{ line.description }}
                      @if (line.units !== null && line.rate !== null) {
                        <span class="block text-xs text-faint num">{{ line.units }} × {{ line.category === 'DUTY' || line.category === 'GST' ? line.rate + '%' : '₹' + line.rate }}</span>
                      }
                    </td>
                    <td class="num py-2 text-right" [class.text-ok]="line.amount < 0">{{ line.amount | inr }}</td>
                  </tr>
                }
                <tr><td class="py-2 font-semibold">Invoice total</td><td class="num py-2 text-right font-semibold">{{ d.bill.totalAmount | inr }}</td></tr>
                @for (note of d.notes; track note.id) {
                  <tr><td class="py-1 text-muted">{{ note.noteType === 'CREDIT' ? 'Credit' : 'Debit' }} note {{ note.noteNumber }} · {{ note.reason }}</td>
                    <td class="num py-1 text-right" [class.text-ok]="note.noteType === 'CREDIT'">{{ (note.noteType === 'CREDIT' ? -note.amount : note.amount) | inr }}</td></tr>
                }
                @if (d.bill.paidAmount > 0) {
                  <tr><td class="py-1 text-muted">Paid{{ d.bill.lateFeePaid > 0 ? ' (incl. ' + money(d.bill.lateFeePaid) + ' late fee)' : '' }}</td>
                    <td class="num py-1 text-right text-ok">{{ -d.bill.paidAmount | inr }}</td></tr>
                }
                @if (d.bill.accruedLateFee > 0) {
                  <tr><td class="py-1 text-bad">Late payment surcharge to date</td><td class="num py-1 text-right text-bad">{{ d.bill.accruedLateFee | inr }}</td></tr>
                }
                <tr class="border-t-2 border-line"><td class="py-2 font-semibold">Amount due</td><td class="num py-2 text-right text-lg font-semibold">{{ d.bill.totalDue | inr }}</td></tr>
              </tbody>
            </table>
          </section>
          <p class="text-xs text-faint">Based on {{ d.intervalCount }} validated hourly readings. Tariff {{ d.tariffCode }} v{{ d.tariffVersion }}.</p>
        </div>
      }
      <div dialog-footer class="empty:hidden border-t border-line p-4">
        @if (detail() && detail()!.bill.totalDue > 0) {
          <button type="button" class="btn-primary w-full" (click)="payOne(detail()!.bill)">Pay {{ detail()!.bill.totalDue | inr }}</button>
        }
      </div>
    </app-dialog>

    <app-dialog [open]="checkoutOpen()" heading="Secure checkout" [subheading]="checkoutCount() + (checkoutCount() === 1 ? ' bill · ' : ' bills · ') + money(checkoutTotal())" (closed)="cancelCheckout()">
      @switch (stage()) {
        @case ('method') {
          <fieldset>
            <legend class="label">Payment method</legend>
            <div class="grid grid-cols-2 gap-2">
              @for (option of methods; track option.value) {
                <label class="flex cursor-pointer items-center gap-2 rounded-lg border p-3 text-sm"
                       [class]="method() === option.value ? 'border-brand bg-brand-soft' : 'border-line'">
                  <input type="radio" name="method" class="accent-[var(--brand)]" [checked]="method() === option.value" (change)="method.set(option.value)" />
                  {{ option.label }}
                </label>
              }
            </div>
          </fieldset>
          <p class="mt-4 flex gap-2 text-xs text-muted"><svg lucideShieldCheck [size]="16" class="shrink-0 text-ok" aria-hidden="true"></svg>
            Amounts, including any late fee, are calculated by GridIQ. Every bill is settled together or not at all.</p>
        }
        @case ('gateway') {
          <div class="rounded-xl border border-dashed border-line p-4 text-sm">
            <p class="eyebrow">Sandbox gateway</p>
            <p class="mt-2 text-2xl font-semibold num">{{ payment()?.amount | inr }}</p>
            <p class="text-muted">Reference {{ payment()?.paymentReference }} · {{ methodLabel() }}</p>
            <p class="mt-3 text-xs text-faint">This test gateway signs its webhook with HMAC-SHA256 exactly like a live provider. No money moves.</p>
          </div>
        }
        @case ('done') {
          @if (payment()?.status === 'SUCCEEDED') {
            <div class="text-center">
              <span class="mx-auto inline-flex size-12 items-center justify-center rounded-full bg-ok-soft text-ok">
                <svg lucideCheck [size]="24" aria-hidden="true"></svg>
              </span>
              <p class="mt-3 text-lg font-semibold">Payment successful</p>
              <p class="text-sm text-muted">{{ payment()?.amount | inr }} · {{ payment()?.paymentReference }}</p>
            </div>
          } @else {
            <p role="alert" class="rounded-lg bg-bad-soft p-3 text-sm">Payment {{ payment()?.status | humanize }}: {{ payment()?.failureReason }}</p>
          }
        }
      }
      @if (checkoutError()) { <p role="alert" class="mt-3 rounded-lg bg-bad-soft p-3 text-sm">{{ checkoutError() }}</p> }
      <div dialog-footer class="empty:hidden flex justify-end gap-2 border-t border-line p-4">
        @switch (stage()) {
          @case ('method') {
            <button type="button" class="btn-secondary" (click)="cancelCheckout()">Cancel</button>
            <button type="button" class="btn-primary" [disabled]="busy()" (click)="checkout()">Continue</button>
          }
          @case ('gateway') {
            <button type="button" class="btn-secondary" [disabled]="busy()" (click)="confirm('FAILED')">Decline</button>
            <button type="button" class="btn-primary" [disabled]="busy()" (click)="confirm('SUCCEEDED')">{{ busy() ? 'Processing…' : 'Approve payment' }}</button>
          }
          @case ('done') {
            @if (payment()?.status === 'SUCCEEDED') {
              <button type="button" class="btn-secondary" (click)="receipt(payment()!)"><svg lucideDownload [size]="14" aria-hidden="true"></svg> Receipt</button>
            }
            <button type="button" class="btn-primary" (click)="cancelCheckout()">Done</button>
          }
        }
      </div>
    </app-dialog>
  `,
})
export class Bills {
  readonly bill = input<string>();
  readonly tab = input<string>();
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);
  private readonly toasts = inject(Toasts);
  private readonly notifications = inject(NotificationStore);
  protected readonly customer = inject(CustomerStore);
  protected readonly methods = METHODS;
  protected readonly years = Array.from({ length: 4 }, (_, index) => new Date().getFullYear() - index);
  protected readonly meterFilter = signal('');
  protected readonly yearFilter = signal('');
  protected readonly exportable = computed(() => this.current() === 'payments'
    ? (this.payments()?.content.length ?? 0) > 0 : (this.bills()?.content.length ?? 0) > 0);
  protected readonly tabs: { value: Tab; label: string }[] = [
    { value: 'unpaid', label: 'Unpaid' }, { value: 'paid', label: 'Paid' },
    { value: 'payments', label: 'Payments' }, { value: 'statement', label: 'Statement' },
  ];
  protected readonly current = signal<Tab>('unpaid');
  protected readonly page = signal(0);
  protected readonly bills = signal<Page<BillSummary> | null>(null);
  protected readonly payments = signal<Page<Payment> | null>(null);
  protected readonly ledger = signal<LedgerLine[]>([]);
  protected readonly error = signal<string | null>(null);
  protected readonly detail = signal<BillDetail | null>(null);
  protected readonly selected = signal(new Map<string, BillSummary>());
  protected readonly selectedTotal = computed(() =>
    [...this.selected().values()].reduce((sum, bill) => sum + bill.totalDue, 0));
  protected readonly allSelected = computed(() => {
    const open = (this.bills()?.content ?? []).filter((bill) => bill.totalDue > 0);
    return open.length > 0 && open.every((bill) => this.selected().has(bill.id));
  });
  protected readonly checkoutOpen = signal(false);
  protected readonly checkoutTotal = signal(0);
  protected readonly checkoutCount = signal(0);
  protected readonly stage = signal<'method' | 'gateway' | 'done'>('method');
  protected readonly method = signal<Method>('UPI');
  protected readonly payment = signal<Payment | null>(null);
  protected readonly busy = signal(false);
  protected readonly checkoutError = signal<string | null>(null);
  private key = idempotencyKey();

  constructor() {
    // Tracks only the route's tab; reload() reads other signals untracked so each change fetches exactly once.
    let initial = true;
    effect(() => {
      const param = this.tab();
      const next: Tab = param === 'payments' || param === 'paid' || param === 'statement' ? param : 'unpaid';
      untracked(() => {
        if (!initial && next === this.current()) return;
        initial = false;
        this.current.set(next);
        this.page.set(0);
        void this.reload();
      });
    });
    effect(() => {
      const id = this.bill();
      if (id) void this.open(id);
    });
  }

  protected methodLabel(): string {
    return METHODS.find((option) => option.value === this.method())?.label ?? '';
  }

  protected money(value: number): string {
    return inr(value);
  }

  protected filter(change: { meter?: string; year?: string }): void {
    if (change.meter !== undefined) this.meterFilter.set(change.meter);
    if (change.year !== undefined) this.yearFilter.set(String(change.year));
    this.page.set(0);
    this.selected.set(new Map());
    void this.reload();
  }

  /** Exports the rows on screen (the current page and filters) as CSV. */
  protected exportCurrent(): void {
    if (this.current() === 'payments') {
      exportCsv('gridiq-payments.csv', this.payments()?.content ?? [], [
        { header: 'Reference', value: (p) => p.paymentReference },
        { header: 'Date', value: (p) => p.completedAt ?? p.createdAt },
        { header: 'Method', value: (p) => p.paymentMethod },
        { header: 'Amount (INR)', value: (p) => p.amount },
        { header: 'Status', value: (p) => p.status },
      ]);
      return;
    }
    const suffix = [this.current(), this.yearFilter()].filter(Boolean).join('-');
    exportCsv(`gridiq-bills-${suffix}.csv`, this.bills()?.content ?? [], [
      { header: 'Bill number', value: (b) => b.billNumber },
      { header: 'Period', value: (b) => b.period },
      { header: 'Meter', value: (b) => b.meterSerial },
      { header: 'Net billable kWh', value: (b) => b.netBillableKwh },
      { header: 'Total (INR)', value: (b) => b.totalAmount },
      { header: 'Due now (INR)', value: (b) => b.totalDue },
      { header: 'Late fee (INR)', value: (b) => b.accruedLateFee },
      { header: 'Due date', value: (b) => b.dueDate },
      { header: 'Status', value: (b) => b.status },
    ]);
  }

  protected setTab(tab: Tab): void {
    if (tab === this.current()) return;
    this.current.set(tab);
    this.page.set(0);
    this.selected.set(new Map());
    void this.reload();
    // The route change that follows sees the tab already active and does not fetch again.
    void this.router.navigate([], { queryParams: { tab, bill: null }, queryParamsHandling: 'merge', replaceUrl: true });
  }

  async reload(): Promise<void> {
    this.error.set(null);
    try {
      switch (this.current()) {
        case 'payments':
          this.payments.set(await firstValueFrom(this.http.get<Page<Payment>>('/api/v1/payments?size=50')));
          break;
        case 'statement':
          this.ledger.set(await firstValueFrom(this.http.get<LedgerLine[]>('/api/v1/consumers/me/ledger?limit=100')));
          break;
        default: {
          const params = new URLSearchParams({ status: this.current() === 'unpaid' ? 'OPEN' : 'SETTLED', size: '20',
            page: String(this.page()) });
          if (this.meterFilter()) params.set('meterId', this.meterFilter());
          if (this.yearFilter()) params.set('year', this.yearFilter());
          this.bills.set(await firstValueFrom(this.http.get<Page<BillSummary>>(`/api/v1/bills?${params}`)));
        }
      }
    } catch (error) {
      this.error.set(problemMessage(error, 'Your bills could not be loaded.'));
    }
  }

  protected async open(id: string): Promise<void> {
    try {
      this.detail.set(await firstValueFrom(this.http.get<BillDetail>(`/api/v1/bills/${id}`)));
    } catch (error) {
      this.toasts.error(error);
    }
  }

  protected close(): void {
    this.detail.set(null);
    if (this.bill()) void this.router.navigate([], { queryParams: { bill: null }, queryParamsHandling: 'merge', replaceUrl: true });
  }

  protected toggle(bill: BillSummary): void {
    const next = new Map(this.selected());
    if (next.has(bill.id)) next.delete(bill.id);
    else next.set(bill.id, bill);
    this.selected.set(next);
  }

  protected toggleAll(): void {
    const open = (this.bills()?.content ?? []).filter((bill) => bill.totalDue > 0);
    this.selected.set(this.allSelected() ? new Map() : new Map(open.map((bill) => [bill.id, bill])));
  }

  protected payOne(bill: BillSummary): void {
    this.selected.set(new Map([[bill.id, bill]]));
    this.detail.set(null);
    this.startCheckout();
  }

  protected startCheckout(): void {
    this.key = idempotencyKey();
    this.payment.set(null);
    this.checkoutError.set(null);
    this.stage.set('method');
    this.checkoutTotal.set(this.selectedTotal());
    this.checkoutCount.set(this.selected().size);
    this.checkoutOpen.set(true);
  }

  protected async checkout(): Promise<void> {
    this.busy.set(true);
    this.checkoutError.set(null);
    try {
      const payment = await firstValueFrom(this.http.post<Payment>('/api/v1/payments/checkout',
        { billIds: [...this.selected().keys()], paymentMethod: this.method() },
        { headers: new HttpHeaders({ 'Idempotency-Key': this.key }) }));
      this.payment.set(payment);
      this.stage.set('gateway');
    } catch (error) {
      this.checkoutError.set(problemMessage(error, 'Checkout could not start.'));
    } finally {
      this.busy.set(false);
    }
  }

  protected async confirm(outcome: 'SUCCEEDED' | 'FAILED'): Promise<void> {
    const payment = this.payment();
    if (!payment) return;
    this.busy.set(true);
    try {
      const result = await firstValueFrom(this.http.post<Payment>(`/api/v1/payments/sandbox/${payment.id}/confirm`, { outcome }));
      this.payment.set(result);
      this.stage.set('done');
      if (result.status === 'SUCCEEDED') {
        this.toasts.success(`Payment of ${inr(result.amount)} received.`);
        this.selected.set(new Map());
      }
      void this.reload();
      void this.notifications.load().catch(() => undefined);
    } catch (error) {
      this.checkoutError.set(problemMessage(error, 'The gateway did not respond. Check your payments before retrying.'));
    } finally {
      this.busy.set(false);
    }
  }

  protected cancelCheckout(): void {
    this.checkoutOpen.set(false);
  }

  protected async pdf(detail: BillDetail): Promise<void> {
    await download(this.http, `/api/v1/bills/${detail.bill.id}/pdf`, `${detail.bill.billNumber}.pdf`)
      .catch((error) => this.toasts.error(error));
  }

  protected async receipt(payment: Payment): Promise<void> {
    await download(this.http, `/api/v1/payments/${payment.id}/receipt/pdf`, `${payment.paymentReference}.pdf`)
      .catch((error) => this.toasts.error(error));
  }
}
