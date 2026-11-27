import { HttpClient } from '@angular/common/http';
import { Component, computed, effect, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { LucideAlertTriangle, LucideHeadset, LucideIndianRupee, LucideReceipt, LucideSiren, LucideSparkles, LucideUsers,
  LucideZap } from '@lucide/angular';
import { firstValueFrom } from 'rxjs';
import { AuthStore } from '../../core/auth';
import { CustomerStore } from '../../core/customer';
import { ConsumerOverview, Forecast, PeerBenchmark } from '../../core/models';
import { NotificationStore, problemMessage } from '../../core/ui-state';
import { CopilotState } from '../../layout/copilot';
import { ChartPalette, EChart, axisStyle, tooltipStyle } from '../../shared/echart';
import { FORMAT_PIPES, inr, kwh, period } from '../../shared/format';
import { EmptyState, ErrorBanner, KpiCard } from '../../shared/ui';

const WINDOW_LABEL: Record<string, string> = {
  SOLAR_OFF_PEAK: 'Solar hours', NORMAL: 'Normal', EVENING_PEAK: 'Evening peak',
};

/** Formats an ISO date (YYYY-MM-DD) as "27 Nov". */
function dayLabel(iso: string): string {
  const [, month, day] = iso.split('-').map(Number);
  return `${day} ${['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'][month - 1]}`;
}

@Component({
  imports: [RouterLink, EChart, KpiCard, ErrorBanner, EmptyState, ...FORMAT_PIPES, LucideSiren, LucideSparkles,
    LucideAlertTriangle, LucideReceipt, LucideIndianRupee, LucideZap, LucideHeadset, LucideUsers],
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
      <app-kpi label="Projected bill this month" [loading]="forecastState() === 'loading'"
               [value]="(forecast()?.projectedBillInr?.p50 ?? null) | inr"
               [hint]="forecastState() === 'unavailable' ? forecastError() : forecastHint()"
               [tone]="overBudget() ? 'bad' : 'muted'">
        <svg kpi-icon lucideReceipt [size]="16" aria-hidden="true"></svg>
        @if (overBudget()) {
          <span class="badge mt-3 bg-bad-soft text-bad"><svg lucideAlertTriangle [size]="12" aria-hidden="true"></svg>
            Bill-shock: above your {{ overview()?.monthlyBudgetInr | inr: true }} budget</span>
        }
      </app-kpi>
      <app-kpi label="Amount due" [loading]="!overview()" [value]="due() | inr" [tone]="(overview()?.receivables?.overdueBills ?? 0) > 0 ? 'bad' : 'muted'"
               [hint]="dueHint()"><svg kpi-icon lucideIndianRupee [size]="16" aria-hidden="true"></svg></app-kpi>
      <app-kpi label="Used this cycle" [loading]="!overview()" [value]="(overview()?.cycle?.importKwhToDate ?? null) | kwh"
               [hint]="usageHint()" [tone]="(overview()?.cycle?.changePct ?? 0) > 10 ? 'warn' : 'ok'"><svg kpi-icon lucideZap [size]="16" aria-hidden="true"></svg></app-kpi>
      <app-kpi label="Support" [loading]="!overview()" [value]="plural(overview()?.openComplaints ?? 0, 'open ticket')"
               [hint]="plural(overview()?.outages?.length ?? 0, 'outage') + ' in your area'"><svg kpi-icon lucideHeadset [size]="16" aria-hidden="true"></svg></app-kpi>
    </section>

    @if (overview(); as data) {
      <div class="mt-4 grid gap-4 xl:grid-cols-3">
      <section [class]="data.peers ? 'card-pad flex flex-col xl:col-span-2' : 'card-pad flex flex-col xl:col-span-3'" aria-labelledby="slab-heading">
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
        <dl class="mt-6 grid grid-cols-2 gap-4 border-t border-line pt-4 text-sm sm:grid-cols-4 xl:mt-auto">
          <div><dt class="text-faint">Current rate</dt><dd class="num mt-0.5 text-lg font-semibold text-ink">₹{{ data.slab.ratePerKwh }}/kWh</dd></div>
          <div><dt class="text-faint">Next slab rate</dt>
            <dd class="num mt-0.5 text-lg font-semibold text-ink">{{ data.slab.nextRatePerKwh === null ? 'Top slab' : '₹' + data.slab.nextRatePerKwh + '/kWh' }}</dd></div>
          <div><dt class="text-faint">Until next slab</dt>
            <dd class="num mt-0.5 text-lg font-semibold text-ink">{{ data.slab.kwhToNextSlab === null ? '—' : (data.slab.kwhToNextSlab | kwh) }}</dd></div>
          <div><dt class="text-faint">Projected end of cycle</dt>
            <dd class="num mt-0.5 text-lg font-semibold text-ink">{{ (forecast()?.projectedKwh?.p50 ?? null) === null ? '—' : (forecast()!.projectedKwh.p50 | kwh) }}</dd></div>
        </dl>
      </section>
      @if (data.peers; as peer) {
        <section class="card-pad" aria-labelledby="peer-heading">
          <div class="flex items-start justify-between gap-3">
            <div>
              <h2 id="peer-heading" class="h-section">Compared with similar homes</h2>
              <p class="text-xs text-faint">Last 30 days · {{ peer.peers }} anonymised {{ peer.group }} meters</p>
            </div>
            <span class="inline-flex size-8 shrink-0 items-center justify-center rounded-lg bg-brand-soft text-brand">
              <svg lucideUsers [size]="16" aria-hidden="true"></svg></span>
          </div>
          @if (absValue(peer.differencePct) < 1) {
            <p class="mt-3 text-2xl font-semibold text-ink">About the same as the median</p>
          } @else {
            <p class="mt-3 text-sm" [class]="peer.differencePct > 0 ? 'text-warn' : 'text-ok'">
              <span class="num text-2xl font-semibold">{{ absPct(peer.differencePct) }}%</span>
              {{ peer.differencePct > 0 ? 'more' : 'less' }} than the median</p>
          }
          <div class="relative mt-5 h-2 rounded-full bg-sunken" role="img"
               [attr.aria-label]="'You used ' + peer.yourKwh + ' kWh; similar homes typically use ' + peer.p25Kwh + ' to ' + peer.p75Kwh + ' kWh, median ' + peer.medianKwh + ' kWh'">
            <span class="absolute inset-y-0 rounded-full bg-brand/25" [style.left.%]="peerScale(peer, peer.p25Kwh)"
                  [style.width.%]="peerScale(peer, peer.p75Kwh) - peerScale(peer, peer.p25Kwh)"></span>
            <span class="absolute -inset-y-1 w-0.5 rounded bg-muted" [style.left.%]="peerScale(peer, peer.medianKwh)"></span>
            <span class="absolute -top-1.5 size-5 -translate-x-1/2 rounded-full border-2 border-surface bg-brand shadow" [style.left.%]="peerScale(peer, peer.yourKwh)"></span>
          </div>
          <dl class="mt-4 grid grid-cols-3 gap-2 text-xs">
            <div><dt class="text-faint">You</dt><dd class="num font-medium text-ink">{{ peer.yourKwh | kwh }}</dd></div>
            <div><dt class="text-faint">Median</dt><dd class="num font-medium text-ink">{{ peer.medianKwh | kwh }}</dd></div>
            <div><dt class="text-faint">Typical range</dt><dd class="num font-medium text-ink">{{ round(peer.p25Kwh) }}–{{ round(peer.p75Kwh) }} kWh</dd></div>
          </dl>
          <p class="mt-3 text-sm text-muted">{{ peer.moreEfficientThanPct }}% of similar homes used more energy than you.</p>
        </section>
      }
      </div>

      <div class="mt-4 grid gap-4 xl:grid-cols-3">
        <section class="card-pad xl:col-span-2" aria-labelledby="monthly-heading">
          <h2 id="monthly-heading" class="h-section">Monthly usage and bills</h2>
          <app-chart class="mt-2" [options]="monthlyChart()" label="Monthly imported energy and billed amount for the last 12 months" />
        </section>
        <section class="card-pad" aria-labelledby="forecast-heading">
          <h2 id="forecast-heading" class="h-section">Cycle forecast</h2>
          <p class="text-xs text-faint">{{ forecast()?.model }}{{ forecast()?.source === 'fallback' ? ' (ML service unavailable)' : '' }}</p>
          @switch (forecastState()) {
            @case ('ready') {
              <app-chart class="mt-2" [options]="forecastChart()" label="Cumulative energy this cycle with forecast range" />
            }
            @case ('unavailable') {
              <app-empty class="mt-3 block" title="Forecast unavailable" [message]="forecastError()">
                <button type="button" class="btn-secondary btn-sm" (click)="loadForecast()">Try again</button>
              </app-empty>
            }
            @default { <div class="skeleton mt-3 h-[260px]"></div> }
          }
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
  /** Position of a kWh value on the peer bar, which spans the quartiles and your usage with some padding. */
  protected peerScale(peer: PeerBenchmark, value: number): number {
    const low = Math.min(peer.p25Kwh, peer.yourKwh);
    const high = Math.max(peer.p75Kwh, peer.yourKwh);
    const pad = Math.max((high - low) * 0.35, peer.medianKwh * 0.05, 1);
    const from = Math.max(0, low - pad);
    return Math.min(100, Math.max(0, ((value - from) / (high + pad - from)) * 100));
  }

  protected round(value: number): number {
    return Math.round(value);
  }

  protected absValue(value: number): number {
    return Math.abs(value);
  }

  protected absPct(value: number): string {
    return Math.abs(value).toFixed(Math.abs(value) < 10 ? 1 : 0);
  }

  protected readonly auth = inject(AuthStore);
  protected readonly notifications = inject(NotificationStore);
  private readonly customer = inject(CustomerStore);
  private readonly copilot = inject(CopilotState);
  private readonly http = inject(HttpClient);
  protected readonly overview = signal<ConsumerOverview | null>(null);
  protected readonly forecast = signal<Forecast | null>(null);
  protected readonly forecastState = signal<'loading' | 'ready' | 'unavailable'>('loading');
  protected readonly forecastError = signal('');
  protected readonly error = signal<string | null>(null);
  /** Increments per meter load so a slow response for a previous meter is ignored. */
  private request = 0;

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
      // The shaded band stacks "low" with the band width; keep the absolute high values for the tooltip.
      const low = [base], mid = [base], width = [0], high = [base];
      let l = base, m = base, h = base;
      for (const day of forecast.trajectory) {
        l += day.p10; m += day.p50; h += day.p90;
        labels.push(day.day.slice(8));
        low.push(+l.toFixed(1)); mid.push(+m.toFixed(1)); width.push(+(h - l).toFixed(1)); high.push(+h.toFixed(1));
      }
      const dates = ['To date', ...forecast.trajectory.map((day) => day.day)];
      return {
        grid: { left: 44, right: 12, top: 16, bottom: 28 },
        tooltip: { trigger: 'axis', ...tooltipStyle(c),
          formatter: (items: { dataIndex: number }[]) => {
            const index = items[0]?.dataIndex ?? 0;
            const heading = index === 0 ? 'So far this cycle' : `By ${dayLabel(dates[index])}`;
            return `${heading}\nHigh (P90): ${kwh(high[index])}\nLikely (P50): ${kwh(mid[index])}\nLow (P10): ${kwh(low[index])}`;
          } },
        xAxis: { type: 'category', data: labels, boundaryGap: false, ...axisStyle(c) },
        yAxis: { type: 'value', name: 'kWh', ...axisStyle(c) },
        series: [
          { name: 'Low', type: 'line', stack: 'band', data: low, lineStyle: { opacity: 0 }, symbol: 'none' },
          { name: 'Range', type: 'line', stack: 'band', data: width, lineStyle: { opacity: 0 }, symbol: 'none', areaStyle: { color: c.brand, opacity: 0.15 } },
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
    const request = ++this.request;
    this.error.set(null);
    this.overview.set(null);
    // The forecast may take longer than the overview, so each panel renders as soon as its data arrives.
    void this.loadForecast(meterId, request);
    try {
      const overview = await firstValueFrom(
        this.http.get<ConsumerOverview>(`/api/v1/analytics/consumer/overview?meterId=${meterId}`));
      if (request === this.request) this.overview.set(overview);
    } catch (error) {
      if (request === this.request) this.error.set(problemMessage(error, 'Your dashboard could not be loaded.'));
    }
  }

  /** Loads the cycle forecast; a failure shows an explained empty state instead of a permanent skeleton. */
  async loadForecast(meterId = this.customer.meter()?.id, request = this.request): Promise<void> {
    if (!meterId) return;
    this.forecast.set(null);
    this.forecastState.set('loading');
    try {
      const forecast = await firstValueFrom(this.http.get<Forecast>(`/api/v1/ai/forecast/${meterId}`));
      if (request !== this.request) return;
      this.forecast.set(forecast);
      this.forecastState.set('ready');
    } catch (error) {
      if (request !== this.request) return;
      this.forecastError.set(problemMessage(error, 'The forecast needs at least 14 days of readings.'));
      this.forecastState.set('unavailable');
    }
  }

  protected plural(count: number, noun: string): string {
    return `${count} ${noun}${count === 1 ? '' : 's'}`;
  }

  protected copilotHint(): void {
    this.copilot.open.set(true);
  }
}
