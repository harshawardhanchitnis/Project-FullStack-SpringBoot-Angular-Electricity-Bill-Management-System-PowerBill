import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Component, computed, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { LucideUpload } from '@lucide/angular';
import { firstValueFrom } from 'rxjs';
import { Anomaly, Meter } from '../../core/models';
import { Toasts, problemMessage } from '../../core/ui-state';
import { FORMAT_PIPES } from '../../shared/format';
import { Dialog, EmptyState, ErrorBanner, Pager, StatusBadge } from '../../shared/ui';

const OUTCOMES = [
  { value: 'INVESTIGATING', label: 'Start investigation' },
  { value: 'CONFIRMED_FAULT', label: 'Confirm meter fault' },
  { value: 'CONFIRMED_TAMPER', label: 'Confirm tamper' },
  { value: 'FALSE_POSITIVE', label: 'False positive' },
];
const QUEUE_PAGE = 10;

interface Reading {
  start: string;
  importKwh: number;
  exportKwh: number;
  voltage: number | null;
  powerFactor: number | null;
  source: string;
  quality: string;
}

interface Intervals {
  meterId: string;
  meterSerial: string;
  readings: Reading[];
  missing: string[];
  flaggedCount: number;
}

interface Row {
  start: string;
  reading: Reading | null;
}

/** IST midnight N days ago as an ISO-8601 offset timestamp. */
function istDay(offset: number): string {
  const now = new Date(Date.now() + 5.5 * 3600 * 1000);
  const day = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate() + offset));
  return `${day.toISOString().slice(0, 10)}T00:00:00+05:30`;
}

@Component({
  imports: [FormsModule, Dialog, EmptyState, ErrorBanner, Pager, StatusBadge, LucideUpload, ...FORMAT_PIPES],
  template: `
    <div class="flex flex-wrap items-end justify-between gap-3">
      <div>
        <h1 class="h-page">VEE & anomalies</h1>
        <p class="mt-1 text-sm text-muted">Data-quality exceptions and ML findings, each with its reasons. Nothing is billed from a flagged interval.</p>
      </div>
      <div class="tabs" role="tablist" aria-label="View">
        <button type="button" role="tab" class="tab" [attr.aria-selected]="view() === 'queue'" (click)="view.set('queue')">Anomaly queue</button>
        <button type="button" role="tab" class="tab" [attr.aria-selected]="view() === 'vee'" (click)="view.set('vee')">VEE & telemetry</button>
      </div>
    </div>

    @if (view() === 'queue') {
      <div class="mt-5 flex flex-wrap items-center justify-between gap-2">
        <div class="tabs" role="tablist" aria-label="Status">
          @for (option of filters; track option.value) {
            <button type="button" role="tab" class="tab" [attr.aria-selected]="status() === option.value" (click)="setStatus(option.value)">{{ option.label }}</button>
          }
        </div>
        <p class="text-sm text-muted">{{ items().length }} {{ items().length === 1 ? 'finding' : 'findings' }}</p>
      </div>
      @if (error()) { <div class="mt-4"><app-error [message]="error()!" (retry)="load()" /></div> }
      <div class="mt-4 grid gap-4 lg:grid-cols-2">
        @for (item of pageItems(); track item.id) {
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
                  <button type="button" class="btn-ghost btn-sm" (click)="inspect(item)">Inspect intervals</button>
                </div>
              </div>
            }
          </article>
        } @empty {
          <div class="card lg:col-span-2"><app-empty title="Queue is clear" message="No anomalies in this state." /></div>
        }
      </div>
      <app-pager [page]="page()" [size]="queuePage" [total]="items().length" (pageChange)="page.set($event)" />
    } @else {
      <section class="card-pad mt-5" aria-labelledby="meter-heading">
        <h2 id="meter-heading" class="h-section">Meter</h2>
        <form class="mt-3 flex flex-wrap gap-2" role="search" (ngSubmit)="findMeters()">
          <label class="sr-only" for="meter-search">Meter serial</label>
          <input id="meter-search" class="input max-w-xs" name="meterQuery" [(ngModel)]="meterQuery" placeholder="Meter serial, e.g. M-DEMO-04" minlength="2" required />
          <button type="submit" class="btn-secondary" [disabled]="meterQuery.trim().length < 2">Find meter</button>
        </form>
        @if (meters().length) {
          <ul class="mt-3 flex flex-wrap gap-2" aria-label="Matching meters">
            @for (meter of meters(); track meter.id) {
              <li><button type="button" class="btn-sm" [class]="meter.id === selected()?.id ? 'btn-primary' : 'btn-secondary'" (click)="choose(meter)">
                <span class="num">{{ meter.meterSerial }}</span> · {{ meter.connectionType | humanize }}</button></li>
            }
          </ul>
        }
      </section>

      @if (selected(); as meter) {
        <div class="mt-4 grid gap-4 lg:grid-cols-3">
          <section class="card-pad lg:col-span-2" aria-labelledby="interval-heading">
            <div class="flex flex-wrap items-end gap-3">
              <h2 id="interval-heading" class="h-section mr-auto">Intervals · <span class="num">{{ meter.meterSerial }}</span></h2>
              <div><label class="label" for="vee-window">Window</label>
                <select id="vee-window" class="input w-auto" [ngModel]="days()" (ngModelChange)="days.set(+$event); loadIntervals()">
                  <option [ngValue]="1">Yesterday</option><option [ngValue]="3">Last 3 days</option><option [ngValue]="7">Last 7 days</option>
                </select></div>
              <label class="flex items-center gap-2 text-sm"><input type="checkbox" class="size-4 accent-[var(--brand)]" [ngModel]="showAll()" (ngModelChange)="showAll.set($event)" /> All hours</label>
            </div>
            @if (intervals(); as data) {
              <p class="mt-2 text-sm text-muted">{{ data.readings.length }} readings · {{ data.flaggedCount }} flagged · {{ data.missing.length }} missing</p>
              <div class="mt-3 max-h-[28rem] overflow-auto rounded-lg border border-line">
                <table class="table">
                  <caption class="sr-only">Hourly intervals</caption>
                  <thead><tr><th>Hour (IST)</th><th class="text-right">Import kWh</th><th class="text-right">Export kWh</th><th class="text-right">Voltage</th><th>Quality</th><th><span class="sr-only">Action</span></th></tr></thead>
                  <tbody>
                    @for (row of rows(); track row.start) {
                      <tr>
                        <td class="num">{{ row.start | moment }}</td>
                        @if (row.reading; as reading) {
                          <td class="num text-right">{{ reading.importKwh }}</td><td class="num text-right">{{ reading.exportKwh }}</td>
                          <td class="num text-right">{{ reading.voltage ?? '—' }}</td>
                          <td><app-badge [status]="reading.quality" /></td>
                          <td class="text-right">@if (reading.quality === 'FLAGGED') { <button type="button" class="btn-ghost btn-sm" (click)="startRevision(reading)">Revise</button> }</td>
                        } @else {
                          <td colspan="3" class="text-muted">No reading</td>
                          <td><span class="badge bg-warn-soft text-warn">Missing</span></td>
                          <td class="text-right"><button type="button" class="btn-ghost btn-sm" [disabled]="busy()" (click)="estimate(row.start)">Estimate</button></td>
                        }
                      </tr>
                    } @empty {
                      <tr><td colspan="6"><app-empty title="No exceptions" message="Every hour in this window has a valid reading." /></td></tr>
                    }
                  </tbody>
                </table>
              </div>
            } @else if (intervalError()) {
              <div class="mt-3"><app-error [message]="intervalError()!" (retry)="loadIntervals()" /></div>
            } @else { <div class="skeleton mt-3 h-64"></div> }
          </section>

          <div class="space-y-4">
            <section class="card-pad" aria-labelledby="upload-heading">
              <h2 id="upload-heading" class="h-section">Import readings</h2>
              <p class="hint">GridIQ CSV (start,import_kwh,export_kwh,voltage,power_factor) or Green Button ESPI XML, up to 2,000 hours. Every row passes VEE checks.</p>
              <label class="btn-secondary btn-sm mt-3 cursor-pointer">
                <svg lucideUpload [size]="14" aria-hidden="true"></svg> Choose file
                <input type="file" class="sr-only" accept=".csv,.xml,text/csv,application/xml" (change)="upload($event)" />
              </label>
              @if (ingestion(); as result) {
                <p class="mt-3 text-sm" role="status">{{ result.inserted }} inserted · {{ result.duplicates }} duplicates · {{ result.flagged }} flagged</p>
              }
            </section>
            @if (meter.meterSerial.startsWith('M-DEMO-')) {
              <section class="card-pad" aria-labelledby="sim-heading">
                <h2 id="sim-heading" class="h-section">AMI simulator</h2>
                <p class="hint">Fills missing hours for this demo meter with realistic readings. Existing hours are never changed.</p>
                <div class="mt-3 flex items-end gap-2">
                  <div><label class="label" for="sim-months">Months</label>
                    <input id="sim-months" class="input num w-24" type="number" min="1" max="12" [(ngModel)]="simMonths" /></div>
                  <button type="button" class="btn-secondary" [disabled]="busy()" (click)="simulate()">Run simulator</button>
                </div>
              </section>
            }
          </div>
        </div>
      }
    }

    <app-dialog [open]="!!revision()" heading="Revise flagged reading" [subheading]="revision() ? (revision()!.start | moment) + ' IST' : ''" (closed)="revision.set(null)">
      @if (revision(); as draft) {
        <form id="revision-form" class="grid gap-3 sm:grid-cols-2" (ngSubmit)="revise()" #revisionForm="ngForm">
          <div><label class="label" for="r-import">Import kWh</label><input id="r-import" class="input num" type="number" step="0.0001" min="0" name="importKwh" [(ngModel)]="draft.importKwh" required /></div>
          <div><label class="label" for="r-export">Export kWh</label><input id="r-export" class="input num" type="number" step="0.0001" min="0" name="exportKwh" [(ngModel)]="draft.exportKwh" required /></div>
          <div><label class="label" for="r-voltage">Voltage</label><input id="r-voltage" class="input num" type="number" step="0.1" name="voltage" [(ngModel)]="draft.voltage" /></div>
          <div><label class="label" for="r-pf">Power factor</label><input id="r-pf" class="input num" type="number" step="0.001" min="0" max="1" name="powerFactor" [(ngModel)]="draft.powerFactor" /></div>
          <div class="sm:col-span-2"><label class="label" for="r-reason">Reason (recorded in the audit trail)</label>
            <input id="r-reason" class="input" name="reason" [(ngModel)]="revisionReason" required maxlength="500" placeholder="Check meter reading confirmed 230 V" /></div>
        </form>
      }
      <div dialog-footer class="empty:hidden flex justify-end gap-2 border-t border-line p-4">
        <button type="button" class="btn-secondary" (click)="revision.set(null)">Cancel</button>
        <button type="submit" form="revision-form" class="btn-primary" [disabled]="busy() || !revisionReason.trim()">Save revision</button>
      </div>
    </app-dialog>
  `,
})
export class Anomalies {
  readonly anomaly = input<string>();
  private readonly http = inject(HttpClient);
  private readonly toasts = inject(Toasts);
  protected readonly outcomes = OUTCOMES;
  protected readonly queuePage = QUEUE_PAGE;
  protected readonly filters = [
    { value: 'OPEN', label: 'Open' }, { value: 'INVESTIGATING', label: 'Investigating' }, { value: '', label: 'All' },
  ];
  protected readonly view = signal<'queue' | 'vee'>('queue');
  protected readonly status = signal('OPEN');
  protected readonly items = signal<Anomaly[]>([]);
  protected readonly page = signal(0);
  protected readonly pageItems = computed(() =>
    this.items().slice(this.page() * QUEUE_PAGE, (this.page() + 1) * QUEUE_PAGE));
  protected readonly error = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected notes: Record<string, string> = {};

  protected meterQuery = '';
  protected readonly meters = signal<Meter[]>([]);
  protected readonly selected = signal<Meter | null>(null);
  protected readonly days = signal(1);
  protected readonly showAll = signal(false);
  protected readonly intervals = signal<Intervals | null>(null);
  protected readonly intervalError = signal<string | null>(null);
  protected readonly ingestion = signal<{ inserted: number; duplicates: number; flagged: number } | null>(null);
  protected readonly revision = signal<Reading | null>(null);
  protected revisionReason = '';
  protected simMonths = 1;

  /** Exceptions only (flagged or missing hours) unless "All hours" is ticked, newest first. */
  protected readonly rows = computed<Row[]>(() => {
    const data = this.intervals();
    if (!data) return [];
    const rows: Row[] = [
      ...data.readings.filter((reading) => this.showAll() || reading.quality !== 'VALID').map((reading) => ({ start: reading.start, reading })),
      ...data.missing.map((start) => ({ start, reading: null })),
    ];
    return rows.sort((a, b) => b.start.localeCompare(a.start));
  });

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

  protected setStatus(status: string): void {
    this.status.set(status);
    this.page.set(0);
    void this.load();
  }

  async load(): Promise<void> {
    this.error.set(null);
    try {
      const query = this.status() ? `?status=${this.status()}&size=100` : '?size=100';
      this.items.set(await firstValueFrom(this.http.get<Anomaly[]>(`/api/v1/ops/anomalies${query}`)));
      if (this.page() * QUEUE_PAGE >= this.items().length) this.page.set(0);
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

  /** Opens the VEE tab on the anomaly's meter and window. */
  protected inspect(item: Anomaly): void {
    this.view.set('vee');
    this.meterQuery = item.meterSerial;
    this.choose({ id: item.meterId, meterSerial: item.meterSerial, connectionType: '' } as Meter);
  }

  protected async findMeters(): Promise<void> {
    try {
      const found = await firstValueFrom(this.http.get<Meter[]>(
        `/api/v1/ops/metering/meters?q=${encodeURIComponent(this.meterQuery.trim())}`));
      this.meters.set(found);
      if (found.length === 1) this.choose(found[0]);
      if (!found.length) this.toasts.show('No meter matches that serial.', 'info');
    } catch (error) {
      this.toasts.error(error);
    }
  }

  protected choose(meter: Meter): void {
    this.selected.set(meter);
    this.ingestion.set(null);
    void this.loadIntervals();
  }

  async loadIntervals(): Promise<void> {
    const meter = this.selected();
    if (!meter) return;
    this.intervals.set(null);
    this.intervalError.set(null);
    const from = encodeURIComponent(istDay(-this.days()));
    const to = encodeURIComponent(istDay(0));
    try {
      this.intervals.set(await firstValueFrom(this.http.get<Intervals>(
        `/api/v1/ops/metering/meters/${meter.id}/intervals?from=${from}&to=${to}`)));
    } catch (error) {
      this.intervalError.set(problemMessage(error, 'The intervals could not be loaded.'));
    }
  }

  protected async estimate(start: string): Promise<void> {
    const meter = this.selected();
    if (!meter) return;
    this.busy.set(true);
    try {
      await firstValueFrom(this.http.post(`/api/v1/meters/${meter.id}/readings/estimate`, { start }));
      this.toasts.success('Hour estimated from neighbouring readings.');
      await this.loadIntervals();
    } catch (error) {
      this.toasts.error(error);
    } finally {
      this.busy.set(false);
    }
  }

  protected startRevision(reading: Reading): void {
    this.revisionReason = '';
    this.revision.set({ ...reading });
  }

  protected async revise(): Promise<void> {
    const meter = this.selected();
    const draft = this.revision();
    if (!meter || !draft) return;
    this.busy.set(true);
    try {
      await firstValueFrom(this.http.patch(`/api/v1/meters/${meter.id}/readings`, {
        reason: this.revisionReason.trim(),
        reading: { start: draft.start, importKwh: draft.importKwh, exportKwh: draft.exportKwh,
          voltage: draft.voltage, powerFactor: draft.powerFactor },
      }));
      this.revision.set(null);
      this.toasts.success('Reading revised and re-validated.');
      await this.loadIntervals();
    } catch (error) {
      this.toasts.error(error);
    } finally {
      this.busy.set(false);
    }
  }

  protected async upload(event: Event): Promise<void> {
    const meter = this.selected();
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    if (!meter || !file) return;
    if (file.size > 500_000) {
      this.toasts.show('Choose a file under 500 KB (about 2,000 hourly rows).', 'error');
      return;
    }
    const xml = file.name.toLowerCase().endsWith('.xml');
    this.busy.set(true);
    try {
      const body = await file.text();
      const result = await firstValueFrom(this.http.post<{ inserted: number; duplicates: number; flagged: number }>(
        `/api/v1/meters/${meter.id}/import/${xml ? 'green-button' : 'csv'}`, body,
        { headers: new HttpHeaders({ 'Content-Type': xml ? 'application/xml' : 'text/csv' }) }));
      this.ingestion.set(result);
      this.toasts.success(`${file.name} imported.`);
      await this.loadIntervals();
    } catch (error) {
      this.toasts.error(error);
    } finally {
      this.busy.set(false);
    }
  }

  protected async simulate(): Promise<void> {
    const meter = this.selected();
    if (!meter) return;
    this.busy.set(true);
    try {
      const result = await firstValueFrom(this.http.post<{ inserted: number }>('/api/v1/ops/metering/simulate',
        { meterId: meter.id, months: Math.min(12, Math.max(1, Math.round(this.simMonths))) }));
      this.toasts.success(`Simulator added ${result.inserted} hourly readings.`);
      await this.loadIntervals();
    } catch (error) {
      this.toasts.error(error);
    } finally {
      this.busy.set(false);
    }
  }
}
