import { Component, ElementRef, computed, inject, input, signal, viewChild } from '@angular/core';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { toSignal } from '@angular/core/rxjs-interop';
import { filter, map } from 'rxjs';
import {
  LucideActivity, LucideBell, LucideCalculator, LucideChartLine, LucideDynamicIcon, LucideGauge, LucideHeadset,
  LucideLifeBuoy, LucideLogOut, LucideMenu, LucideMonitor, LucideMoon, LucideReceipt, LucideScrollText,
  LucideSearch, LucideShieldCheck, LucideSparkles, LucideSun, LucideUser, LucideUsers, LucideX, LucideZap,
} from '@lucide/angular';
import { AuthStore } from '../core/auth';
import { CustomerStore } from '../core/customer';
import { Role } from '../core/models';
import { NotificationStore, ThemeStore } from '../core/ui-state';
import { HumanizePipe, RelativePipe } from '../shared/format';
import { CopilotDrawer, CopilotState } from './copilot';
import { CommandPalette } from './palette';

interface NavItem {
  label: string;
  path: string;
  icon: unknown;
  roles?: Role[];
}

const CUSTOMER_NAV: NavItem[] = [
  { label: 'Dashboard', path: '/app/dashboard', icon: LucideGauge },
  { label: 'Energy & simulator', path: '/app/energy', icon: LucideChartLine },
  { label: 'Bills & payments', path: '/app/bills', icon: LucideReceipt },
  { label: 'Support & outages', path: '/app/support', icon: LucideLifeBuoy },
  { label: 'Account & meters', path: '/app/account', icon: LucideUser },
];

const OPS_NAV: NavItem[] = [
  { label: 'Executive overview', path: '/ops/dashboard', icon: LucideGauge },
  { label: 'Consumers & meters', path: '/ops/consumers', icon: LucideUsers,
    roles: ['BILLING_OFFICER', 'SUPPORT_AGENT', 'SUPER_ADMIN'] },
  { label: 'Billing & runs', path: '/ops/billing', icon: LucideReceipt,
    roles: ['BILLING_OFFICER', 'SUPPORT_AGENT', 'SUPER_ADMIN'] },
  { label: 'Tariff studio', path: '/ops/tariffs', icon: LucideCalculator, roles: ['BILLING_OFFICER', 'SUPER_ADMIN'] },
  { label: 'VEE & anomalies', path: '/ops/anomalies', icon: LucideActivity,
    roles: ['BILLING_OFFICER', 'FIELD_ENGINEER', 'SUPER_ADMIN'] },
  { label: 'Support desk & outages', path: '/ops/support', icon: LucideHeadset,
    roles: ['SUPPORT_AGENT', 'FIELD_ENGINEER', 'SUPER_ADMIN', 'BILLING_OFFICER'] },
  { label: 'Audit trail', path: '/ops/audit', icon: LucideScrollText, roles: ['SUPER_ADMIN'] },
  { label: 'Staff & access', path: '/ops/users', icon: LucideShieldCheck, roles: ['SUPER_ADMIN'] },
];

@Component({
  selector: 'app-shell',
  imports: [
    RouterOutlet, RouterLink, RouterLinkActive, LucideDynamicIcon, LucideMenu, LucideX, LucideBell, LucideSun,
    LucideMoon, LucideMonitor, LucideLogOut, LucideSparkles, LucideZap, LucideSearch, HumanizePipe, RelativePipe,
    CopilotDrawer, CommandPalette,
  ],
  host: { '(document:click)': 'onDocumentClick($event)', '(document:keydown.escape)': 'bellOpen.set(false)' },
  template: `
    <a href="#main" class="sr-only focus:not-sr-only focus:fixed focus:left-3 focus:top-3 focus:z-[60] focus:rounded-lg focus:bg-surface focus:px-4 focus:py-2">
      Skip to content
    </a>
    <div class="flex min-h-dvh">
      @if (menuOpen()) {
        <div class="fixed inset-0 z-30 bg-slate-950/40 lg:hidden" aria-hidden="true" (click)="menuOpen.set(false)"></div>
      }
      <aside
        class="fixed inset-y-0 left-0 z-40 flex w-64 flex-col border-r border-line bg-surface transition-transform lg:sticky lg:top-0 lg:h-dvh lg:translate-x-0"
        [class.-translate-x-full]="!menuOpen()" aria-label="Primary"
      >
        <div class="flex h-16 items-center gap-2.5 border-b border-line px-5">
          <span class="inline-flex size-8 items-center justify-center rounded-lg bg-brand text-on-brand">
            <svg lucideZap [size]="18" aria-hidden="true"></svg>
          </span>
          <div class="leading-tight">
            <p class="text-sm font-bold text-ink">PowerBill GridIQ</p>
            <p class="text-xs text-faint">{{ portal() === 'customer' ? 'Customer portal' : 'Utility operations' }}</p>
          </div>
          <button type="button" class="icon-btn ml-auto lg:hidden" aria-label="Close menu" (click)="menuOpen.set(false)">
            <svg lucideX [size]="18" aria-hidden="true"></svg>
          </button>
        </div>
        <nav class="flex-1 space-y-1 overflow-y-auto p-3">
          @for (item of nav(); track item.path) {
            <a class="nav-link" [routerLink]="item.path" routerLinkActive="active" ariaCurrentWhenActive="page"
               (click)="menuOpen.set(false)">
              <svg [lucideIcon]="$any(item.icon)" [size]="18" aria-hidden="true"></svg>
              {{ item.label }}
            </a>
          }
        </nav>
        <div class="border-t border-line p-4 text-sm">
          <p class="truncate font-medium text-ink">{{ auth.user()?.fullName }}</p>
          <p class="truncate text-xs text-faint">{{ auth.user()?.role | humanize }}</p>
          <button type="button" class="btn-ghost btn-sm mt-3 -ml-2" (click)="auth.logout()">
            <svg lucideLogOut [size]="15" aria-hidden="true"></svg> Sign out
          </button>
        </div>
      </aside>

      <div class="flex min-w-0 flex-1 flex-col">
        <header class="sticky top-0 z-20 flex h-16 items-center gap-2 border-b border-line bg-surface/90 px-3 backdrop-blur sm:px-6">
          <button type="button" class="icon-btn lg:hidden" aria-label="Open menu" (click)="menuOpen.set(true)">
            <svg lucideMenu [size]="20" aria-hidden="true"></svg>
          </button>
          @if (portal() === 'customer' && customer.meters().length > 1) {
            <label class="sr-only" for="meter-select">Meter</label>
            <select id="meter-select" class="input min-w-0 flex-1 sm:w-auto sm:max-w-xs sm:flex-none" (change)="selectMeter($event)">
              @for (meter of customer.meters(); track meter.id) {
                <option [value]="meter.id" [selected]="meter.id === customer.meter()?.id">{{ meter.meterSerial }} · {{ customer.premiseFor(meter.premiseId)?.label }}</option>
              }
            </select>
          }
          <div class="ml-auto flex shrink-0 items-center gap-1">
            <button type="button" class="btn-secondary btn-sm hidden md:inline-flex" (click)="paletteOpen.set(true)"
                    aria-keyshortcuts="Control+K Meta+K">
              <svg lucideSearch [size]="15" aria-hidden="true"></svg> Search…
              <kbd class="ml-2 rounded border border-line px-1.5 text-[10px] text-faint">Ctrl K</kbd>
            </button>
            <button type="button" class="icon-btn md:hidden" aria-label="Search" (click)="paletteOpen.set(true)">
              <svg lucideSearch [size]="18" aria-hidden="true"></svg>
            </button>
            @if (portal() === 'customer') {
              <button type="button" class="btn-secondary btn-sm hidden sm:inline-flex" (click)="copilotOpen.set(true)">
                <svg lucideSparkles [size]="15" aria-hidden="true"></svg> Copilot
              </button>
              <button type="button" class="icon-btn sm:hidden" aria-label="Open Energy Copilot" (click)="copilotOpen.set(true)">
                <svg lucideSparkles [size]="18" aria-hidden="true"></svg>
              </button>
            }
            <button type="button" class="icon-btn" [attr.aria-label]="'Theme: ' + (theme.theme() | humanize) + '. Change theme'"
                    (click)="cycleTheme()">
              @switch (theme.theme()) {
                @case ('LIGHT') { <svg lucideSun [size]="18" aria-hidden="true"></svg> }
                @case ('DARK') { <svg lucideMoon [size]="18" aria-hidden="true"></svg> }
                @default { <svg lucideMonitor [size]="18" aria-hidden="true"></svg> }
              }
            </button>
            <div class="relative" #bell>
              <button type="button" class="icon-btn relative" [attr.aria-expanded]="bellOpen()" aria-haspopup="true"
                      [attr.aria-label]="'Notifications, ' + notifications.unread() + ' unread'" (click)="toggleBell()">
                <svg lucideBell [size]="18" aria-hidden="true"></svg>
                @if (notifications.unread() > 0) {
                  <span class="num absolute right-1 top-1 min-w-4 rounded-full bg-bad px-1 text-[10px] font-bold leading-4 text-white">
                    {{ notifications.unread() > 99 ? '99+' : notifications.unread() }}
                  </span>
                }
              </button>
              @if (bellOpen()) {
                <div class="absolute right-0 z-30 mt-2 w-[min(22rem,calc(100vw-1.5rem))] overflow-hidden rounded-xl border border-line bg-surface shadow-xl">
                  <div class="flex items-center justify-between border-b border-line px-4 py-3">
                    <p class="font-semibold">Notifications</p>
                    <div class="flex items-center gap-2">
                      <span class="badge" [class]="notifications.live() ? 'bg-ok-soft text-ok' : 'bg-sunken text-muted'">
                        {{ notifications.live() ? 'Live' : 'Offline' }}
                      </span>
                      @if (notifications.unread()) {
                        <button type="button" class="text-xs font-medium text-brand hover:underline" (click)="notifications.markAllRead()">
                          Mark all read
                        </button>
                      }
                    </div>
                  </div>
                  <ul class="max-h-96 divide-y divide-line overflow-y-auto">
                    @for (item of notifications.items(); track item.id) {
                      <li>
                        <button type="button" class="block w-full px-4 py-3 text-left hover:bg-sunken" (click)="openNotification(item)">
                          <p class="flex items-center gap-2 text-sm font-medium text-ink">
                            @if (!item.isRead) { <span class="size-2 shrink-0 rounded-full bg-brand" aria-label="Unread"></span> }
                            {{ item.title }}
                          </p>
                          <p class="mt-0.5 text-xs text-muted">{{ item.message }}</p>
                          <p class="mt-1 text-[11px] text-faint">{{ item.createdAt | relative }}</p>
                        </button>
                      </li>
                    } @empty {
                      <li class="px-4 py-8 text-center text-sm text-muted">You're all caught up.</li>
                    }
                  </ul>
                </div>
              }
            </div>
          </div>
        </header>
        <main id="main" tabindex="-1" class="mx-auto w-full max-w-7xl flex-1 px-4 py-6 outline-none sm:px-6 lg:px-8">
          <router-outlet />
        </main>
      </div>
    </div>
    @if (portal() === 'customer') {
      <app-copilot [open]="copilotOpen()" (closed)="copilotOpen.set(false)" />
    }
    <app-palette [open]="paletteOpen()" [portal]="portal()" [routes]="nav()" (openChange)="paletteOpen.set($event)" />
  `,
})
export class Shell {
  readonly portal = input<'customer' | 'ops'>('customer');
  protected readonly auth = inject(AuthStore);
  protected readonly customer = inject(CustomerStore);
  protected readonly theme = inject(ThemeStore);
  protected readonly notifications = inject(NotificationStore);
  private readonly router = inject(Router);
  protected readonly menuOpen = signal(false);
  protected readonly bellOpen = signal(false);
  protected readonly paletteOpen = signal(false);
  private readonly bell = viewChild<ElementRef<HTMLElement>>('bell');
  protected readonly copilotOpen = inject(CopilotState).open;
  protected readonly nav = computed(() => {
    const role = this.auth.role();
    return (this.portal() === 'customer' ? CUSTOMER_NAV : OPS_NAV)
      .filter((item) => !item.roles || item.roles.includes(role as Role));
  });

  constructor() {
    // Close menus on navigation.
    toSignal(this.router.events.pipe(filter((event) => event instanceof NavigationEnd), map(() => {
      this.bellOpen.set(false);
      this.menuOpen.set(false);
      return true;
    })));
    if (this.auth.role() === 'CUSTOMER') void this.customer.load().catch(() => undefined);
  }

  protected selectMeter(event: Event): void {
    this.customer.select((event.target as HTMLSelectElement).value);
  }

  protected cycleTheme(): void {
    const order = ['SYSTEM', 'LIGHT', 'DARK'] as const;
    const next = order[(order.indexOf(this.theme.theme()) + 1) % order.length];
    void this.theme.choose(next).catch(() => undefined);
  }

  protected toggleBell(): void {
    this.bellOpen.update((open) => !open);
  }

  /** Closes the notifications popover when the user clicks anywhere outside it. */
  protected onDocumentClick(event: MouseEvent): void {
    if (this.bellOpen() && !this.bell()?.nativeElement.contains(event.target as Node)) this.bellOpen.set(false);
  }

  protected async openNotification(item: { id: string; isRead: boolean; link: string | null } & object): Promise<void> {
    this.bellOpen.set(false);
    await this.notifications.markRead(item as never).catch(() => undefined);
    if (item.link) await this.router.navigateByUrl(item.link);
  }
}
