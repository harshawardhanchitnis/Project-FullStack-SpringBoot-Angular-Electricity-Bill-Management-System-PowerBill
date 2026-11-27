import { HttpClient } from '@angular/common/http';
import { Injectable, computed, effect, inject, signal, untracked } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { AuthStore } from './auth';
import { ConsumerDetails } from './models';

const METER_KEY = 'gridiq-meter';

/**
 * The signed-in customer's account, premises and meters, plus the meter selected in the top bar. Everything is
 * dropped when the signed-in user changes, so one customer's account can never leak into the next session.
 */
@Injectable({ providedIn: 'root' })
export class CustomerStore {
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthStore);
  readonly details = signal<ConsumerDetails | null>(null);
  readonly meterId = signal<string | null>(null);
  readonly meters = computed(() => this.details()?.meters ?? []);
  readonly meter = computed(() => {
    const meters = this.meters();
    return meters.find((meter) => meter.id === this.meterId()) ?? (meters.length ? meters[0] : null);
  });
  readonly premiseFor = (premiseId: string) => this.details()?.premises.find((premise) => premise.id === premiseId);
  private loading: Promise<ConsumerDetails> | null = null;
  private owner: string | null = null;
  private generation = 0;

  constructor() {
    effect(() => {
      const userId = this.auth.user()?.id ?? null;
      untracked(() => {
        if (userId === this.owner) return;
        // A different user (or none) now owns the tab: forget the previous account and, on sign-out, its meter.
        if (this.owner !== null) this.clear(userId === null);
        this.owner = userId;
      });
    });
  }

  load(force = false): Promise<ConsumerDetails> {
    if (!force && this.details()) return Promise.resolve(this.details()!);
    const generation = this.generation;
    this.loading ??= firstValueFrom(this.http.get<ConsumerDetails>('/api/v1/consumers/me'))
      .then((details) => {
        if (generation !== this.generation) return details; // the user changed while this request was in flight
        this.details.set(details);
        const stored = this.storedMeter(details.account.id);
        const preferred = details.meters.find((meter) => meter.id === (this.meterId() ?? stored));
        this.select(preferred?.id ?? details.meters[0]?.id ?? null);
        return details;
      })
      .finally(() => (this.loading = null));
    return this.loading;
  }

  select(id: string | null): void {
    this.meterId.set(id);
    const account = this.details()?.account.id;
    try {
      if (id && account) sessionStorage.setItem(`${METER_KEY}:${account}`, id);
    } catch {
      /* storage may be unavailable */
    }
  }

  /** Drops the cached account. With `forgetMeter`, also removes every remembered meter selection in this tab. */
  clear(forgetMeter = false): void {
    this.generation++;
    this.loading = null;
    this.details.set(null);
    this.meterId.set(null);
    if (!forgetMeter) return;
    try {
      for (const key of Object.keys(sessionStorage)) {
        if (key === METER_KEY || key.startsWith(`${METER_KEY}:`)) sessionStorage.removeItem(key);
      }
    } catch {
      /* storage may be unavailable */
    }
  }

  private storedMeter(account: string): string | null {
    try {
      return sessionStorage.getItem(`${METER_KEY}:${account}`);
    } catch {
      return null;
    }
  }
}
