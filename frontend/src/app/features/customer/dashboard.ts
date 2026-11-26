import { HttpClient } from '@angular/common/http';
import { Component, computed, effect, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { LucideAlertTriangle, LucideSiren, LucideSparkles } from '@lucide/angular';
import { firstValueFrom } from 'rxjs';
import { AuthStore } from '../../core/auth';
import { CustomerStore } from '../../core/customer';
import { ConsumerOverview, Forecast } from '../../core/models';
import { NotificationStore, problemMessage } from '../../core/ui-state';
import { CopilotState } from '../../layout/copilot';
import { ChartPalette, EChart, axisStyle, tooltipStyle } from '../../shared/echart';
import { FORMAT_PIPES, inr, kwh, period } from '../../shared/format';
import { ErrorBanner, KpiCard } from '../../shared/ui';

const WINDOW_LABEL: Record<string, string> = {
  SOLAR_OFF_PEAK: 'Solar hours', NORMAL: 'Normal', EVENING_PEAK: 'Evening peak',
};

@Component({
  imports: [RouterLink, EChart, KpiCard, ErrorBanner, ...FORMAT_PIPES, LucideSiren, LucideSparkles,
    LucideAlertTriangle],
  template: `
    <div class="flex flex-wrap items-end justify-between gap-3">
      <div>
        <p class="eyebrow">{{ greeting() }}</p>
        <h1 class="h-page">{{ auth.user()?.fullName }}</h1>
        @if (overview(); as data) {
          <p class="mt-1 text-sm text-muted">{{ data.meter.serial }} · {{ data.meter.premiseLabel }} · {{ data.meter.tariffName }}</p>
        }
      </div>
      <a routerLink="/app/bills" class="btn-primary">Pay bills</a>
    </div>

    @for (outage of overview()?.outages ?? []; track outage.id) {
      <div role="status" class="mt-5 flex gap-3 rounded-xl border border-warn/40 bg-warn-soft p-4 text-sm">
        <svg lucideSiren [size]="18" class="mt-0.5 shrink-0 text-warn" aria-hidden="true"></svg>
        <div>
          <p class="font-semibold text-ink">{{ outage.title }}</p>
          <p class="text-muted">{{ outage.status | humanize }}@if (outage.estimatedRestorationAt) {
            · restoration expected {{ outage.estimatedRestorationAt | moment }} IST}</p>
        </div>
      </div>
    }

    @if (error()) {
      <div class="mt-6"><app-error [message]="error()!" (retry)="load()" /></div>
    }

    <section class="mt-6 grid gap-4 sm:grid-cols-2 xl:grid-cols-4" aria-label="Key figures">
      <app-kpi label="Projected bill this month" [loading]="!forecast()"
               [value]="(forecast()?.projectedBillInr?.p50 ?? null) | inr"
               [hint]="forecastHint()" [tone]="overBudget() ? 'bad' : 'muted'">
        @if (overBudget()) {
          <span class="badge mt-3 bg-bad-soft text-bad"><svg lucideAlertTriangle [size]="12" aria-hidden="true"></svg>
            Bill-shock: above your {{ overview()?.monthlyBudgetInr | inr: true }} budget</span>
        }
      </app-kpi>
      <app-kpi label="Amount due" [loading]="!overview()" [value]="due() | inr" [tone]="(overview()?.receivables?.overdueBills ?? 0) > 0 ? 'bad' : 'muted'"
               [hint]="dueHint()" />
      <app-kpi label="Used this cycle" [loading]="!overview()" [value]="(overview()?.cycle?.importKwhToDate ?? null) | kwh"
               [hint]="usageHint()" [tone]="(overview()?.cycle?.changePct ?? 0) > 10 ? 'warn' : 'ok'" />
      <app-kpi label="Support" [loading]="!overview()" [value]="plural(overview()?.openComplaints ?? 0, 'open ticket')"
               [hint]="plural(overview()?.outages?.length ?? 0, 'outage') + ' in your area'" />
    </section>

    @if (overview(); as data) {
      <section class="card-pad mt-4" aria-labelledby="slab-heading">
        <div class="flex flex-wrap items-baseline justify-between gap-2">
          <h2 id="slab-heading" class="h-section">Slab progress · {{ data.cycle.month | period }}</h2>
          <p class="text-sm text-muted">{{ slabMessage() }}</p>
        </div>
        <div class="relative mt-4 flex h-3 overflow-hidden rounded-full bg-sunken" role="meter"
             [attr.aria-valuenow]="data.slab.currentKwh" aria-valuemin="0" [attr.aria-valuemax]="gaugeMax()"
             [attr.aria-label]="'Energy used this cycle: ' + data.slab.currentKwh + ' kWh'">
          @for (segment of segments(); track $index) {
            <span class="h-full border-r-2 border-surface last:border-0" [style.width.%]="segment.width"
                  [class]="segment.used ? segment.tone : 'bg-transparent'"></span>
          }
          @if (forecastMarker() !== null) {
            <span class="absolute inset-y-0 w-0.5 bg-ink" [style.left.%]="forecastMarker()" title="Projected end of cycle"></span>
          }
        </div>
        <div class="mt-2 flex justify-between text-xs text-faint">
          @for (edge of data.slab.slabs; track $index) {
            <span class="num">{{ edge.fromKwh }}{{ edge.untilKwh === null ? '+' : '' }} kWh · ₹{{ edge.ratePerKwh }}</span>
          }
        </div>
      </section>

      <div class="mt-4 grid gap-4 xl:grid-cols-3">
        <section class="card-pad xl:col-span-2" aria-labelledby="monthly-heading">
          <h2 id="monthly-heading" class="h-section">Monthly usage and bills</h2>
          <app-chart class="mt-2" [options]="monthlyChart()" label="Monthly imported energy and billed amount for the last 12 months" />
        </section>
        <section class="card-pad" aria-labelledby="forecast-heading">
          <h2 id="forecast-heading" class="h-section">Cycle forecast</h2>
          <p class="text-xs text-faint">{{ forecast()?.model }}{{ forecast()?.source === 'fallback' ? ' (ML service unavailable)' : '' }}</p>
          @if (forecast()) {
            <app-chart class="mt-2" [options]="forecastChart()" label="Cumulative energy this cycle with forecast range" />
          } @else { <div class="skeleton mt-3 h-[260px]"></div> }
        </section>
      </div>

      <div class="mt-4 grid gap-4 xl:grid-cols-3">
        <section class="card-pad xl:col-span-2" aria-labelledby="tod-heading">
          <h2 id="tod-heading" class="h-section">When you use electricity · last 30 days</h2>
          <app-chart class="mt-2" [options]="hourlyChart()" label="Energy by hour of day, coloured by time-of-day window" />
        </section>
        <section class="card-pad" aria-labelledby="activity-heading">
          <div class="flex items-center justify-between">
            <h2 id="activity-heading" class="h-section">Recent activity</h2>
            <button type="button" class="btn-ghost btn-sm" (click)="copilotHint()">
              <svg lucideSparkles [size]="14" aria-hidden="true"></svg> Ask Copilot
            </button>
          </div>
          <ul class="mt-3 space-y-3">
            @for (item of notifications.items().slice(0, 6); track item.id) {
              <li class="text-sm">
                <p class="font-medium text-ink">{{ item.title }}</p>
                <p class="text-muted">{{ item.message }}</p>
                <p class="text-xs text-faint">{{ item.createdAt | relative }}</p>
              </li>
            } @empty {
              <li class="text-sm text-muted">No activity yet.</li>
            }
          </ul>
        </section>
      </div>
    } @else if (!error()) {
      <div class="mt-4 grid gap-4 xl:grid-cols-3">
        <div class="skeleton h-80 xl:col-span-2"></div>
        <div class="skeleton h-80"></div>
      </div>
    }
  `,
})
export class CustomerDashboard {
  protected readonly auth = inject(AuthStore);
  protected readonly notifications = inject(NotificationStore);
  private readonly customer = inject(CustomerStore);
  private readonly copilot = inject(CopilotState);
  private readonly http = inject(HttpClient);
  protected readonly overview = signal<ConsumerOverview | null>(null);
  protected readonly forecast = signal<Forecast | null>(null);
  protected readonly error = signal<string | null>(null);

  protected readonly greeting = computed(() => {
    const hour = new Date().getHours();
    return hour < 12 ? 'Good morning' : hour < 17 ? 'Good afternoon' : 'Good evening';
  });
  protected readonly due = computed(() => {
    const receivables = this.overview()?.receivables;
    return receivables ? receivables.principalDue + receivables.lateFeesDue : null;
  });
  protected readonly dueHint = computed(() => {
    const receivables = this.overview()?.receivables;
    if (!receivables) return '';
    if (!receivables.openBills) return 'All bills paid';
    return `${receivables.openBills} open · ${receivables.overdueBills} overdue`;
  });
  protected readonly overBudget = computed(() => {
    const budget = this.overview()?.monthlyBudgetInr;
    const projected = this.forecast()?.projectedBillInr.p50;
    return !!budget && projected !== undefined && projected > budget;
  });
  protected readonly forecastHint = computed(() => {
    const band = this.forecast()?.projectedBillInr;
    return band ? `Likely ${inr(band.p10, true)} – ${inr(band.p90, true)}` : '';
  });
  protected readonly usageHint = computed(() => {
    const change = this.overview()?.cycle.changePct;
    if (change === null || change === undefined) return 'First cycle';
    return `${change > 0 ? '+' : ''}${change}% vs same days last month`;
  });
  protected readonly gaugeMax = computed(() => {
    const data = this.overview();
    if (!data) return 1;
    const last = data.slab.slabs.at(-2)?.untilKwh ?? 500;
    return Math.max(last * 1.2, (this.forecast()?.projectedKwh.p90 ?? 0) * 1.05, data.slab.currentKwh * 1.05);
  });
  protected readonly segments = computed(() => {
    const data = this.overview();
    if (!data) return [];
    const max = this.gaugeMax();
    const tones = ['bg-ok', 'bg-cyan', 'bg-warn', 'bg-bad'];
    return data.slab.slabs.map((edge, index) => {
      const until = edge.untilKwh ?? max;
      const used = Math.min(Math.max(data.slab.currentKwh - edge.fromKwh, 0), until - edge.fromKwh);
      return { width: (used > 0 ? used : 0) / max * 100 + 0, used: used > 0, tone: tones[index] ?? 'bg-bad' };
    }).concat([{ width: Math.max(0, 100 - data.slab.currentKwh / max * 100), used: false, tone: '' }]);
  });
  protected readonly forecastMarker = computed(() => {
    const projected = this.forecast()?.projectedKwh.p50;
    return projected ? Math.min(99.5, projected / this.gaugeMax() * 100) : null;
  });
  protected readonly slabMessage = computed(() => {
    const slab = this.overview()?.slab;
    if (!slab) return '';
    if (slab.kwhToNextSlab === null) return `In the top slab at ₹${slab.ratePerKwh}/kWh`;
    return `${kwh(slab.kwhToNextSlab)} left in slab ${slab.index} before ₹${slab.nextRatePerKwh}/kWh`;
  });

  protected readonly monthlyChart = computed(() => {
    const months = this.overview()?.monthly ?? [];
    return (c: ChartPalette) => ({
      grid: { left: 48, right: 56, top: 30, bottom: 28 },
      tooltip: { trigger: 'axis', ...tooltipStyle(c) },
      legend: { top: 0, right: 0, textStyle: { color: c.muted }, itemWidth: 12, itemHeight: 8 },
      xAxis: { type: 'category', data: months.map((m) => period(m.month, true)), ...axisStyle(c) },
      yAxis: [
        { type: 'value', name: 'kWh', nameTextStyle: { color: c.muted }, ...axisStyle(c) },
        { type: 'value', name: '₹', nameTextStyle: { color: c.muted }, ...axisStyle(c), splitLine: { show: false } },
      ],
      series: [
        { name: 'Imported kWh', type: 'bar', data: months.map((m) => m.importKwh), itemStyle: { color: c.brand, borderRadius: [4, 4, 0, 0] }, barMaxWidth: 26 },
        ...(months.some((m) => m.exportKwh > 0) ? [{ name: 'Solar export kWh', type: 'bar', data: months.map((m) => m.exportKwh), itemStyle: { color: c.ok, borderRadius: [4, 4, 0, 0] }, barMaxWidth: 26 }] : []),
        { name: 'Billed ₹', type: 'line', yAxisIndex: 1, smooth: true, data: months.map((m) => m.billedInr), itemStyle: { color: c.warn }, lineStyle: { width: 2.5 }, symbolSize: 6, connectNulls: true },
      ],
    });
  });

  protected readonly forecastChart = computed(() => {
    const forecast = this.forecast();
    return (c: ChartPalette) => {
      if (!forecast) return {};
      const base = forecast.importKwhToDate;
      const labels = ['To date'];
      const low = [base], mid = [base], high = [0];
      let l = base, m = base, h = base;
      for (const day of forecast.trajectory) {
        l += day.p10; m += day.p50; h += day.p90;
        labels.push(day.day.slice(8));
        low.push(+l.toFixed(1)); mid.push(+m.toFixed(1)); high.push(+(h - l).toFixed(1));
      }
      return {
        grid: { left: 44, right: 12, top: 16, bottom: 28 },
        tooltip: { trigger: 'axis', ...tooltipStyle(c) },
        xAxis: { type: 'category', data: labels, boundaryGap: false, ...axisStyle(c) },
        yAxis: { type: 'value', name: 'kWh', ...axisStyle(c) },
        series: [
          { name: 'Low', type: 'line', stack: 'band', data: low, lineStyle: { opacity: 0 }, symbol: 'none' },
          { name: 'Range', type: 'line', stack: 'band', data: high, lineStyle: { opacity: 0 }, symbol: 'none', areaStyle: { color: c.brand, opacity: 0.15 } },
          { name: 'Projected', type: 'line', data: mid, smooth: true, symbol: 'none', lineStyle: { color: c.brand, width: 2.5, type: 'dashed' } },
        ],
      };
    };
  });

  protected readonly hourlyChart = computed(() => {
    const hours = this.overview()?.hourlyProfile ?? [];
    return (c: ChartPalette) => {
      const colour: Record<string, string> = { SOLAR_OFF_PEAK: c.ok, NORMAL: c.brand, EVENING_PEAK: c.bad };
      return {
        grid: { left: 44, right: 12, top: 30, bottom: 28 },
        tooltip: { trigger: 'axis', ...tooltipStyle(c),
          formatter: (items: { dataIndex: number }[]) => {
            const hour = hours[items[0].dataIndex];
            return `${String(hour.hour).padStart(2, '0')}:00 · ${WINDOW_LABEL[hour.window]}\n${kwh(hour.importKwh)}`;
          } },
        xAxis: { type: 'category', data: hours.map((h) => `${h.hour}`), ...axisStyle(c) },
        yAxis: { type: 'value', name: 'kWh', ...axisStyle(c) },
        series: [{ type: 'bar', barCategoryGap: '18%',
          data: hours.map((h) => ({ value: h.importKwh, itemStyle: { color: colour[h.window] ?? c.brand, borderRadius: [3, 3, 0, 0] } })) }],
      };
    };
  });

  constructor() {
    effect(() => {
      const meter = this.customer.meter();
      if (meter) void this.load(meter.id);
    });
  }

  async load(meterId = this.customer.meter()?.id): Promise<void> {
    if (!meterId) return;
    this.error.set(null);
    this.overview.set(null);
    this.forecast.set(null);
    // The forecast may take longer than the overview, so each panel renders as soon as its data arrives.
    firstValueFrom(this.http.get<Forecast>(`/api/v1/ai/forecast/${meterId}`))
      .then((forecast) => this.forecast.set(forecast)).catch(() => undefined);
    try {
      this.overview.set(await firstValueFrom(
        this.http.get<ConsumerOverview>(`/api/v1/analytics/consumer/overview?meterId=${meterId}`)));
    } catch (error) {
      this.error.set(problemMessage(error, 'Your dashboard could not be loaded.'));
    }
  }

  protected plural(count: number, noun: string): string {
    return `${count} ${noun}${count === 1 ? '' : 's'}`;
  }

  protected copilotHint(): void {
    this.copilot.open.set(true);
  }
}
