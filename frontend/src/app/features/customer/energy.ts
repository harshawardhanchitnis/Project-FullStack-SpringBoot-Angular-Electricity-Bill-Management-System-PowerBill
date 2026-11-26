import { HttpClient } from '@angular/common/http';
import { Component, computed, effect, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { LucideDownload, LucideSunMedium } from '@lucide/angular';
import { firstValueFrom } from 'rxjs';
import { CustomerStore } from '../../core/customer';
import { Simulation } from '../../core/models';
import { Toasts, problemMessage } from '../../core/ui-state';
import { download } from '../../shared/download';
import { ChartPalette, EChart, axisStyle, tooltipStyle } from '../../shared/echart';
import { FORMAT_PIPES, inr, kwh, period } from '../../shared/format';
import { ErrorBanner, KpiCard } from '../../shared/ui';

type Range = 'week' | 'month' | 'year';
interface Point { label: string; importKwh: number; exportKwh: number }

function istDate(offsetDays: number, monthStart = false): Date {
  const now = new Date(Date.now() + 5.5 * 3600 * 1000);
  const date = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), monthStart ? 1 : now.getUTCDate()));
  date.setUTCDate(date.getUTCDate() + offsetDays);
  return date;
}

function ist(date: Date): string {
  return `${date.toISOString().slice(0, 10)}T00:00:00+05:30`;
}

function completedMonths(count: number): string[] {
  const now = istDate(0, true);
  return Array.from({ length: count }, (_, index) => {
    const date = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth() - index - 1, 1));
    return date.toISOString().slice(0, 7);
  });
}

@Component({
  imports: [FormsModule, EChart, KpiCard, ErrorBanner, ...FORMAT_PIPES, LucideDownload, LucideSunMedium],
  template: `
    <div class="flex flex-wrap items-end justify-between gap-3">
      <div>
        <h1 class="h-page">Energy & simulator</h1>
        <p class="mt-1 text-sm text-muted">{{ customer.meter()?.meterSerial }} · validated smart meter intervals</p>
      </div>
      <div class="flex flex-wrap gap-2">
        <button type="button" class="btn-secondary btn-sm" (click)="export('csv')"><svg lucideDownload [size]="14" aria-hidden="true"></svg> CSV</button>
        <button type="button" class="btn-secondary btn-sm" (click)="export('green-button')"><svg lucideDownload [size]="14" aria-hidden="true"></svg> Green Button XML</button>
      </div>
    </div>

    <section class="card-pad mt-6" aria-labelledby="usage-heading">
      <div class="flex flex-wrap items-center justify-between gap-3">
        <h2 id="usage-heading" class="h-section">Usage history</h2>
        <div class="tabs" role="tablist" aria-label="Range">
          @for (option of ranges; track option.value) {
            <button type="button" role="tab" class="tab" [attr.aria-selected]="range() === option.value" (click)="range.set(option.value)">{{ option.label }}</button>
          }
        </div>
      </div>
      @if (error()) { <div class="mt-4"><app-error [message]="error()!" (retry)="loadUsage()" /></div> }
      <app-chart class="mt-3" [options]="usageChart()" [height]="280" label="Imported and exported energy over the selected range" />
      <p class="mt-2 text-sm text-muted">Total imported {{ totalImport() | kwh }}@if (totalExport() > 0) { · exported {{ totalExport() | kwh }}}</p>
    </section>

    <section class="card-pad mt-4" aria-labelledby="heatmap-heading">
      <h2 id="heatmap-heading" class="h-section">Hour-by-day load map · last 14 days</h2>
      <p class="text-xs text-faint">Darker cells use more energy. Evening peak runs 18:00–22:00; solar hours 09:00–17:00.</p>
      <app-chart class="mt-3" [options]="heatmapChart()" [height]="320" label="Heatmap of hourly energy use for the last 14 days" />
    </section>

    <section class="card-pad mt-4" aria-labelledby="sim-heading">
      <h2 id="sim-heading" class="h-section flex items-center gap-2">
        <svg lucideSunMedium [size]="18" class="text-warn" aria-hidden="true"></svg> What-if simulator
      </h2>
      <p class="mt-1 text-sm text-muted">Replays every hour of a past month through your tariff with your changes applied.</p>
      <form class="mt-4 grid gap-5 md:grid-cols-4" (ngSubmit)="simulate()">
        <div>
          <label class="label" for="sim-month">Month</label>
          <select id="sim-month" class="input" name="month" [(ngModel)]="month">
            @for (option of months; track option) { <option [value]="option">{{ option | period }}</option> }
          </select>
        </div>
        <div>
          <label class="label" for="sim-shift">Shift evening peak to solar hours: <span class="num">{{ shift }}%</span></label>
          <input id="sim-shift" type="range" min="0" max="60" step="5" name="shift" [(ngModel)]="shift" class="w-full accent-[var(--brand)]" />
        </div>
        <div>
          <label class="label" for="sim-solar">Rooftop solar: <span class="num">{{ solarKw }} kW</span></label>
          <input id="sim-solar" type="range" min="0" max="10" step="0.5" name="solar" [(ngModel)]="solarKw" class="w-full accent-[var(--brand)]" />
          <p class="hint">Est. cost {{ solarCost() | inr: true }} at ₹50,000/kW</p>
        </div>
        <div class="flex items-end">
          <button type="submit" class="btn-primary w-full" [disabled]="simulating()">{{ simulating() ? 'Simulating…' : 'Run simulation' }}</button>
        </div>
      </form>
      @if (simulation(); as sim) {
        <div class="mt-6 grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
          <app-kpi label="Actual bill" [value]="sim.baseline.totalInr | inr" [hint]="(sim.baseline.netBillableKwh | kwh) + ' billed'" />
          <app-kpi label="With your changes" [value]="sim.projected.totalInr | inr" [hint]="(sim.projected.netBillableKwh | kwh) + ' billed'" tone="ok" />
          <app-kpi label="Yearly savings" [value]="sim.annualizedSavingsInr | inr" hint="Based on this month × 12" [tone]="sim.annualizedSavingsInr > 0 ? 'ok' : 'muted'" />
          <app-kpi label="Solar payback" [value]="sim.solarPaybackYears === null ? '—' : sim.solarPaybackYears + ' years'"
                   [hint]="solarKw > 0 ? 'Before subsidies' : 'Add rooftop solar to see payback'" />
        </div>
        <app-chart class="mt-4" [options]="comparisonChart()" [height]="240" label="Bill components before and after the simulated changes" />
      }
    </section>
  `,
})
export class Energy {
  protected readonly customer = inject(CustomerStore);
  private readonly http = inject(HttpClient);
  private readonly toasts = inject(Toasts);
  protected readonly ranges: { value: Range; label: string }[] = [
    { value: 'week', label: '7 days' }, { value: 'month', label: '30 days' }, { value: 'year', label: '12 months' },
  ];
  protected readonly range = signal<Range>('month');
  protected readonly points = signal<Point[]>([]);
  protected readonly heat = signal<[number, number, number][]>([]);
  protected readonly heatDays = signal<string[]>([]);
  protected readonly error = signal<string | null>(null);
  protected readonly months = completedMonths(6);
  protected month = this.months[0];
  protected shift = 25;
  protected solarKw = 2;
  protected readonly simulation = signal<Simulation | null>(null);
  protected readonly simulating = signal(false);
  protected readonly totalImport = computed(() => this.points().reduce((sum, point) => sum + point.importKwh, 0));
  protected readonly totalExport = computed(() => this.points().reduce((sum, point) => sum + point.exportKwh, 0));

  protected solarCost(): number {
    return this.solarKw * 50_000;
  }

  protected readonly usageChart = computed(() => {
    const points = this.points();
    return (c: ChartPalette) => ({
      grid: { left: 48, right: 12, top: 30, bottom: 30 },
      tooltip: { trigger: 'axis', ...tooltipStyle(c), valueFormatter: (value: number) => kwh(value) },
      legend: { top: 0, right: 0, textStyle: { color: c.muted } },
      xAxis: { type: 'category', data: points.map((p) => p.label), ...axisStyle(c) },
      yAxis: { type: 'value', name: 'kWh', ...axisStyle(c) },
      series: [
        { name: 'Imported', type: 'bar', data: points.map((p) => p.importKwh), itemStyle: { color: c.brand, borderRadius: [3, 3, 0, 0] }, barMaxWidth: 22 },
        ...(points.some((p) => p.exportKwh > 0) ? [{ name: 'Exported', type: 'bar', data: points.map((p) => p.exportKwh), itemStyle: { color: c.ok, borderRadius: [3, 3, 0, 0] }, barMaxWidth: 22 }] : []),
      ],
    });
  });

  protected readonly heatmapChart = computed(() => {
    const cells = this.heat();
    const days = this.heatDays();
    const max = Math.max(0.1, ...cells.map((cell) => cell[2]));
    return (c: ChartPalette) => ({
      grid: { left: 64, right: 16, top: 10, bottom: 54 },
      tooltip: { ...tooltipStyle(c), formatter: (item: { value: [number, number, number] }) =>
        `${days[item.value[1]]} · ${String(item.value[0]).padStart(2, '0')}:00<br/>${kwh(item.value[2])}` },
      xAxis: { type: 'category', data: Array.from({ length: 24 }, (_, h) => `${h}`), ...axisStyle(c), splitArea: { show: false } },
      yAxis: { type: 'category', data: days, ...axisStyle(c) },
      visualMap: { min: 0, max, calculable: false, orient: 'horizontal', left: 'center', bottom: 0, itemHeight: 120,
        textStyle: { color: c.muted }, inRange: { color: [c.sunken, c.cyan, c.brand, c.bad] } },
      series: [{ type: 'heatmap', data: cells, itemStyle: { borderColor: c.surface, borderWidth: 1 } }],
    });
  });

  protected readonly comparisonChart = computed(() => {
    const sim = this.simulation();
    return (c: ChartPalette) => {
      if (!sim) return {};
      const categories = ['SLAB', 'TOD', 'FIXED', 'FAC', 'DUTY', 'SOLAR_CREDIT'];
      const labels: Record<string, string> = { SLAB: 'Energy', TOD: 'Time of day', FIXED: 'Fixed', FAC: 'FAC', DUTY: 'Duty', SOLAR_CREDIT: 'Solar credit' };
      const total = (quote: Simulation['baseline'], category: string) =>
        quote.lineItems.filter((line) => line.category === category).reduce((sum, line) => sum + line.amountInr, 0);
      const used = categories.filter((category) => total(sim.baseline, category) !== 0 || total(sim.projected, category) !== 0);
      return {
        grid: { left: 90, right: 20, top: 30, bottom: 24 },
        legend: { top: 0, right: 0, textStyle: { color: c.muted } },
        tooltip: { trigger: 'axis', ...tooltipStyle(c), valueFormatter: (value: number) => inr(value) },
        xAxis: { type: 'value', ...axisStyle(c) },
        yAxis: { type: 'category', data: used.map((category) => labels[category]), ...axisStyle(c) },
        series: [
          { name: 'Actual', type: 'bar', data: used.map((category) => total(sim.baseline, category)), itemStyle: { color: c.muted } },
          { name: 'Simulated', type: 'bar', data: used.map((category) => total(sim.projected, category)), itemStyle: { color: c.ok } },
        ],
      };
    };
  });

  constructor() {
    effect(() => {
      const meter = this.customer.meter();
      this.range();
      if (meter) void this.loadUsage();
    });
    effect(() => {
      const meter = this.customer.meter();
      if (meter) void this.loadHeatmap(meter.id);
    });
  }

  async loadUsage(): Promise<void> {
    const meter = this.customer.meter();
    if (!meter) return;
    this.error.set(null);
    const range = this.range();
    const to = istDate(range === 'year' ? 0 : 1, range === 'year');
    const from = range === 'week' ? istDate(-6) : range === 'month' ? istDate(-29) : istDate(0, true);
    if (range === 'year') from.setUTCMonth(from.getUTCMonth() - 11);
    const granularity = range === 'year' ? 'monthly' : 'daily';
    const url = `/api/v1/meters/${meter.id}/readings?from=${encodeURIComponent(ist(from))}&to=${encodeURIComponent(ist(range === 'year' ? istDate(1) : to))}&granularity=${granularity}`;
    try {
      const rows = await firstValueFrom(this.http.get<{ bucket: string; importKwh: number; exportKwh: number }[]>(url));
      this.points.set(rows.map((row) => ({
        label: range === 'year' ? period(new Date(row.bucket).toISOString().slice(0, 7), true)
          : new Date(row.bucket).toLocaleDateString('en-IN', { day: 'numeric', month: 'short', timeZone: 'Asia/Kolkata' }),
        importKwh: +row.importKwh, exportKwh: +row.exportKwh,
      })));
    } catch (error) {
      this.error.set(problemMessage(error, 'Usage could not be loaded.'));
    }
  }

  private async loadHeatmap(meterId: string): Promise<void> {
    const from = ist(istDate(-13));
    const to = ist(istDate(1));
    try {
      const rows = await firstValueFrom(this.http.get<{ start: string; importKwh: number }[]>(
        `/api/v1/meters/${meterId}/readings?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}&granularity=hourly`));
      const days: string[] = [];
      const cells: [number, number, number][] = [];
      for (const row of rows) {
        const local = new Date(new Date(row.start).getTime() + 5.5 * 3600 * 1000);
        const label = local.toISOString().slice(5, 10);
        if (!days.includes(label)) days.push(label);
        cells.push([local.getUTCHours(), days.indexOf(label), +row.importKwh]);
      }
      this.heatDays.set(days);
      this.heat.set(cells);
    } catch {
      this.heat.set([]);
    }
  }

  protected async simulate(): Promise<void> {
    const meter = this.customer.meter();
    if (!meter) return;
    this.simulating.set(true);
    try {
      this.simulation.set(await firstValueFrom(this.http.post<Simulation>('/api/v1/tariffs/simulate', {
        meterId: meter.id, month: this.month, loadShiftPct: this.shift, rooftopSolarKw: this.solarKw,
        installedCostInr: this.solarKw > 0 ? this.solarCost() : null,
      })));
    } catch (error) {
      this.toasts.error(error, 'The simulation could not run for this month.');
    } finally {
      this.simulating.set(false);
    }
  }

  protected async export(kind: 'csv' | 'green-button'): Promise<void> {
    const meter = this.customer.meter();
    if (!meter) return;
    const from = encodeURIComponent(ist(istDate(-29)));
    const to = encodeURIComponent(ist(istDate(1)));
    await download(this.http, `/api/v1/meters/${meter.id}/export/${kind}?from=${from}&to=${to}`,
      `${meter.meterSerial}-usage.${kind === 'csv' ? 'csv' : 'xml'}`).catch((error) => this.toasts.error(error));
  }
}
