import { HttpClient } from '@angular/common/http';
import { Component, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { Anomaly } from '../../core/models';
import { Toasts, problemMessage } from '../../core/ui-state';
import { FORMAT_PIPES } from '../../shared/format';
import { EmptyState, ErrorBanner, StatusBadge } from '../../shared/ui';

const OUTCOMES = [
  { value: 'INVESTIGATING', label: 'Start investigation' },
  { value: 'CONFIRMED_FAULT', label: 'Confirm meter fault' },
  { value: 'CONFIRMED_TAMPER', label: 'Confirm tamper' },
  { value: 'FALSE_POSITIVE', label: 'False positive' },
];

@Component({
  imports: [FormsModule, EmptyState, ErrorBanner, StatusBadge, ...FORMAT_PIPES],
  template: `
    <div class="flex flex-wrap items-end justify-between gap-3">
      <div>
        <h1 class="h-page">VEE & anomalies</h1>
        <p class="mt-1 text-sm text-muted">Data-quality exceptions and ML findings, each with its reasons. Nothing is billed from a flagged interval.</p>
      </div>
      <div class="tabs" role="tablist" aria-label="Status">
        @for (option of filters; track option.value) {
          <button type="button" role="tab" class="tab" [attr.aria-selected]="status() === option.value" (click)="status.set(option.value); load()">{{ option.label }}</button>
        }
      </div>
    </div>
    @if (error()) { <div class="mt-4"><app-error [message]="error()!" (retry)="load()" /></div> }
    <div class="mt-5 grid gap-4 lg:grid-cols-2">
      @for (item of items(); track item.id) {
        <article class="card-pad" [class.ring-2]="item.id === anomaly()" [class.ring-brand]="item.id === anomaly()">
          <div class="flex flex-wrap items-center gap-2">
            <p class="font-semibold">{{ item.anomalyType | humanize }}</p>
            <app-badge [status]="item.severity" />
            <app-badge [status]="item.status" class="ml-auto" />
          </div>
          <p class="mt-1 text-sm text-muted"><span class="num">{{ item.meterSerial }}</span> · {{ item.windowStart | moment }} → {{ item.windowEnd | moment }}</p>
          <div class="mt-3 flex items-center gap-3">
            <span class="h-2 flex-1 overflow-hidden rounded-full bg-sunken" role="meter" [attr.aria-valuenow]="item.score" aria-valuemin="0" aria-valuemax="100" aria-label="Anomaly score">
              <span class="block h-full rounded-full" [class]="item.score >= 85 ? 'bg-bad' : item.score >= 65 ? 'bg-warn' : 'bg-cyan'" [style.width.%]="item.score"></span>
            </span>
            <span class="num text-sm font-semibold">{{ item.score }}</span>
          </div>
          <ul class="mt-3 space-y-1.5 text-sm">
            @for (reason of reasons(item); track $index) {
              <li><span class="badge bg-sunken text-muted">{{ reason.code | humanize }}</span> {{ reason.detail }}</li>
            }
          </ul>
          @if (item.resolutionNotes) { <p class="mt-3 rounded-lg bg-sunken p-2 text-sm">{{ item.resolutionNotes }}</p> }
          @if (item.status === 'OPEN' || item.status === 'INVESTIGATING') {
            <div class="mt-4 space-y-2 border-t border-line pt-3">
              <label class="sr-only" [for]="'notes-' + item.id">Investigation notes</label>
              <input class="input" [id]="'notes-' + item.id" [(ngModel)]="notes[item.id]" placeholder="Notes for the record (optional)" />
              <div class="flex flex-wrap gap-2">
                @for (outcome of outcomes; track outcome.value) {
                  @if (outcome.value !== item.status) {
                    <button type="button" class="btn-sm" [class]="outcome.value === 'CONFIRMED_TAMPER' ? 'btn-danger' : 'btn-secondary'"
                            [disabled]="busy()" (click)="resolve(item, outcome.value)">{{ outcome.label }}</button>
                  }
                }
              </div>
            </div>
          }
        </article>
      } @empty {
        <div class="card lg:col-span-2"><app-empty title="Queue is clear" message="No anomalies in this state." /></div>
      }
    </div>
  `,
})
export class Anomalies {
  readonly anomaly = input<string>();
  private readonly http = inject(HttpClient);
  private readonly toasts = inject(Toasts);
  protected readonly outcomes = OUTCOMES;
  protected readonly filters = [
    { value: 'OPEN', label: 'Open' }, { value: 'INVESTIGATING', label: 'Investigating' }, { value: '', label: 'All' },
  ];
  protected readonly status = signal('OPEN');
  protected readonly items = signal<Anomaly[]>([]);
  protected readonly error = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected notes: Record<string, string> = {};

  constructor() {
    void this.load();
  }

  protected reasons(item: Anomaly): { code: string; detail: string }[] {
    try {
      const parsed = JSON.parse(item.reasonCodes);
      return Array.isArray(parsed) ? parsed.map((entry) => typeof entry === 'string' ? { code: entry, detail: '' } : entry) : [];
    } catch {
      return [];
    }
  }

  async load(): Promise<void> {
    this.error.set(null);
    try {
      const query = this.status() ? `?status=${this.status()}&size=100` : '?size=100';
      this.items.set(await firstValueFrom(this.http.get<Anomaly[]>(`/api/v1/ops/anomalies${query}`)));
    } catch (error) {
      this.error.set(problemMessage(error, 'The anomaly queue could not be loaded.'));
    }
  }

  protected async resolve(item: Anomaly, status: string): Promise<void> {
    this.busy.set(true);
    try {
      await firstValueFrom(this.http.patch(`/api/v1/ops/anomalies/${item.id}`, { status, notes: this.notes[item.id] || null }));
      this.toasts.success(`Marked ${item.meterSerial} as ${status.replaceAll('_', ' ').toLowerCase()}.`);
      await this.load();
    } catch (error) {
      this.toasts.error(error);
    } finally {
      this.busy.set(false);
    }
  }
}
