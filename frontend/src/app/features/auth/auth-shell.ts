import { Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { LucideActivity, LucideReceipt, LucideSparkles, LucideZap } from '@lucide/angular';

@Component({
  imports: [RouterOutlet, LucideZap, LucideActivity, LucideReceipt, LucideSparkles],
  template: `
    <div class="grid min-h-dvh lg:grid-cols-[1.05fr_1fr]">
      <section class="relative isolate hidden overflow-hidden bg-[#0b1530] p-12 text-white lg:flex lg:flex-col" aria-hidden="true">
        <!-- Decorative only: a faint grid, two soft glows and a load curve, all from utility classes and inline SVG markup
             so the strict style-src policy holds. The panel is always dark, so it reads the same in light and dark themes. -->
        <div class="pointer-events-none absolute inset-0 -z-10" aria-hidden="true">
          <div class="absolute inset-0 bg-[linear-gradient(to_right,rgb(148_163_184/0.09)_1px,transparent_1px),linear-gradient(to_bottom,rgb(148_163_184/0.09)_1px,transparent_1px)] bg-size-[44px_44px] [mask-image:radial-gradient(ellipse_75%_65%_at_35%_45%,#000_20%,transparent_100%)]"></div>
          <div class="absolute -right-40 -top-40 size-[32rem] rounded-full bg-[#2563eb]/30 blur-3xl"></div>
          <div class="absolute -bottom-56 -left-32 size-[28rem] rounded-full bg-[#4fd1e8]/10 blur-3xl"></div>
          <svg class="absolute inset-x-0 bottom-28 h-28 w-full text-[#4fd1e8]/25" viewBox="0 0 600 120" preserveAspectRatio="none" fill="none">
            <path d="M0 98 C50 98 70 62 110 62 S170 88 210 84 S270 34 320 36 S390 76 430 70 S510 22 600 26"
                  stroke="currentColor" stroke-width="1.5" vector-effect="non-scaling-stroke" />
          </svg>
        </div>
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
