import { HttpClient } from '@angular/common/http';
import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { LucideDownload, LucidePlus } from '@lucide/angular';
import { firstValueFrom } from 'rxjs';
import { AuthStore } from '../../core/auth';
import { Page } from '../../core/models';
import { Toasts, problemMessage } from '../../core/ui-state';
import { FORMAT_PIPES, humanize } from '../../shared/format';
import { Dialog, EmptyState, ErrorBanner, Pager, StatusBadge } from '../../shared/ui';
import { ConfirmService } from '../../shared/confirm';
import { exportCsv } from '../../shared/csv';

interface AuditEntry {
  id: number;
  actorUserId: string | null;
  actorName: string | null;
  actorEmail: string | null;
  actorRole: string | null;
  action: string;
  entityType: string;
  entityId: string | null;
  correlationId: string | null;
  createdAt: string;
  afterState: unknown;
}

interface UserRow {
  id: string;
  email: string;
  fullName: string;
  mobileNumber: string;
  role: string;
  status: string;
  emailVerified: boolean;
  sessionVersion: number;
  createdAt: string;
}

@Component({
  imports: [FormsModule, Dialog, EmptyState, ErrorBanner, Pager, ...FORMAT_PIPES, LucideDownload],
  template: `
    <div class="flex flex-wrap items-end justify-between gap-3">
      <div>
        <h1 class="h-page">Audit trail</h1>
        <p class="mt-1 text-sm text-muted">Append-only record of every security, financial and administrative action. Select a row for details.</p>
      </div>
      <button type="button" class="btn-secondary btn-sm" [disabled]="!entries()?.content?.length" (click)="exportPage()">
        <svg lucideDownload [size]="15" aria-hidden="true"></svg> Export page (CSV)</button>
    </div>
    <form class="mt-5 flex flex-wrap gap-2" role="search" (ngSubmit)="load(0)">
      <label class="sr-only" for="audit-q">Filter</label>
      <input id="audit-q" class="input w-full sm:w-72" name="q" [(ngModel)]="query" placeholder="Action, entity or correlation ID" />
      <button type="submit" class="btn-secondary">Filter</button>
    </form>
    @if (error()) { <div class="mt-4"><app-error [message]="error()!" (retry)="load(page())" /></div> }
    <div class="table-wrap mt-4">
      <table class="table">
        <caption class="sr-only">Audit log</caption>
        <thead><tr><th>Time</th><th>Action</th><th>Entity</th><th>Actor</th><th>Correlation ID</th></tr></thead>
        <tbody>
          @for (entry of entries()?.content ?? []; track entry.id) {
            <tr class="cursor-pointer hover:bg-sunken" tabindex="0" (click)="selected.set(entry)" (keydown.enter)="selected.set(entry)">
              <td class="whitespace-nowrap">{{ entry.createdAt | moment }}</td>
              <td class="font-medium">{{ entry.action | humanize }}</td>
              <td>{{ entry.entityType | humanize }}<span class="block num text-xs text-faint">{{ entry.entityId }}</span></td>
              <td>{{ entry.actorName ?? ((entry.actorRole ?? 'System') | humanize) }}
                <span class="block text-xs text-faint">{{ entry.actorName ? (entry.actorRole | humanize) : entry.actorUserId }}</span></td>
              <td class="num text-xs text-muted">{{ entry.correlationId }}</td>
            </tr>
          } @empty { <tr><td colspan="5"><app-empty title="No audit entries" /></td></tr> }
        </tbody>
      </table>
    </div>
    <app-pager [page]="page()" [size]="25" [total]="entries()?.totalElements ?? 0" (pageChange)="load($event)" />

    <app-dialog [open]="!!selected()" [heading]="selected() ? label(selected()!.action) : ''"
                [subheading]="selected() ? (selected()!.createdAt | moment) : ''" (closed)="selected.set(null)">
      @if (selected(); as entry) {
        <dl class="grid gap-x-4 gap-y-2 text-sm sm:grid-cols-[9rem_1fr]">
          <dt class="text-muted">Actor</dt>
          <dd>{{ entry.actorName ?? 'System' }}@if (entry.actorEmail) { <span class="block text-xs text-faint">{{ entry.actorEmail }}</span> }</dd>
          <dt class="text-muted">Role</dt><dd>{{ (entry.actorRole ?? 'System') | humanize }}</dd>
          <dt class="text-muted">Entity</dt>
          <dd>{{ entry.entityType | humanize }}<span class="block num text-xs text-faint wrap-anywhere">{{ entry.entityId ?? '—' }}</span></dd>
          <dt class="text-muted">Correlation ID</dt><dd class="num text-xs wrap-anywhere">{{ entry.correlationId ?? '—' }}</dd>
        </dl>
        <h3 class="h-section mt-5">Recorded state</h3>
        @if (entry.afterState !== null && entry.afterState !== undefined) {
          <pre class="mt-2 max-h-80 overflow-auto rounded-md bg-sunken p-3 text-xs leading-relaxed">{{ pretty(entry.afterState) }}</pre>
        } @else {
          <p class="mt-1 text-sm text-muted">This action records no state snapshot.</p>
        }
      }
    </app-dialog>
  `,
})
export class AuditTrail {
  private readonly http = inject(HttpClient);
  protected readonly entries = signal<Page<AuditEntry> | null>(null);
  protected readonly page = signal(0);
  protected readonly error = signal<string | null>(null);
  protected readonly selected = signal<AuditEntry | null>(null);
  protected query = '';

  constructor() {
    void this.load(0);
  }

  protected label(action: string): string {
    return humanize(action);
  }

  protected pretty(state: unknown): string {
    return JSON.stringify(state, null, 2);
  }

  protected exportPage(): void {
    exportCsv(`gridiq-audit-page-${this.page() + 1}.csv`, this.entries()?.content ?? [], [
      { header: 'Time', value: (entry) => entry.createdAt },
      { header: 'Action', value: (entry) => entry.action },
      { header: 'Entity type', value: (entry) => entry.entityType },
      { header: 'Entity ID', value: (entry) => entry.entityId },
      { header: 'Actor', value: (entry) => entry.actorName ?? 'System' },
      { header: 'Actor email', value: (entry) => entry.actorEmail },
      { header: 'Actor role', value: (entry) => entry.actorRole },
      { header: 'Correlation ID', value: (entry) => entry.correlationId },
    ]);
  }

  async load(page: number): Promise<void> {
    this.page.set(page);
    this.error.set(null);
    try {
      this.entries.set(await firstValueFrom(this.http.get<Page<AuditEntry>>(
        `/api/v1/ops/audit?page=${page}&size=25&q=${encodeURIComponent(this.query.trim())}`)));
    } catch (error) {
      this.error.set(problemMessage(error, 'The audit trail could not be loaded.'));
    }
  }
}

@Component({
  imports: [FormsModule, Dialog, EmptyState, ErrorBanner, Pager, StatusBadge, ...FORMAT_PIPES, LucidePlus],
  template: `
    <div class="flex flex-wrap items-end justify-between gap-3">
      <div>
        <h1 class="h-page">Staff & access</h1>
        <p class="mt-1 text-sm text-muted">Role changes and suspensions take effect immediately and sign the user out everywhere.</p>
      </div>
      <button type="button" class="btn-primary" (click)="createOpen.set(true)"><svg lucidePlus [size]="16" aria-hidden="true"></svg> Add staff member</button>
    </div>
    <form class="mt-5 flex flex-wrap gap-2" role="search" (ngSubmit)="load(0)">
      <label class="sr-only" for="user-q">Search users</label>
      <input id="user-q" class="input w-64" name="q" [(ngModel)]="query" placeholder="Name or email" />
      <label class="sr-only" for="user-role">Role</label>
      <select id="user-role" class="input w-auto" name="role" [(ngModel)]="role">
        <option value="">Staff and customers</option>
        @for (option of roles; track option) { <option [value]="option">{{ option | humanize }}</option> }
        <option value="CUSTOMER">Customer</option>
      </select>
      <button type="submit" class="btn-secondary">Search</button>
    </form>
    @if (error()) { <div class="mt-4"><app-error [message]="error()!" (retry)="load(page())" /></div> }
    <div class="table-wrap mt-4">
      <table class="table">
        <caption class="sr-only">Users</caption>
        <thead><tr><th>Name</th><th>Email</th><th>Role</th><th>Status</th><th>Joined</th></tr></thead>
        <tbody>
          @for (user of users()?.content ?? []; track user.id) {
            <tr>
              <td class="font-medium">{{ user.fullName }}</td>
              <td class="text-muted">{{ user.email }}</td>
              <td>
                @if (user.role === 'CUSTOMER' || user.id === auth.user()?.id) { {{ user.role | humanize }} }
                @else {
                  <label class="sr-only" [for]="'role-' + user.id">Role for {{ user.fullName }}</label>
                  <select class="input w-auto" [id]="'role-' + user.id" [ngModel]="user.role" (ngModelChange)="change(user, { role: $event })">
                    @for (option of roles; track option) { <option [value]="option">{{ option | humanize }}</option> }
                  </select>
                }
              </td>
              <td>
                @if (user.id === auth.user()?.id) { <app-badge [status]="user.status" /> }
                @else {
                  <button type="button" class="btn-ghost btn-sm" (click)="change(user, { status: user.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE' })">
                    <app-badge [status]="user.status" /> <span class="text-xs">{{ user.status === 'ACTIVE' ? 'Disable' : 'Enable' }}</span>
                  </button>
                }
              </td>
              <td>{{ user.createdAt | day }}</td>
            </tr>
          } @empty { <tr><td colspan="5"><app-empty title="No users match" /></td></tr> }
        </tbody>
      </table>
    </div>
    <app-pager [page]="page()" [size]="20" [total]="users()?.totalElements ?? 0" (pageChange)="load($event)" />

    <app-dialog [open]="createOpen()" heading="Add staff member" subheading="Share the temporary password through a secure channel." (closed)="createOpen.set(false)">
      <form id="staff-form" class="grid gap-3" (ngSubmit)="create()">
        <div><label class="label" for="s-name">Full name</label><input id="s-name" class="input" name="fullName" [(ngModel)]="draft.fullName" required /></div>
        <div><label class="label" for="s-email">Email</label><input id="s-email" class="input" type="email" name="email" [(ngModel)]="draft.email" required /></div>
        <div><label class="label" for="s-mobile">Mobile</label><input id="s-mobile" class="input num" name="mobile" [(ngModel)]="draft.mobileNumber" required /></div>
        <div><label class="label" for="s-role">Role</label>
          <select id="s-role" class="input" name="role" [(ngModel)]="draft.role">@for (option of roles; track option) { <option [value]="option">{{ option | humanize }}</option> }</select></div>
        <div><label class="label" for="s-password">Temporary password</label><input id="s-password" class="input" type="password" name="password" [(ngModel)]="draft.temporaryPassword" minlength="12" autocomplete="new-password" required /></div>
        @if (formError()) { <p role="alert" class="rounded-lg bg-bad-soft p-3 text-sm">{{ formError() }}</p> }
      </form>
      <div dialog-footer class="empty:hidden flex justify-end gap-2 border-t border-line p-4">
        <button type="button" class="btn-secondary" (click)="createOpen.set(false)">Cancel</button>
        <button type="submit" form="staff-form" class="btn-primary" [disabled]="busy()">Create</button>
      </div>
    </app-dialog>
  `,
})
export class StaffAccess {
  protected readonly auth = inject(AuthStore);
  private readonly http = inject(HttpClient);
  private readonly toasts = inject(Toasts);
  private readonly confirm = inject(ConfirmService);
  protected readonly roles = ['BILLING_OFFICER', 'SUPPORT_AGENT', 'FIELD_ENGINEER', 'SUPER_ADMIN'];
  protected readonly users = signal<Page<UserRow> | null>(null);
  protected readonly page = signal(0);
  protected readonly error = signal<string | null>(null);
  protected readonly createOpen = signal(false);
  protected readonly busy = signal(false);
  protected readonly formError = signal<string | null>(null);
  protected query = '';
  protected role = '';
  protected draft = { fullName: '', email: '', mobileNumber: '', role: 'SUPPORT_AGENT', temporaryPassword: '' };

  constructor() {
    void this.load(0);
  }

  async load(page: number): Promise<void> {
    this.page.set(page);
    this.error.set(null);
    const params = new URLSearchParams({ page: String(page), size: '20', q: this.query.trim() });
    if (this.role) params.set('role', this.role);
    try {
      this.users.set(await firstValueFrom(this.http.get<Page<UserRow>>(`/api/v1/ops/users?${params}`)));
    } catch (error) {
      this.error.set(problemMessage(error, 'Users could not be loaded.'));
    }
  }

  protected async change(user: UserRow, change: { role?: string; status?: string }): Promise<void> {
    const disabling = change.status === 'DISABLED';
    const confirmed = await this.confirm.ask(change.role
      ? { title: `Change ${user.fullName}'s role?`,
          message: `${user.fullName} becomes ${humanize(change.role)} and is signed out of every session. The change is audited.`,
          confirmLabel: 'Change role' }
      : { title: `${disabling ? 'Disable' : 'Enable'} ${user.fullName}?`,
          message: disabling ? `${user.fullName} is signed out everywhere and can no longer sign in. The change is audited.`
            : `${user.fullName} can sign in again. The change is audited.`,
          confirmLabel: disabling ? 'Disable account' : 'Enable account', tone: disabling ? 'danger' : 'default' });
    if (!confirmed) {
      await this.load(this.page()); // resets the role select to the saved value
      return;
    }
    try {
      await firstValueFrom(this.http.patch(`/api/v1/ops/users/${user.id}`, { ...change, sessionVersion: user.sessionVersion }));
      this.toasts.success(`${user.fullName} updated and signed out of existing sessions.`);
    } catch (error) {
      this.toasts.error(error);
    }
    await this.load(this.page());
  }

  protected async create(): Promise<void> {
    this.busy.set(true);
    try {
      await firstValueFrom(this.http.post('/api/v1/ops/users', this.draft));
      this.createOpen.set(false);
      this.toasts.success(`${this.draft.fullName} can now sign in.`);
      this.draft = { fullName: '', email: '', mobileNumber: '', role: 'SUPPORT_AGENT', temporaryPassword: '' };
      await this.load(0);
    } catch (error) {
      this.formError.set(problemMessage(error));
    } finally {
      this.busy.set(false);
    }
  }
}
