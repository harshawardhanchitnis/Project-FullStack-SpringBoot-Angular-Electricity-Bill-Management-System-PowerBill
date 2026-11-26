import { HttpClient } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { LucideCopy } from '@lucide/angular';
import { firstValueFrom } from 'rxjs';
import { TariffPlan } from '../../core/models';
import { Toasts, problemMessage } from '../../core/ui-state';
import { FORMAT_PIPES } from '../../shared/format';
import { Dialog, ErrorBanner, StatusBadge } from '../../shared/ui';

function tomorrow(): string {
  const now = new Date(Date.now() + 5.5 * 3600 * 1000);
  now.setUTCDate(now.getUTCDate() + 1);
  return now.toISOString().slice(0, 10);
}

@Component({
  imports: [FormsModule, Dialog, ErrorBanner, StatusBadge, ...FORMAT_PIPES, LucideCopy],
  template: `
    <h1 class="h-page">Tariff studio</h1>
    <p class="mt-1 text-sm text-muted">Effective-dated, versioned tariffs. Active versions are never edited; publish a new version instead.</p>
    @if (error()) { <div class="mt-4"><app-error [message]="error()!" (retry)="load()" /></div> }
    <div class="grid grid-cols-1 items-start gap-x-4 lg:grid-cols-2 2xl:grid-cols-3">
    @for (group of groups(); track group.type) {
      <div class="min-w-0">
      <h2 class="h-section mt-8">{{ group.type | humanize }}</h2>
      <div class="mt-3 grid gap-4">
        @for (plan of group.plans; track plan.id) {
          <section class="card-pad">
            <div class="flex flex-wrap items-start gap-2">
              <div class="min-w-0 flex-1">
                <p class="font-semibold">{{ plan.name }}</p>
                <p class="text-sm text-muted">{{ plan.code }} · version {{ plan.version }} · from {{ plan.effectiveFrom | day }}{{ plan.effectiveTo ? ' to ' + label(plan.effectiveTo) : '' }}</p>
              </div>
              <app-badge [status]="plan.active ? 'ACTIVE' : 'PENDING'" [label]="plan.active ? 'Active' : 'Draft'" />
            </div>
            <dl class="mt-4 grid grid-cols-3 gap-2 text-sm">
              <div class="rounded-lg bg-sunken p-2"><dt class="text-xs text-faint">Fixed / kW</dt><dd class="num font-medium">₹{{ plan.fixedChargePerKw }}</dd></div>
              <div class="rounded-lg bg-sunken p-2"><dt class="text-xs text-faint">FAC / kWh</dt><dd class="num font-medium">₹{{ plan.facPerKwh }}</dd></div>
              <div class="rounded-lg bg-sunken p-2"><dt class="text-xs text-faint">Duty</dt><dd class="num font-medium">{{ plan.electricityDutyPct }}%</dd></div>
            </dl>
            <table class="mt-4 w-full text-sm">
              <caption class="sr-only">Energy slabs</caption>
              <thead><tr class="text-left text-xs text-faint"><th class="py-1 font-medium">Slab</th><th class="py-1 font-medium">Units</th><th class="py-1 text-right font-medium">Rate</th></tr></thead>
              <tbody>
                @for (slab of plan.slabs; track $index) {
                  <tr class="border-t border-line"><td class="py-1.5">{{ $index + 1 }}</td><td class="num py-1.5">{{ slab.fromKwh }}–{{ slab.untilKwh ?? '∞' }} kWh</td><td class="num py-1.5 text-right">₹{{ slab.ratePerKwh }}/kWh</td></tr>
                }
              </tbody>
            </table>
            <div class="mt-3 flex flex-wrap gap-1.5">
              @for (window of plan.windows; track window.startHour) {
                <span class="badge" [class]="window.name === 'EVENING_PEAK' ? 'bg-bad-soft text-bad' : window.name === 'SOLAR_OFF_PEAK' ? 'bg-ok-soft text-ok' : 'bg-sunken text-muted'">
                  {{ window.startHour }}–{{ window.endHour }}h × {{ window.rateMultiplier }}</span>
              }
            </div>
            <div class="mt-4 flex gap-2">
              <button type="button" class="btn-secondary btn-sm" (click)="draftFrom(plan)"><svg lucideCopy [size]="14" aria-hidden="true"></svg> New version</button>
              @if (!plan.active) {
                <button type="button" class="btn-primary btn-sm" [disabled]="busy()" (click)="activate(plan)">Activate</button>
              }
            </div>
          </section>
        }
      </div>
      </div>
    }
    </div>

    <app-dialog [open]="!!draft()" heading="New tariff version" [subheading]="draft() ? draft()!.code + ' · ' + draft()!.connectionType : ''" (closed)="draft.set(null)">
      @if (draft(); as d) {
        <form id="tariff-form" class="space-y-4 text-sm" (ngSubmit)="save()">
          <div class="grid gap-3 sm:grid-cols-2">
            <div class="sm:col-span-2"><label class="label" for="t-name">Name</label><input id="t-name" class="input" name="name" [(ngModel)]="d.name" required /></div>
            <div><label class="label" for="t-from">Effective from</label><input id="t-from" class="input" type="date" name="from" [(ngModel)]="d.effectiveFrom" [min]="minDate" required /></div>
            <div><label class="label" for="t-fixed">Fixed charge per kW (₹)</label><input id="t-fixed" class="input num" type="number" step="0.01" name="fixed" [(ngModel)]="d.fixedChargePerKw" /></div>
            <div><label class="label" for="t-fac">FAC per kWh (₹)</label><input id="t-fac" class="input num" type="number" step="0.01" name="fac" [(ngModel)]="d.facPerKwh" /></div>
            <div><label class="label" for="t-duty">Electricity duty (%)</label><input id="t-duty" class="input num" type="number" step="0.1" name="duty" [(ngModel)]="d.electricityDutyPct" /></div>
          </div>
          <fieldset>
            <legend class="label">Slab rates (₹/kWh)</legend>
            <div class="grid grid-cols-2 gap-2 sm:grid-cols-4">
              @for (slab of d.slabs; track $index) {
                <div><label class="text-xs text-faint" [for]="'slab-' + $index">{{ slab.fromKwh }}–{{ slab.untilKwh ?? '∞' }} kWh</label>
                  <input class="input num" type="number" step="0.01" [id]="'slab-' + $index" [name]="'slab-' + $index" [(ngModel)]="slab.ratePerKwh" /></div>
              }
            </div>
          </fieldset>
          <fieldset>
            <legend class="label">Time-of-day multipliers</legend>
            <div class="grid grid-cols-2 gap-2 sm:grid-cols-3">
              @for (window of d.windows; track $index) {
                <div><label class="text-xs text-faint" [for]="'window-' + $index">{{ window.name | humanize }} {{ window.startHour }}–{{ window.endHour }}h</label>
                  <input class="input num" type="number" step="0.01" min="0" [id]="'window-' + $index" [name]="'window-' + $index" [(ngModel)]="window.rateMultiplier" /></div>
              }
            </div>
          </fieldset>
          @if (formError()) { <p role="alert" class="rounded-lg bg-bad-soft p-3">{{ formError() }}</p> }
        </form>
      }
      <div dialog-footer class="flex justify-end gap-2 border-t border-line p-4">
        <button type="button" class="btn-secondary" (click)="draft.set(null)">Cancel</button>
        <button type="submit" form="tariff-form" class="btn-primary" [disabled]="busy()">Save draft</button>
      </div>
    </app-dialog>
  `,
})
export class Tariffs {
  private readonly http = inject(HttpClient);
  private readonly toasts = inject(Toasts);
  protected readonly plans = signal<TariffPlan[]>([]);
  protected readonly error = signal<string | null>(null);
  protected readonly draft = signal<TariffPlan | null>(null);
  protected readonly busy = signal(false);
  protected readonly formError = signal<string | null>(null);
  protected readonly minDate = tomorrow();
  protected readonly groups = computed(() => {
    const types = [...new Set(this.plans().map((plan) => plan.connectionType))];
    return types.map((type) => ({ type, plans: this.plans().filter((plan) => plan.connectionType === type) }));
  });

  constructor() {
    void this.load();
  }

  protected label(value: string): string {
    return new Date(`${value}T00:00:00`).toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: 'numeric' });
  }

  async load(): Promise<void> {
    try {
      this.plans.set(await firstValueFrom(this.http.get<TariffPlan[]>('/api/v1/ops/tariffs')));
    } catch (error) {
      this.error.set(problemMessage(error, 'Tariffs could not be loaded.'));
    }
  }

  protected draftFrom(plan: TariffPlan): void {
    this.formError.set(null);
    this.draft.set({ ...structuredClone(plan), name: plan.name, effectiveFrom: this.minDate, effectiveTo: null });
  }

  protected async save(): Promise<void> {
    const d = this.draft();
    if (!d) return;
    this.busy.set(true);
    try {
      await firstValueFrom(this.http.post<TariffPlan>('/api/v1/ops/tariffs', {
        code: d.code, name: d.name, connectionType: d.connectionType, effectiveFrom: d.effectiveFrom, effectiveTo: null,
        fixedChargePerKw: d.fixedChargePerKw, facPerKwh: d.facPerKwh, electricityDutyPct: d.electricityDutyPct, gstPct: d.gstPct,
        solarExportRatePerKwh: d.solarExportRatePerKwh, solarPolicy: d.solarPolicy, lateFeeDailyPct: d.lateFeeDailyPct,
        lateFeeCapPct: d.lateFeeCapPct, graceDays: d.graceDays, slabs: d.slabs, windows: d.windows,
      }));
      this.draft.set(null);
      this.toasts.success('Draft saved. Activate it to publish.');
      await this.load();
    } catch (error) {
      this.formError.set(problemMessage(error));
    } finally {
      this.busy.set(false);
    }
  }

  protected async activate(plan: TariffPlan): Promise<void> {
    if (!confirm(`Activate ${plan.code} version ${plan.version} from ${plan.effectiveFrom}? The previous version ends that day.`)) return;
    this.busy.set(true);
    try {
      await firstValueFrom(this.http.patch(`/api/v1/ops/tariffs/${plan.id}/activate`, null));
      this.toasts.success('Tariff version activated.');
      await this.load();
    } catch (error) {
      this.toasts.error(error);
    } finally {
      this.busy.set(false);
    }
  }
}
