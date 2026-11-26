import { Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { LucideActivity, LucideReceipt, LucideSparkles, LucideZap } from '@lucide/angular';

@Component({
  imports: [RouterOutlet, LucideZap, LucideActivity, LucideReceipt, LucideSparkles],
  template: `
    <div class="grid min-h-dvh lg:grid-cols-[1.05fr_1fr]">
      <section class="relative hidden overflow-hidden bg-[#0b1530] p-12 text-white lg:flex lg:flex-col" aria-hidden="true">
        <div class="flex items-center gap-2.5">
          <span class="inline-flex size-9 items-center justify-center rounded-lg bg-[#2563eb]">
            <svg lucideZap [size]="20"></svg>
          </span>
          <span class="text-lg font-bold">PowerBill GridIQ</span>
        </div>
        <div class="my-auto max-w-md">
          <h1 class="text-4xl font-semibold leading-tight tracking-tight">Every unit, every rupee, explained.</h1>
          <p class="mt-4 text-[#b8c6e3]">Smart meter billing for Indian distribution utilities, with transparent slab,
            time-of-day and solar net-metering bills.</p>
          <ul class="mt-10 space-y-5 text-sm text-[#dbe5f7]">
            <li class="flex gap-3"><svg lucideActivity [size]="20" class="shrink-0 text-[#4fd1e8]"></svg>
              Hourly AMI telemetry with validation, estimation and anomaly detection</li>
            <li class="flex gap-3"><svg lucideReceipt [size]="20" class="shrink-0 text-[#4ade9f]"></svg>
              Immutable itemized invoices and atomic multi-bill payments</li>
            <li class="flex gap-3"><svg lucideSparkles [size]="20" class="shrink-0 text-[#fbbf4d]"></svg>
              Bill-shock forecasts and a Copilot grounded in your own ledger</li>
          </ul>
        </div>
        <p class="text-xs text-[#8597b3]">Demonstration tariffs are illustrative and not issued by any regulator.</p>
      </section>
      <main class="flex items-center justify-center px-4 py-10 sm:px-8">
        <div class="w-full max-w-md">
          <div class="mb-8 flex items-center gap-2.5 lg:hidden">
            <span class="inline-flex size-8 items-center justify-center rounded-lg bg-brand text-on-brand">
              <svg lucideZap [size]="18" aria-hidden="true"></svg>
            </span>
            <span class="font-bold">PowerBill GridIQ</span>
          </div>
          <router-outlet />
        </div>
      </main>
    </div>
  `,
})
export class AuthShell {}
