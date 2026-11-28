import { HttpClient } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { LucideHeadset, LucideIndianRupee, LucideLandmark, LucideWallet } from '@lucide/angular';
import { firstValueFrom } from 'rxjs';
import { problemMessage } from '../../core/ui-state';
import { ChartPalette, EChart, axisStyle, tooltipStyle } from '../../shared/echart';
import { FORMAT_PIPES, humanize, inr, period } from '../../shared/format';
import { ErrorBanner, KpiCard } from '../../shared/ui';

interface ExecutiveData {
  revenue: { month: string; bills: number; billedInr: number; collectedInr: number; billedKwh: number }[];
  billedLast12Inr: number;
  collectedLast12Inr: number;
  collectionEfficiencyPct: number | null;
  outstandingInr: number;
  aging: { bucket: string; bills: number; outstandingInr: number }[];
  slabDistribution: { band: string; bills: number }[];
  anomalies: { open: number; investigating: number; confirmedTamper: number; confirmedFault: number; falsePositive: number;
    openByType: { key: string; count: number }[]; openBySeverity: { key: string; count: number }[] };
  sla: { openTickets: number; breachedOpen: number; resolvedLast90Days: number; compliancePct: number;
    averageResolutionHours: number; averageCsat: number; openByPriority: { priority: string; count: number }[] };
  consumers: number;
  meters: number;
  regions: Region[];
}

/** Last three billed months for one region; the loss proxy is metered minus billed energy over metered energy. */
interface Region {
  regionZone: string;
  city: string;
  meters: number;
  meteredKwh: number;
  billedKwh: number;
  lossProxyPct: number | null;
  billedInr: number;
  collectedInr: number;
  collectionEfficiencyPct: number | null;
  overdueInr: number;
  openAnomalies: number;
}

@Component({
  imports: [EChart, KpiCard, ErrorBanner, RouterLink, ...FORMAT_PIPES, LucideIndianRupee, LucideWallet, LucideLandmark, LucideHeadset],
  template: `
    <div class="flex flex-wrap items-end justify-between gap-3">
      <div>
        <p class="eyebrow">Utility command centre</p>
        <h1 class="h-page">Executive overview</h1>
        <p class="mt-1 text-sm text-muted">{{ data()?.consumers ?? '—' }} consumer accounts · {{ data()?.meters ?? '—' }} active smart meters</p>
      </div>
      <button type="button" class="btn-secondary btn-sm" (click)="load()">Refresh</button>
    </div>
    @if (error()) { <div class="mt-5"><app-error [message]="error()!" (retry)="load()" /></div> }

    <section class="mt-6 grid gap-4 sm:grid-cols-2 xl:grid-cols-4" aria-label="Key figures">
      <app-kpi label="Billed · last 12 months" [loading]="!data()" [value]="(data()?.billedLast12Inr ?? null) | inr: true">
        <svg kpi-icon lucideIndianRupee [size]="16" aria-hidden="true"></svg></app-kpi>
      <app-kpi label="Collected · last 12 months" [loading]="!data()" [value]="(data()?.collectedLast12Inr ?? null) | inr: true" tone="ok"
               [hint]="'Collection efficiency ' + (data()?.collectionEfficiencyPct ?? '—') + '%'">
        <svg kpi-icon lucideWallet [size]="16" aria-hidden="true"></svg></app-kpi>
      <app-kpi label="Outstanding receivables" [loading]="!data()" [value]="(data()?.outstandingInr ?? null) | inr: true"
               [hint]="overdueHint()" [tone]="overdueAmount() > 0 ? 'warn' : 'muted'">
        <svg kpi-icon lucideLandmark [size]="16" aria-hidden="true"></svg></app-kpi>
      <app-kpi label="Complaint SLA compliance" [loading]="!data()" [value]="(data()?.sla?.compliancePct ?? '—') + '%'"
               [hint]="(data()?.sla?.breachedOpen ?? 0) + ' open tickets breached · CSAT ' + (data()?.sla?.averageCsat ?? '—')"
               [tone]="(data()?.sla?.breachedOpen ?? 0) > 0 ? 'bad' : 'ok'">
        <svg kpi-icon lucideHeadset [size]="16" aria-hidden="true"></svg></app-kpi>
    </section>

    @if (data()) {
      <div class="mt-4 grid gap-4 xl:grid-cols-3">
        <section class="card-pad xl:col-span-2" aria-labelledby="revenue-heading">
          <h2 id="revenue-heading" class="h-section">Revenue billed vs collected</h2>
          <app-chart class="mt-2" [options]="revenueChart()" [height]="280" label="Monthly billed and collected revenue" />
        </section>
        <section class="card-pad" aria-labelledby="aging-heading">
          <h2 id="aging-heading" class="h-section">Receivables aging</h2>
          <app-chart class="mt-2" [options]="agingChart()" [height]="280" label="Outstanding receivables by days past due" />
        </section>
      </div>
      <div class="mt-4 grid gap-4 lg:grid-cols-3">
        <section class="card-pad" aria-labelledby="slab-mix-heading">
          <h2 id="slab-mix-heading" class="h-section">Consumption slab mix · 3 months</h2>
          <app-chart class="mt-2" [options]="slabChart()" [height]="240" label="Bills by consumption band" />
        </section>
        <section class="card-pad" aria-labelledby="anomaly-heading">
          <div class="flex items-center justify-between">
            <h2 id="anomaly-heading" class="h-section">Open meter anomalies</h2>
            <a routerLink="/ops/anomalies" class="text-sm font-medium text-brand hover:underline">Queue</a>
          </div>
          <p class="num mt-2 text-3xl font-semibold">{{ data()!.anomalies.open + data()!.anomalies.investigating }}</p>
          <p class="text-sm text-muted">{{ data()!.anomalies.confirmedTamper }} confirmed tamper · {{ data()!.anomalies.confirmedFault }} confirmed faults</p>
          <ul class="mt-4 space-y-2 text-sm">
            @for (item of data()!.anomalies.openByType; track item.key) {
              <li class="flex items-center gap-3"><span class="w-40 text-muted">{{ item.key | humanize }}</span>
                <span class="h-2 flex-1 overflow-hidden rounded-full bg-sunken"><span class="block h-full rounded-full bg-bad" [style.width.%]="share(item.count)"></span></span>
                <span class="num w-6 text-right">{{ item.count }}</span></li>
            } @empty { <li class="text-muted">No open anomalies.</li> }
          </ul>
        </section>
        <section class="card-pad" aria-labelledby="desk-heading">
          <div class="flex items-center justify-between">
            <h2 id="desk-heading" class="h-section">Support desk</h2>
            <a routerLink="/ops/support" class="text-sm font-medium text-brand hover:underline">Desk</a>
          </div>
          <app-chart [options]="slaGauge()" [height]="170" label="Complaint SLA compliance gauge" />
          <p class="text-sm text-muted">{{ data()!.sla.openTickets }} open · avg resolution {{ data()!.sla.averageResolutionHours }} h</p>
          <div class="mt-2 flex flex-wrap gap-2">
            @for (item of data()!.sla.openByPriority; track item.priority) {
              <span class="badge bg-sunken text-ink">{{ item.priority | humanize }} {{ item.count }}</span>
            }
          </div>
        </section>
      </div>

      <section class="card mt-4 overflow-hidden" aria-labelledby="regions-heading">
        <div class="flex flex-wrap items-baseline justify-between gap-2 p-5 pb-3">
          <h2 id="regions-heading" class="h-section">Regional performance · last 3 billed months</h2>
          <p class="text-xs text-faint">Loss proxy = metered minus billed energy, over metered energy (an AT&amp;C indicator, not a technical-loss figure)</p>
        </div>
        <div class="table-wrap rounded-none border-0 border-t">
          <table class="table">
            <caption class="sr-only">Energy, billing and collection by region</caption>
            <thead><tr><th>Region</th><th class="text-right">Meters</th><th class="text-right">Metered</th><th class="text-right">Billed</th>
              <th class="text-right">Loss proxy</th><th class="text-right">Billed ₹</th><th class="text-right">Collection</th>
              <th class="text-right">Overdue</th><th class="text-right">Open anomalies</th></tr></thead>
            <tbody>
              @for (region of data()!.regions; track region.regionZone) {
                <tr>
                  <td class="font-medium">{{ region.regionZone }}@if (region.city && region.city !== region.regionZone) {
                    <span class="block text-xs text-faint">{{ region.city }}</span>}</td>
                  <td class="num text-right">{{ region.meters }}</td>
                  <td class="num text-right">{{ region.meteredKwh | kwh }}</td>
                  <td class="num text-right">{{ region.billedKwh | kwh }}</td>
                  <td class="num text-right" [class]="lossTone(region.lossProxyPct)">{{ region.lossProxyPct === null ? '—' : region.lossProxyPct + '%' }}</td>
                  <td class="num text-right">{{ region.billedInr | inr: true }}</td>
                  <td class="num text-right" [class]="collectionTone(region.collectionEfficiencyPct)">{{ region.collectionEfficiencyPct === null ? '—' : region.collectionEfficiencyPct + '%' }}</td>
                  <td class="num text-right">{{ region.overdueInr | inr: true }}</td>
                  <td class="num text-right">{{ region.openAnomalies }}</td>
                </tr>
              } @empty { <tr><td colspan="9" class="text-muted">No billed regions yet.</td></tr> }
            </tbody>
          </table>
        </div>
      </section>
    } @else if (!error()) {
      <div class="mt-4 grid gap-4 xl:grid-cols-3" aria-hidden="true">
        <div class="skeleton h-[330px] xl:col-span-2"></div>
        <div class="skeleton h-[330px]"></div>
      </div>
      <div class="mt-4 grid gap-4 lg:grid-cols-3" aria-hidden="true">
        <div class="skeleton h-72"></div><div class="skeleton h-72"></div><div class="skeleton h-72"></div>
      </div>
    }
  `,
})
export class Executive {
  private readonly http = inject(HttpClient);
  protected readonly data = signal<ExecutiveData | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly overdueAmount = computed(() =>
    (this.data()?.aging ?? []).filter((bucket) => bucket.bucket !== 'Not yet due').reduce((sum, b) => sum + b.outstandingInr, 0));
  protected readonly overdueHint = computed(() => `${inr(this.overdueAmount(), true)} past due`);

  protected readonly revenueChart = computed(() => {
    const rows = this.data()?.revenue ?? [];
    return (c: ChartPalette) => ({
      grid: { left: 64, right: 16, top: 34, bottom: 28 },
      tooltip: { trigger: 'axis', ...tooltipStyle(c), valueFormatter: (value: number) => inr(value) },
      legend: { top: 0, right: 0, textStyle: { color: c.muted } },
      xAxis: { type: 'category', data: rows.map((row) => period(row.month, true)), ...axisStyle(c) },
      yAxis: { type: 'value', ...axisStyle(c), axisLabel: { color: c.muted, formatter: (v: number) => `₹${(v / 1000).toFixed(0)}k` } },
      series: [
        { name: 'Billed', type: 'bar', data: rows.map((row) => row.billedInr), itemStyle: { color: c.brand, borderRadius: [4, 4, 0, 0] }, barMaxWidth: 28 },
        { name: 'Collected', type: 'line', data: rows.map((row) => row.collectedInr), smooth: true, itemStyle: { color: c.ok }, lineStyle: { width: 2.5 } },
      ],
    });
  });

  protected readonly agingChart = computed(() => {
    const rows = this.data()?.aging ?? [];
    return (c: ChartPalette) => ({
      grid: { left: 84, right: 24, top: 10, bottom: 24 },
      tooltip: { trigger: 'axis', ...tooltipStyle(c), valueFormatter: (value: number) => inr(value) },
      xAxis: { type: 'value', ...axisStyle(c), axisLabel: { color: c.muted, formatter: (v: number) => `₹${(v / 1000).toFixed(0)}k` } },
      yAxis: { type: 'category', data: rows.map((row) => row.bucket), ...axisStyle(c), inverse: true },
      series: [{ type: 'bar', data: rows.map((row, index) => ({ value: row.outstandingInr,
        itemStyle: { color: [c.brand, c.warn, c.warn, c.bad, c.bad][index] ?? c.bad, borderRadius: [0, 4, 4, 0] } })) }],
    });
  });

  protected readonly slabChart = computed(() => {
    const rows = this.data()?.slabDistribution ?? [];
    return (c: ChartPalette) => ({
      tooltip: { trigger: 'item', ...tooltipStyle(c) },
      legend: { bottom: 0, textStyle: { color: c.muted }, itemWidth: 10, itemHeight: 10 },
      series: [{ type: 'pie', radius: ['48%', '72%'], center: ['50%', '42%'], label: { show: false },
        itemStyle: { borderColor: c.surface, borderWidth: 2 },
        data: rows.map((row, index) => ({ name: row.band, value: row.bills, itemStyle: { color: [c.ok, c.cyan, c.warn, c.bad][index] } })) }],
    });
  });

  protected readonly slaGauge = computed(() => {
    const value = this.data()?.sla.compliancePct ?? 0;
    return (c: ChartPalette) => ({
      series: [{ type: 'gauge', startAngle: 200, endAngle: -20, min: 0, max: 100, radius: '95%', center: ['50%', '62%'],
        progress: { show: true, width: 12, itemStyle: { color: value >= 90 ? c.ok : value >= 75 ? c.warn : c.bad } },
        axisLine: { lineStyle: { width: 12, color: [[1, c.sunken]] } }, axisTick: { show: false }, splitLine: { show: false },
        axisLabel: { show: false }, pointer: { show: false }, anchor: { show: false },
        detail: { valueAnimation: true, formatter: '{value}%', color: c.ink, fontSize: 22, fontWeight: 600, offsetCenter: [0, '0%'] },
        data: [{ value }] }],
    });
  });

  constructor() {
    void this.load();
  }

  protected share(count: number): number {
    const max = Math.max(1, ...(this.data()?.anomalies.openByType ?? []).map((item) => item.count));
    return (count / max) * 100;
  }

  protected label(value: string): string {
    return humanize(value);
  }

  protected lossTone(pct: number | null): string {
    return pct === null ? 'text-faint' : pct > 15 ? 'text-bad' : pct > 8 ? 'text-warn' : 'text-ok';
  }

  protected collectionTone(pct: number | null): string {
    return pct === null ? 'text-faint' : pct < 75 ? 'text-bad' : pct < 90 ? 'text-warn' : 'text-ok';
  }

  async load(): Promise<void> {
    this.error.set(null);
    try {
      this.data.set(await firstValueFrom(this.http.get<ExecutiveData>('/api/v1/ops/analytics/executive')));
    } catch (error) {
      this.error.set(problemMessage(error, 'The executive overview could not be loaded.'));
    }
  }
}
