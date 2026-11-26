import { HttpClient } from '@angular/common/http';
import { Component, computed, effect, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { LucidePlus, LucideSparkles } from '@lucide/angular';
import { firstValueFrom } from 'rxjs';
import { AuthStore } from '../../core/auth';
import { Complaint, ComplaintDetail, Outage, Page, StaffMember } from '../../core/models';
import { Toasts, problemMessage } from '../../core/ui-state';
import { FORMAT_PIPES, humanize } from '../../shared/format';
import { Dialog, EmptyState, ErrorBanner, Pager, StatusBadge } from '../../shared/ui';
import { slaText } from '../customer/support';

interface Triage {
  summary: string;
  likelyRootCause: string;
  suggestedPriority: string;
  suggestedResponse: string;
  checks: string[];
  evidence: string[];
  providerUsed: string;
}

const NEXT: Record<string, string[]> = {
  OPEN: ['IN_PROGRESS', 'RESOLVED'], ASSIGNED: ['IN_PROGRESS', 'RESOLVED'], IN_PROGRESS: ['RESOLVED'], RESOLVED: ['CLOSED'], CLOSED: [],
};

@Component({
  imports: [FormsModule, Dialog, EmptyState, ErrorBanner, Pager, StatusBadge, ...FORMAT_PIPES, LucideSparkles, LucidePlus],
  template: `
    <div class="flex flex-wrap items-end justify-between gap-3">
      <div>
        <h1 class="h-page">Support desk & outages</h1>
        <p class="mt-1 text-sm text-muted">Queue ordered by priority and SLA deadline.</p>
      </div>
      <div class="tabs" role="tablist" aria-label="Desk views">
        <button type="button" role="tab" class="tab" [attr.aria-selected]="tab() === 'queue'" (click)="tab.set('queue')">Tickets</button>
        <button type="button" role="tab" class="tab" [attr.aria-selected]="tab() === 'outages'" (click)="tab.set('outages'); loadOutages()">Outages</button>
      </div>
    </div>

    @if (tab() === 'queue') {
      <form class="mt-5 flex flex-wrap gap-2" role="search" (ngSubmit)="page.set(0); load()">
        <label class="sr-only" for="desk-q">Search</label>
        <input id="desk-q" class="input w-56" name="q" [(ngModel)]="query" placeholder="Ticket or subject" />
        <label class="sr-only" for="desk-status">Status</label>
        <select id="desk-status" class="input w-auto" name="status" [(ngModel)]="status">
          <option value="">Any status</option>
          @for (option of statuses; track option) { <option [value]="option">{{ option | humanize }}</option> }
        </select>
        <label class="sr-only" for="desk-priority">Priority</label>
        <select id="desk-priority" class="input w-auto" name="priority" [(ngModel)]="priority">
          <option value="">Any priority</option><option value="CRITICAL">Critical</option><option value="HIGH">High</option><option value="MEDIUM">Medium</option><option value="LOW">Low</option>
        </select>
        <label class="flex items-center gap-2 text-sm"><input type="checkbox" class="size-4 accent-[var(--brand)]" name="breached" [(ngModel)]="breached" /> SLA breached</label>
        <label class="flex items-center gap-2 text-sm"><input type="checkbox" class="size-4 accent-[var(--brand)]" name="mine" [(ngModel)]="mine" /> Assigned to me</label>
        <button type="submit" class="btn-secondary">Apply</button>
      </form>
      @if (error()) { <div class="mt-4"><app-error [message]="error()!" (retry)="load()" /></div> }
      <div class="table-wrap mt-4">
        <table class="table">
          <caption class="sr-only">Complaint queue</caption>
          <thead><tr><th>Ticket</th><th>Subject</th><th>Type</th><th>Priority</th><th>SLA</th><th>Status</th></tr></thead>
          <tbody>
            @for (item of queue()?.content ?? []; track item.id) {
              <tr class="clickable" (click)="open(item.id)">
                <td><button type="button" class="num font-medium text-brand hover:underline" (click)="open(item.id); $event.stopPropagation()">{{ item.ticketNumber }}</button></td>
                <td class="max-w-72 truncate">{{ item.subject }}</td>
                <td>{{ item.complaintType | humanize }}</td>
                <td><app-badge [status]="item.priority" /></td>
                <td><span class="badge" [class]="sla(item).tone">{{ sla(item).text }}</span></td>
                <td><app-badge [status]="item.status" /></td>
              </tr>
            } @empty { <tr><td colspan="6"><app-empty title="No tickets match" /></td></tr> }
          </tbody>
        </table>
      </div>
      <app-pager [page]="page()" [size]="20" [total]="queue()?.totalElements ?? 0" (pageChange)="page.set($event); load()" />
    } @else {
      @if (canManage()) {
        <form class="card-pad mt-5 grid gap-3 md:grid-cols-6" (ngSubmit)="declare()">
          <p class="font-semibold md:col-span-6">Declare an outage</p>
          <div class="md:col-span-2"><label class="label" for="o-title">Title</label><input id="o-title" class="input" name="title" [(ngModel)]="outageDraft.title" required /></div>
          <div><label class="label" for="o-city">City</label><input id="o-city" class="input" name="city" [(ngModel)]="outageDraft.city" required /></div>
          <div><label class="label" for="o-pins">Pincodes</label><input id="o-pins" class="input num" name="pins" [(ngModel)]="outageDraft.pincodes" placeholder="411001, 411002" required /></div>
          <div><label class="label" for="o-sev">Severity</label>
            <select id="o-sev" class="input" name="severity" [(ngModel)]="outageDraft.severity"><option value="UNPLANNED">Unplanned</option><option value="PLANNED">Planned</option><option value="EMERGENCY">Emergency</option></select></div>
          <div><label class="label" for="o-eta">Restoration ETA</label><input id="o-eta" class="input" type="datetime-local" name="eta" [(ngModel)]="outageDraft.eta" /></div>
          <div class="md:col-span-5"><label class="label" for="o-desc">Description</label><input id="o-desc" class="input" name="description" [(ngModel)]="outageDraft.description" required /></div>
          <div class="flex items-end"><button type="submit" class="btn-primary w-full" [disabled]="busy()"><svg lucidePlus [size]="15" aria-hidden="true"></svg> Declare</button></div>
        </form>
      }
      <div class="mt-4 space-y-3">
        @for (outage of outages(); track outage.id) {
          <article class="card-pad">
            <div class="flex flex-wrap items-center gap-2">
              <p class="font-semibold">{{ outage.title }}</p><app-badge [status]="outage.severity" /><app-badge [status]="outage.status" />
              <span class="ml-auto text-xs text-faint">{{ outage.incidentCode }}</span>
            </div>
            <p class="mt-1 text-sm text-muted">{{ outage.city }} · {{ outage.affectedPincodes.join(', ') }} · started {{ outage.startsAt | moment }}
              @if (outage.estimatedRestorationAt) { · ETA {{ outage.estimatedRestorationAt | moment }} }</p>
            <p class="mt-2 text-sm">{{ outage.description }}</p>
            @if (canManage() && outage.status !== 'RESOLVED') {
              <div class="mt-3 flex flex-wrap gap-2">
                @for (next of ['CREW_DISPATCHED', 'RESTORING', 'RESOLVED']; track next) {
                  @if (next !== outage.status) {
                    <button type="button" class="btn-secondary btn-sm" [disabled]="busy()" (click)="updateOutage(outage, next)">{{ next | humanize }}</button>
                  }
                }
              </div>
            }
          </article>
        } @empty { <div class="card"><app-empty title="No active outages" /></div> }
      </div>
    }

    <app-dialog [open]="!!detail()" [drawer]="true" [heading]="detail()?.complaint?.subject ?? ''"
                [subheading]="detail() ? detail()!.complaint.ticketNumber + ' · ' + humanize(detail()!.complaint.complaintType) : ''" (closed)="close()">
      @if (detail(); as d) {
        <div class="space-y-5 text-sm">
          <div class="flex flex-wrap gap-2">
            <app-badge [status]="d.complaint.status" /><app-badge [status]="d.complaint.priority" />
            <span class="badge" [class]="sla(d.complaint).tone">{{ sla(d.complaint).text }}</span>
          </div>
          <p class="whitespace-pre-line">{{ d.complaint.description }}</p>
          <p class="text-xs text-faint">Contact {{ d.complaint.contactNumber }} · opened {{ d.complaint.createdAt | moment }}</p>

          <section class="rounded-xl border border-brand/30 bg-brand-soft p-4">
            <div class="flex items-center justify-between gap-2">
              <p class="flex items-center gap-2 font-semibold text-brand-strong"><svg lucideSparkles [size]="16" aria-hidden="true"></svg> AI triage</p>
              <button type="button" class="btn-secondary btn-sm" [disabled]="triaging()" (click)="triage(d.complaint.id)">{{ triage$() ? 'Refresh' : 'Analyse' }}</button>
            </div>
            @if (triaging()) { <div class="skeleton mt-3 h-20"></div> }
            @if (triage$(); as t) {
              <p class="mt-3"><span class="font-medium">Likely cause:</span> {{ t.likelyRootCause }}</p>
              @if (t.evidence.length) { <ul class="mt-2 list-disc pl-5 text-muted">@for (line of t.evidence; track $index) { <li>{{ line }}</li> }</ul> }
              @if (t.checks.length) { <p class="mt-2 font-medium">Next steps</p><ul class="list-disc pl-5 text-muted">@for (line of t.checks; track $index) { <li>{{ line }}</li> }</ul> }
              <div class="mt-3 rounded-lg bg-surface p-3"><p class="text-xs text-faint">Suggested reply · priority {{ t.suggestedPriority | humanize }}</p><p class="mt-1">{{ t.suggestedResponse }}</p></div>
              <button type="button" class="btn-ghost btn-sm mt-2" (click)="reply = t.suggestedResponse; internal = false">Use as reply</button>
            }
          </section>

          @if (canManage()) {
            <section class="grid gap-3 rounded-xl border border-line p-4 sm:grid-cols-2">
              <div><label class="label" for="assignee">Assigned to</label>
                <select id="assignee" class="input" [ngModel]="d.complaint.assignedAgentId" (ngModelChange)="assign(d, $event)">
                  <option [ngValue]="null">Unassigned</option>
                  @for (member of staff(); track member.id) { <option [ngValue]="member.id">{{ member.fullName }} · {{ member.role | humanize }}</option> }
                </select></div>
              <div><label class="label" for="priority">Priority</label>
                <select id="priority" class="input" [ngModel]="d.complaint.priority" (ngModelChange)="update(d, { priority: $event })">
                  <option value="CRITICAL">Critical</option><option value="HIGH">High</option><option value="MEDIUM">Medium</option><option value="LOW">Low</option>
                </select></div>
              @if (nextStatuses(d.complaint.status).includes('RESOLVED')) {
                <div class="sm:col-span-2"><label class="label" for="resolution">Resolution summary</label>
                  <textarea id="resolution" class="input min-h-16 py-2" [(ngModel)]="resolution" maxlength="2000"></textarea></div>
              }
              <div class="flex flex-wrap gap-2 sm:col-span-2">
                @for (next of nextStatuses(d.complaint.status); track next) {
                  <button type="button" class="btn-sm" [class]="next === 'RESOLVED' ? 'btn-primary' : 'btn-secondary'" [disabled]="busy() || (next === 'RESOLVED' && !resolution.trim())"
                          (click)="update(d, next === 'RESOLVED' ? { status: next, resolutionSummary: resolution } : { status: next })">Mark {{ next | humanize }}</button>
                }
              </div>
            </section>
          }

          <section>
            <h3 class="h-section">Conversation</h3>
            <ul class="mt-3 space-y-3">
              @for (comment of d.comments; track comment.id) {
                <li class="rounded-lg p-3" [class]="comment.isInternal ? 'border border-dashed border-warn/50 bg-warn-soft' : comment.authorRole === 'CUSTOMER' ? 'bg-sunken' : 'bg-brand-soft'">
                  <p class="text-xs font-semibold">{{ comment.authorName }} · {{ comment.authorRole | humanize }}{{ comment.isInternal ? ' · internal note' : '' }}
                    <span class="font-normal text-faint"> · {{ comment.createdAt | moment }}</span></p>
                  <p class="mt-1 whitespace-pre-line">{{ comment.body }}</p>
                </li>
              } @empty { <li class="text-muted">No messages yet.</li> }
            </ul>
          </section>
        </div>
      }
      <div dialog-footer class="border-t border-line p-4">
        @if (detail() && detail()!.complaint.status !== 'CLOSED' && canManage()) {
          <form class="space-y-2" (ngSubmit)="comment()">
            <label class="sr-only" for="desk-reply">Message</label>
            <textarea id="desk-reply" class="input min-h-16 py-2" name="reply" [(ngModel)]="reply" maxlength="4000" placeholder="Reply to the customer or add an internal note"></textarea>
            <div class="flex items-center justify-between gap-2">
              <label class="flex items-center gap-2 text-sm"><input type="checkbox" class="size-4 accent-[var(--brand)]" name="internal" [(ngModel)]="internal" /> Internal note</label>
              <button type="submit" class="btn-primary" [disabled]="busy() || !reply.trim()">{{ internal ? 'Add note' : 'Send reply' }}</button>
            </div>
          </form>
        }
      </div>
    </app-dialog>
  `,
})
export class SupportDesk {
  readonly ticket = input<string>();
  private readonly http = inject(HttpClient);
  private readonly toasts = inject(Toasts);
  private readonly auth = inject(AuthStore);
  protected readonly humanize = humanize;
  protected readonly statuses = ['OPEN', 'ASSIGNED', 'IN_PROGRESS', 'RESOLVED', 'CLOSED'];
  protected readonly canManage = computed(() => ['SUPPORT_AGENT', 'FIELD_ENGINEER', 'SUPER_ADMIN'].includes(this.auth.role() ?? ''));
  protected readonly tab = signal<'queue' | 'outages'>('queue');
  protected readonly page = signal(0);
  protected readonly queue = signal<Page<Complaint> | null>(null);
  protected readonly outages = signal<Outage[]>([]);
  protected readonly staff = signal<StaffMember[]>([]);
  protected readonly detail = signal<ComplaintDetail | null>(null);
  protected readonly triage$ = signal<Triage | null>(null);
  protected readonly triaging = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected query = '';
  protected status = '';
  protected priority = '';
  protected breached = false;
  protected mine = false;
  protected reply = '';
  protected internal = false;
  protected resolution = '';
  protected outageDraft = { title: '', city: '', pincodes: '', severity: 'UNPLANNED', eta: '', description: '' };

  constructor() {
    void this.load();
    if (this.canManage()) {
      firstValueFrom(this.http.get<StaffMember[]>('/api/v1/ops/staff')).then((staff) => this.staff.set(staff)).catch(() => undefined);
    }
    effect(() => {
      const id = this.ticket();
      if (id) void this.open(id);
    });
  }

  protected sla = slaText;

  protected nextStatuses(status: string): string[] {
    return NEXT[status] ?? [];
  }

  async load(): Promise<void> {
    this.error.set(null);
    const params = new URLSearchParams({ page: String(this.page()), size: '20', q: this.query.trim() });
    if (this.status) params.set('status', this.status);
    if (this.priority) params.set('priority', this.priority);
    if (this.breached) params.set('slaBreached', 'true');
    if (this.mine && this.auth.user()) params.set('assignedTo', this.auth.user()!.id);
    try {
      this.queue.set(await firstValueFrom(this.http.get<Page<Complaint>>(`/api/v1/ops/complaints?${params}`)));
    } catch (error) {
      this.error.set(problemMessage(error, 'The queue could not be loaded.'));
    }
  }

  protected async loadOutages(): Promise<void> {
    this.outages.set(await firstValueFrom(this.http.get<Outage[]>('/api/v1/ops/outages')).catch(() => []));
  }

  protected async open(id: string): Promise<void> {
    try {
      this.triage$.set(null);
      this.reply = '';
      this.internal = false;
      const detail = await firstValueFrom(this.http.get<ComplaintDetail>(`/api/v1/ops/complaints/${id}`));
      this.resolution = detail.complaint.resolutionSummary ?? '';
      this.detail.set(detail);
    } catch (error) {
      this.toasts.error(error);
    }
  }

  protected close(): void {
    this.detail.set(null);
  }

  protected async triage(id: string): Promise<void> {
    this.triaging.set(true);
    try {
      this.triage$.set(await firstValueFrom(this.http.post<Triage>(`/api/v1/ops/ai/complaints/${id}/triage`, null)));
    } catch (error) {
      this.toasts.error(error, 'Triage is unavailable right now.');
    } finally {
      this.triaging.set(false);
    }
  }

  protected assign(detail: ComplaintDetail, agentId: string | null): void {
    void this.update(detail, agentId ? { assignedAgentId: agentId } : { unassign: true });
  }

  protected async update(detail: ComplaintDetail, change: Record<string, unknown>): Promise<void> {
    this.busy.set(true);
    try {
      const updated = await firstValueFrom(this.http.patch<ComplaintDetail>(`/api/v1/ops/complaints/${detail.complaint.id}`,
        { ...change, version: detail.complaint.version }));
      this.detail.set(updated);
      this.toasts.success('Ticket updated.');
      void this.load();
    } catch (error) {
      this.toasts.error(error);
      await this.open(detail.complaint.id);
    } finally {
      this.busy.set(false);
    }
  }

  protected async comment(): Promise<void> {
    const current = this.detail();
    if (!current) return;
    this.busy.set(true);
    try {
      this.detail.set(await firstValueFrom(this.http.post<ComplaintDetail>(`/api/v1/ops/complaints/${current.complaint.id}/comments`,
        { body: this.reply, internal: this.internal })));
      this.reply = '';
    } catch (error) {
      this.toasts.error(error);
    } finally {
      this.busy.set(false);
    }
  }

  protected async declare(): Promise<void> {
    const d = this.outageDraft;
    this.busy.set(true);
    try {
      await firstValueFrom(this.http.post('/api/v1/ops/outages', {
        title: d.title, city: d.city, regionZone: d.city, description: d.description, severity: d.severity,
        affectedPincodes: d.pincodes.split(/[\s,]+/).filter(Boolean),
        estimatedRestorationAt: d.eta ? new Date(d.eta).toISOString() : null,
      }));
      this.toasts.success('Outage declared. Affected customers are notified.');
      this.outageDraft = { title: '', city: '', pincodes: '', severity: 'UNPLANNED', eta: '', description: '' };
      await this.loadOutages();
    } catch (error) {
      this.toasts.error(error);
    } finally {
      this.busy.set(false);
    }
  }

  protected async updateOutage(outage: Outage, status: string): Promise<void> {
    this.busy.set(true);
    try {
      await firstValueFrom(this.http.patch(`/api/v1/ops/outages/${outage.id}`, { status, version: outage.version }));
      this.toasts.success(status === 'RESOLVED' ? 'Outage resolved. Customers are notified.' : 'Outage updated.');
      await this.loadOutages();
    } catch (error) {
      this.toasts.error(error);
    } finally {
      this.busy.set(false);
    }
  }
}
