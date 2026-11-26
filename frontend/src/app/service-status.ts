import { HttpClient } from '@angular/common/http';
import { Component, DestroyRef, OnInit, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { timeout } from 'rxjs';

type ConnectionState = 'checking' | 'connected' | 'unavailable';

@Component({
  template: `
    <main class="mx-auto flex min-h-dvh max-w-3xl flex-col justify-center px-6 py-16 sm:px-12">
      <p class="mb-4 text-sm font-semibold text-[var(--accent)]">PowerBill</p>
      <h1 class="text-5xl font-bold tracking-tight sm:text-7xl">GridIQ</h1>
      <p class="mt-4 max-w-md text-lg text-[var(--muted)]">
        Smart metering, electricity billing, and utility operations.
      </p>
      <section
        class="mt-10 max-w-md rounded-xl border border-[var(--border)] bg-[var(--surface)] p-5 sm:p-6"
        aria-labelledby="connection-heading"
      >
        <h2 id="connection-heading" class="mb-3 text-base font-semibold">Core API connection</h2>
        <p role="status" class="text-sm">
          @switch (connection()) {
            @case ('checking') {
              <span class="text-[var(--muted)]">Checking connection…</span>
            }
            @case ('connected') {
              <span class="font-medium text-[var(--ok)]">Connected</span>
            }
            @case ('unavailable') {
              <span class="font-medium text-[var(--problem)]">Core API unavailable.</span>
            }
          }
        </p>
        <button
          type="button"
          class="mt-5 cursor-pointer rounded-md border border-[var(--border)] px-3 py-2 text-sm font-medium text-[var(--accent)] disabled:cursor-wait disabled:opacity-60"
          [disabled]="connection() === 'checking'"
          (click)="checkConnection()"
        >
          Check connection
        </button>
      </section>
    </main>
  `,
})
export class ServiceStatus implements OnInit {
  private readonly http = inject(HttpClient);
  private readonly destroyRef = inject(DestroyRef);
  protected readonly connection = signal<ConnectionState>('checking');

  ngOnInit(): void {
    this.checkConnection();
  }

  protected checkConnection(): void {
    this.connection.set('checking');
    this.http
      .get<{ status: string; service: string }>('/api/v1/status')
      .pipe(timeout(5_000), takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (result) =>
          this.connection.set(
            result?.status === 'UP' && result.service === 'gridiq-core'
              ? 'connected'
              : 'unavailable',
          ),
        error: () => this.connection.set('unavailable'),
      });
  }
}
