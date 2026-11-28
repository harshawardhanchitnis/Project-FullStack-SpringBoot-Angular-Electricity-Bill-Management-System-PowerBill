import { HttpClient } from '@angular/common/http';
import { Component, computed, effect, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { LucidePlus, LucideSiren, LucideStar } from '@lucide/angular';
import { firstValueFrom } from 'rxjs';
import { CustomerStore } from '../../core/customer';
import { BillSummary, Complaint, ComplaintDetail, Outage, Page } from '../../core/models';
import { Toasts, problemMessage } from '../../core/ui-state';
import { FORMAT_PIPES, humanize, period, relative } from '../../shared/format';
import { Dialog, EmptyState, ErrorBanner, StatusBadge } from '../../shared/ui';

export const COMPLAINT_TYPES = [
  { value: 'POWER_OUTAGE', label: 'Power outage', hint: 'No supply at your premises · 4-hour service level' },
  { value: 'VOLTAGE_FLUCTUATION', label: 'Voltage fluctuation', hint: 'Flickering lights or tripping appliances · 24 hours' },
  { value: 'METER_FAULT', label: 'Meter fault', hint: 'Blank display or wrong readings · 24 hours' },
  { value: 'BILLING', label: 'Billing question', hint: 'Unexpected amount or charge · 48 hours' },
  { value: 'SOLAR_NET_METERING', label: 'Solar net metering', hint: 'Export credits or bi-directional meter · 48 hours' },
  { value: 'CONNECTION', label: 'Connection or load change', hint: 'New connection, load increase, transfer · 48 hours' },
  { value: 'TECHNICAL', label: 'Portal or app issue', hint: 'Problems using GridIQ · 72 hours' },
  { value: 'OTHER', label: 'Something else', hint: '72 hours' },
];

export const STEPS = ['OPEN', 'ASSIGNED', 'IN_PROGRESS', 'RESOLVED', 'CLOSED'];

export function slaText(complaint: Complaint): { text: string; tone: string } {
  if (complaint.status === 'RESOLVED' || complaint.status === 'CLOSED') {
    return complaint.slaBreached ? { text: 'Resolved late', tone: 'bg-warn-soft text-warn' } : { text: 'Within SLA', tone: 'bg-ok-soft text-ok' };
  }
  const due = new Date(complaint.slaDueAt).getTime();
  if (complaint.slaBreached || due < Date.now()) return { text: `SLA breached ${relative(complaint.slaDueAt)}`, tone: 'bg-bad-soft text-bad' };
  const hours = (due - Date.now()) / 3600_000;
  return { text: `Due ${relative(complaint.slaDueAt)}`, tone: hours < 4 ? 'bg-warn-soft text-warn' : 'bg-sunken text-muted' };
}

@Component({
  imports: [FormsModule, Dialog, EmptyState, ErrorBanner, StatusBadge, ...FORMAT_PIPES, LucidePlus, LucideSiren, LucideStar],
  template: `
    <div class="flex flex-wrap items-end justify-between gap-3">
      <div>
        <h1 class="h-page">Support & outages</h1>
        <p class="mt-1 text-sm text-muted">Track every request against its service level.</p>
      </div>
      <button type="button" class="btn-primary" (click)="openWizard()"><svg lucidePlus [size]="16" aria-hidden="true"></svg> New complaint</button>
    </div>

    <section class="mt-6" aria-labelledby="outage-heading">
      <h2 id="outage-heading" class="sr-only">Outages in your area</h2>
      @for (outage of outages(); track outage.id) {
        <div class="mb-3 flex gap-3 rounded-xl border border-warn/40 bg-warn-soft p-4 text-sm">
          <svg lucideSiren [size]="18" class="mt-0.5 shrink-0 text-warn" aria-hidden="true"></svg>
          <div class="min-w-0">
            <p class="font-semibold">{{ outage.title }} <app-badge class="ml-1" [status]="outage.severity" /></p>
            <p class="text-muted">{{ outage.description }}</p>
            <p class="mt-1 text-xs text-faint">{{ outage.status | humanize }} · {{ outage.incidentCode }}
              @if (outage.estimatedRestorationAt) { · restoration expected {{ outage.estimatedRestorationAt | moment }} IST }</p>
          </div>
        </div>
      } @empty {
        <p class="rounded-xl border border-line bg-surface p-4 text-sm text-muted">No outages reported in your area.</p>
      }
    </section>

    <div class="mt-6 flex flex-wrap items-center justify-between gap-3">
      <div class="tabs" role="tablist" aria-label="Complaint status">
        @for (option of filters; track option.value) {
          <button type="button" role="tab" class="tab" [attr.aria-selected]="filter() === option.value" (click)="filter.set(option.value)">{{ option.label }}</button>
        }
      </div>
      <form class="flex gap-2" role="search" (ngSubmit)="load()">
        <label for="ticket-search" class="sr-only">Search tickets</label>
        <input id="ticket-search" class="input w-56" name="q" [(ngModel)]="query" placeholder="Ticket number or subject" />
        <button type="submit" class="btn-secondary">Search</button>
      </form>
    </div>

    @if (error()) { <div class="mt-4"><app-error [message]="error()!" (retry)="load()" /></div> }
    <div class="mt-4 space-y-3">
      @for (complaint of visible(); track complaint.id) {
        <button type="button" class="card-pad block w-full text-left hover:border-brand" (click)="open(complaint.id)">
          <div class="flex flex-wrap items-center gap-2">
            <span class="font-semibold">{{ complaint.subject }}</span>
            <app-badge [status]="complaint.status" />
            <span class="badge" [class]="sla(complaint).tone">{{ sla(complaint).text }}</span>
          </div>
          <p class="mt-1 text-sm text-muted">{{ complaint.ticketNumber }} · {{ complaint.complaintType | humanize }} · {{ complaint.priority | humanize }} priority · opened {{ complaint.createdAt | relative }}</p>
        </button>
      } @empty {
        <div class="card"><app-empty title="No complaints" message="Raise a complaint if something is not right with your supply or bill." /></div>
      }
    </div>

    <app-dialog [open]="wizardOpen()" heading="New complaint" subheading="We route it to the right team automatically." (closed)="wizardOpen.set(false)">
      <form id="complaint-form" class="space-y-4" (ngSubmit)="create()">
        <fieldset>
          <legend class="label">What is the problem?</legend>
          <div class="grid gap-2">
            @for (type of types; track type.value) {
              <label class="flex cursor-pointer gap-3 rounded-lg border p-3 text-sm" [class]="draft.complaintType === type.value ? 'border-brand bg-brand-soft' : 'border-line'">
                <input type="radio" name="type" class="mt-0.5 accent-[var(--brand)]" [value]="type.value" [(ngModel)]="draft.complaintType" />
                <span><span class="font-medium">{{ type.label }}</span><span class="block text-xs text-muted">{{ type.hint }}</span></span>
              </label>
            }
          </div>
        </fieldset>
        <div class="grid gap-4 sm:grid-cols-2">
          <div>
            <label class="label" for="c-meter">Meter</label>
            <select id="c-meter" class="input" name="meter" [(ngModel)]="draft.meterId">
              @for (meter of customer.meters(); track meter.id) { <option [value]="meter.id">{{ meter.meterSerial }}</option> }
            </select>
          </div>
          <div>
            <label class="label" for="c-bill">Related bill (optional)</label>
            <select id="c-bill" class="input" name="bill" [(ngModel)]="draft.billId">
              <option [ngValue]="null">None</option>
              @for (bill of bills(); track bill.id) { <option [value]="bill.id">{{ bill.billNumber }} · {{ periodLabel(bill.period) }}</option> }
            </select>
          </div>
        </div>
        <div>
          <label class="label" for="c-subject">Subject</label>
          <input id="c-subject" class="input" name="subject" [(ngModel)]="draft.subject" required minlength="5" maxlength="160" />
        </div>
        <div>
          <label class="label" for="c-description">Describe the issue</label>
          <textarea id="c-description" class="input min-h-28 py-2" name="description" [(ngModel)]="draft.description" required minlength="10" maxlength="4000"></textarea>
        </div>
        @if (formError()) { <p role="alert" class="rounded-lg bg-bad-soft p-3 text-sm">{{ formError() }}</p> }
      </form>
      <div dialog-footer class="empty:hidden flex justify-end gap-2 border-t border-line p-4">
        <button type="button" class="btn-secondary" (click)="wizardOpen.set(false)">Cancel</button>
        <button type="submit" form="complaint-form" class="btn-primary" [disabled]="busy()">Submit complaint</button>
      </div>
    </app-dialog>

    <app-dialog [open]="!!detail()" [drawer]="true" [heading]="detail()?.complaint?.subject ?? ''"
                [subheading]="detail() ? detail()!.complaint.ticketNumber + ' · ' + humanize(detail()!.complaint.complaintType) : ''" (closed)="close()">
      @if (detail(); as d) {
        <ol class="flex items-center gap-1 text-[11px] font-medium" aria-label="Progress">
          @for (step of steps; track step; let i = $index) {
            <li class="flex flex-1 flex-col items-center gap-1 text-center" [attr.aria-current]="d.complaint.status === step ? 'step' : null">
              <span class="h-1.5 w-full rounded-full" [class]="stepIndex(d.complaint.status) >= i ? 'bg-brand' : 'bg-sunken'"></span>
              <span [class]="stepIndex(d.complaint.status) >= i ? 'text-ink' : 'text-faint'">{{ step | humanize }}</span>
            </li>
          }
        </ol>
        <div class="mt-4 flex flex-wrap gap-2">
          <span class="badge" [class]="sla(d.complaint).tone">{{ sla(d.complaint).text }}</span>
          <app-badge [status]="d.complaint.priority" [label]="humanize(d.complaint.priority) + ' priority'" />
        </div>
        <p class="mt-4 whitespace-pre-line text-sm">{{ d.complaint.description }}</p>
        @if (d.complaint.resolutionSummary) {
          <div class="mt-4 rounded-lg bg-ok-soft p-3 text-sm"><p class="font-semibold">Resolution</p><p>{{ d.complaint.resolutionSummary }}</p></div>
        }
        <h3 class="h-section mt-6">Conversation</h3>
        <ul class="mt-3 space-y-3">
          @for (comment of d.comments; track comment.id) {
            <li class="rounded-lg p-3 text-sm" [class]="comment.authorRole === 'CUSTOMER' ? 'ml-8 bg-brand-soft' : 'mr-8 bg-sunken'">
              <p class="text-xs font-semibold">{{ comment.authorRole === 'CUSTOMER' ? 'You' : 'GridIQ support' }} · <span class="font-normal text-faint">{{ comment.createdAt | moment }}</span></p>
              <p class="mt-1 whitespace-pre-line">{{ comment.body }}</p>
            </li>
          } @empty { <li class="text-sm text-muted">No messages yet.</li> }
        </ul>
        @if (d.complaint.status === 'RESOLVED') {
          <div class="mt-6 rounded-xl border border-line p-4">
            <p class="font-semibold">How did we do?</p>
            <div class="mt-2 flex gap-1" role="radiogroup" aria-label="Rating">
              @for (star of [1, 2, 3, 4, 5]; track star) {
                <button type="button" role="radio" class="icon-btn" [attr.aria-checked]="rating() === star" [attr.aria-label]="star + ' stars'" (click)="rating.set(star)">
                  <svg lucideStar [size]="22" aria-hidden="true" [class]="rating() >= star ? 'fill-[var(--warn)] text-warn' : 'text-faint'"></svg>
                </button>
              }
            </div>
            <label class="sr-only" for="csat-feedback">Feedback</label>
            <textarea id="csat-feedback" class="input mt-2 min-h-16 py-2" [(ngModel)]="feedback" placeholder="Optional feedback"></textarea>
            <button type="button" class="btn-primary mt-3" [disabled]="!rating() || busy()" (click)="rate()">Submit rating and close</button>
          </div>
        }
        @if (d.complaint.csatRating) { <p class="mt-4 text-sm text-muted">You rated this {{ d.complaint.csatRating }}/5. Thank you.</p> }
      }
      <div dialog-footer class="empty:hidden border-t border-line p-4">
        @if (detail() && detail()!.complaint.status !== 'CLOSED') {
          <form class="flex gap-2" (ngSubmit)="reply()">
            <label class="sr-only" for="reply">Reply</label>
            <input id="reply" class="input" name="reply" [(ngModel)]="replyText" maxlength="4000" placeholder="Write a reply" />
            <button type="submit" class="btn-primary" [disabled]="!replyText.trim() || busy()">Send</button>
          </form>
        }
      </div>
    </app-dialog>
  `,
})
export class Support {
  readonly ticket = input<string>();
  protected readonly customer = inject(CustomerStore);
  private readonly http = inject(HttpClient);
  private readonly toasts = inject(Toasts);
  private readonly router = inject(Router);
  protected readonly types = COMPLAINT_TYPES;
  protected readonly steps = STEPS;
  protected readonly humanize = humanize;
  protected readonly filters = [{ value: 'open', label: 'Open' }, { value: 'done', label: 'Resolved' }, { value: 'all', label: 'All' }] as const;
  protected readonly filter = signal<'open' | 'done' | 'all'>('open');
  protected readonly complaints = signal<Complaint[]>([]);
  protected readonly outages = signal<Outage[]>([]);
  protected readonly bills = signal<BillSummary[]>([]);
  protected readonly error = signal<string | null>(null);
  protected readonly detail = signal<ComplaintDetail | null>(null);
  protected readonly wizardOpen = signal(false);
  protected readonly busy = signal(false);
  protected readonly formError = signal<string | null>(null);
  protected readonly rating = signal(0);
  protected query = '';
  protected replyText = '';
  protected feedback = '';
  protected draft = this.blank();
  protected readonly visible = computed(() => this.complaints().filter((item) => {
    const done = item.status === 'RESOLVED' || item.status === 'CLOSED';
    return this.filter() === 'all' || (this.filter() === 'done' ? done : !done);
  }));

  constructor() {
    void this.load();
    firstValueFrom(this.http.get<Outage[]>('/api/v1/outages/active')).then((items) => this.outages.set(items)).catch(() => undefined);
    effect(() => {
      const id = this.ticket();
      if (id) void this.open(id);
    });
  }

  protected sla = slaText;

  protected periodLabel(value: string): string {
    return period(value);
  }

  protected stepIndex(status: string): number {
    return STEPS.indexOf(status);
  }

  async load(): Promise<void> {
    this.error.set(null);
    try {
      const page = await firstValueFrom(this.http.get<Page<Complaint>>(
        `/api/v1/complaints?size=100&q=${encodeURIComponent(this.query.trim())}`));
      this.complaints.set(page.content);
    } catch (error) {
      this.error.set(problemMessage(error, 'Your complaints could not be loaded.'));
    }
  }

  protected async openWizard(): Promise<void> {
    await this.customer.load().catch(() => undefined);
    this.draft = this.blank();
    this.formError.set(null);
    this.wizardOpen.set(true);
    const page = await firstValueFrom(this.http.get<Page<BillSummary>>('/api/v1/bills?size=12')).catch(() => null);
    this.bills.set(page?.content ?? []);
  }

  protected async create(): Promise<void> {
    if (this.draft.subject.trim().length < 5 || this.draft.description.trim().length < 10) {
      this.formError.set('Add a subject of at least 5 characters and a description of at least 10.');
      return;
    }
    this.busy.set(true);
    try {
      const created = await firstValueFrom(this.http.post<ComplaintDetail>('/api/v1/complaints', this.draft));
      this.wizardOpen.set(false);
      this.toasts.success(`Complaint ${created.complaint.ticketNumber} registered.`);
      await this.load();
      this.detail.set(created);
    } catch (error) {
      this.formError.set(problemMessage(error));
    } finally {
      this.busy.set(false);
    }
  }

  protected async open(id: string): Promise<void> {
    try {
      this.rating.set(0);
      this.detail.set(await firstValueFrom(this.http.get<ComplaintDetail>(`/api/v1/complaints/${id}`)));
    } catch (error) {
      this.toasts.error(error);
    }
  }

  protected close(): void {
    this.detail.set(null);
    if (this.ticket()) void this.router.navigate([], { queryParams: { ticket: null }, replaceUrl: true });
  }

  protected async reply(): Promise<void> {
    const current = this.detail();
    if (!current) return;
    this.busy.set(true);
    try {
      this.detail.set(await firstValueFrom(this.http.post<ComplaintDetail>(
        `/api/v1/complaints/${current.complaint.id}/comments`, { body: this.replyText })));
      this.replyText = '';
      void this.load();
    } catch (error) {
      this.toasts.error(error);
    } finally {
      this.busy.set(false);
    }
  }

  protected async rate(): Promise<void> {
    const current = this.detail();
    if (!current) return;
    this.busy.set(true);
    try {
      this.detail.set(await firstValueFrom(this.http.post<ComplaintDetail>(
        `/api/v1/complaints/${current.complaint.id}/csat`, { rating: this.rating(), feedback: this.feedback || null })));
      this.toasts.success('Thanks for your feedback.');
      void this.load();
    } catch (error) {
      this.toasts.error(error);
    } finally {
      this.busy.set(false);
    }
  }

  private blank() {
    return { complaintType: 'BILLING', meterId: this.customer.meter()?.id ?? null, billId: null as string | null,
      subject: '', description: '' };
  }
}
