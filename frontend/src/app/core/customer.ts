import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { ConsumerDetails } from './models';

/** The signed-in customer's account, premises and meters, plus the meter selected in the top bar. */
@Injectable({ providedIn: 'root' })
export class CustomerStore {
  private readonly http = inject(HttpClient);
  readonly details = signal<ConsumerDetails | null>(null);
  readonly meterId = signal<string | null>(this.storedMeter());
  readonly meters = computed(() => this.details()?.meters ?? []);
  readonly meter = computed(() => {
    const meters = this.meters();
    return meters.find((meter) => meter.id === this.meterId()) ?? (meters.length ? meters[0] : null);
  });
  readonly premiseFor = (premiseId: string) => this.details()?.premises.find((premise) => premise.id === premiseId);
  private loading: Promise<ConsumerDetails> | null = null;

  load(force = false): Promise<ConsumerDetails> {
    if (!force && this.details()) return Promise.resolve(this.details()!);
    this.loading ??= firstValueFrom(this.http.get<ConsumerDetails>('/api/v1/consumers/me'))
      .then((details) => {
        this.details.set(details);
        if (!details.meters.some((meter) => meter.id === this.meterId())) {
          this.select(details.meters[0]?.id ?? null);
        }
        return details;
      })
      .finally(() => (this.loading = null));
    return this.loading;
  }

  select(id: string | null): void {
    this.meterId.set(id);
    try {
      if (id) sessionStorage.setItem('gridiq-meter', id);
    } catch {
      /* storage may be unavailable */
    }
  }

  clear(): void {
    this.details.set(null);
  }

  private storedMeter(): string | null {
    try {
      return sessionStorage.getItem('gridiq-meter');
    } catch {
      return null;
    }
  }
}
