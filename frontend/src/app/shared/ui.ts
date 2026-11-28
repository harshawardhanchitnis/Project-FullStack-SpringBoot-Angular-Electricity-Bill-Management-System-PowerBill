import { Component, DestroyRef, ElementRef, computed, effect, inject, input, output, viewChild } from '@angular/core';
import { LucideAlertCircle, LucideInbox, LucideRefreshCw, LucideX } from '@lucide/angular';
import { humanize } from './format';

const TONES: Record<string, string> = {
  ok: 'bg-ok-soft text-ok',
  warn: 'bg-warn-soft text-warn',
  bad: 'bg-bad-soft text-bad',
  info: 'bg-brand-soft text-brand-strong',
  cyan: 'bg-cyan-soft text-cyan',
  muted: 'bg-sunken text-muted',
};

const STATUS_TONES: Record<string, keyof typeof TONES> = {
  PAID: 'ok', SUCCEEDED: 'ok', ACTIVE: 'ok', RESOLVED: 'ok', CLOSED: 'muted', COMPLETED: 'ok', VALID: 'ok',
  CONFIRMED_FAULT: 'warn', FALSE_POSITIVE: 'muted', ISSUED: 'info', OPEN: 'info', ASSIGNED: 'cyan',
  IN_PROGRESS: 'cyan', INVESTIGATING: 'cyan', PARTIALLY_PAID: 'warn', PENDING: 'warn', INITIATED: 'warn',
  RUNNING: 'warn', PARTIAL: 'warn', RESTORING: 'cyan', CREW_DISPATCHED: 'cyan', PLANNED: 'info', SCHEDULED: 'info',
  SUSPENDED: 'warn', OVERDUE: 'bad', FAILED: 'bad', EXPIRED: 'muted', DISCONNECTED: 'bad', DISABLED: 'bad',
  CONFIRMED_TAMPER: 'bad', CRITICAL: 'bad', HIGH: 'warn', MEDIUM: 'info', LOW: 'muted', UNPLANNED: 'warn',
  EMERGENCY: 'bad', DISPUTED: 'warn', VOID: 'muted', FLAGGED: 'bad', ESTIMATED: 'warn',
};

@Component({
  selector: 'app-badge',
  template: `<span class="badge" [class]="classes()">{{ text() }}</span>`,
})
export class StatusBadge {
  readonly status = input.required<string>();
  readonly label = input<string>();
  protected readonly text = computed(() => this.label() ?? humanize(this.status()));
  protected readonly classes = computed(() => TONES[STATUS_TONES[this.status()] ?? 'muted']);
}

@Component({
  selector: 'app-kpi',
  template: `
    <div class="card-pad h-full">
      <div class="flex items-start justify-between gap-3">
        <p class="eyebrow">{{ label() }}</p>
        <span class="-mt-1 inline-flex size-8 shrink-0 items-center justify-center rounded-lg empty:hidden" [class]="iconClass()"><ng-content select="[kpi-icon]" /></span>
      </div>
      @if (loading()) {
        <div class="skeleton mt-3 h-8 w-32"></div>
        <div class="skeleton mt-2 h-4 w-40"></div>
      } @else {
        <p class="num mt-2 text-2xl font-semibold tracking-tight text-ink">{{ value() }}</p>
        @if (hint()) {
          <p class="mt-1 text-sm" [class]="toneClass()">{{ hint() }}</p>
        }
      }
      <ng-content />
    </div>
  `,
})
export class KpiCard {
  readonly label = input.required<string>();
  readonly value = input<string>('—');
  readonly hint = input<string>();
  readonly tone = input<'ok' | 'warn' | 'bad' | 'muted'>('muted');
  readonly loading = input(false);
  protected readonly toneClass = computed(
    () => ({ ok: 'text-ok', warn: 'text-warn', bad: 'text-bad', muted: 'text-muted' })[this.tone()],
  );
  protected readonly iconClass = computed(
    () => ({ ok: 'bg-ok-soft text-ok', warn: 'bg-warn-soft text-warn', bad: 'bg-bad-soft text-bad', muted: 'bg-brand-soft text-brand' })[this.tone()],
  );
}

@Component({
  selector: 'app-empty',
  imports: [LucideInbox],
  template: `
    <div class="flex flex-col items-center justify-center px-6 py-12 text-center">
      <span class="mb-3 inline-flex size-11 items-center justify-center rounded-full bg-sunken text-faint">
        <svg lucideInbox [size]="20" aria-hidden="true"></svg>
      </span>
      <p class="font-semibold text-ink">{{ title() }}</p>
      @if (message()) {
        <p class="mt-1 max-w-sm text-sm text-muted">{{ message() }}</p>
      }
      <div class="mt-4"><ng-content /></div>
    </div>
  `,
})
export class EmptyState {
  readonly title = input.required<string>();
  readonly message = input<string>();
}

@Component({
  selector: 'app-error',
  imports: [LucideAlertCircle, LucideRefreshCw],
  template: `
    <div role="alert" class="flex flex-wrap items-center gap-3 rounded-xl border border-bad/30 bg-bad-soft p-4 text-sm">
      <svg lucideAlertCircle [size]="18" class="text-bad" aria-hidden="true"></svg>
      <p class="min-w-0 flex-1 text-ink">{{ message() }}</p>
      <button type="button" class="btn-secondary btn-sm" (click)="retry.emit()">
        <svg lucideRefreshCw [size]="14" aria-hidden="true"></svg> Retry
      </button>
    </div>
  `,
})
export class ErrorBanner {
  readonly message = input('This section could not be loaded.');
  readonly retry = output<void>();
}

/** Modal dialog or side drawer with a focus trap, Escape to close and focus restoration. */
@Component({
  selector: 'app-dialog',
  imports: [LucideX],
  host: { '(document:keydown.escape)': 'onEscape()' },
  template: `
    @if (open()) {
      <div class="fixed inset-0 z-40 bg-slate-950/45 backdrop-blur-[2px]" aria-hidden="true" (click)="dismiss()"></div>
      <section
        #panel
        role="dialog"
        aria-modal="true"
        [attr.aria-labelledby]="headingId"
        (keydown.tab)="trap($event, false)"
        (keydown.shift.tab)="trap($event, true)"
        tabindex="-1"
        class="fixed z-50 flex flex-col bg-surface shadow-2xl outline-none"
        [class]="drawer()
          ? 'inset-y-0 right-0 w-full max-w-xl border-l border-line'
          : 'inset-x-3 top-[8vh] mx-auto max-h-[84vh] max-w-lg rounded-2xl border border-line sm:inset-x-6'"
      >
        <header class="flex items-start justify-between gap-4 border-b border-line px-5 py-4">
          <div class="min-w-0">
            <h2 [id]="headingId" class="text-lg font-semibold text-ink">{{ heading() }}</h2>
            @if (subheading()) {
              <p class="mt-0.5 text-sm text-muted">{{ subheading() }}</p>
            }
          </div>
          <button type="button" class="icon-btn -mr-2 -mt-1" aria-label="Close" (click)="dismiss()">
            <svg lucideX [size]="18" aria-hidden="true"></svg>
          </button>
        </header>
        <div class="min-h-0 flex-1 overflow-y-auto px-5 py-4"><ng-content /></div>
        <ng-content select="[dialog-footer]" />
      </section>
    }
  `,
})
export class Dialog {
  private static counter = 0;
  /** Open dialogs; the page behind stays scroll-locked until the last one closes. */
  private static locks = 0;
  readonly open = input(false);
  readonly heading = input.required<string>();
  readonly subheading = input<string>();
  readonly drawer = input(false);
  readonly closed = output<void>();
  protected readonly headingId = `dialog-heading-${++Dialog.counter}`;
  private readonly panel = viewChild<ElementRef<HTMLElement>>('panel');
  private previous: Element | null = null;
  private locked = false;

  constructor() {
    effect(() => {
      if (this.open()) {
        this.lockScroll(true);
        this.previous = document.activeElement;
        queueMicrotask(() => this.panel()?.nativeElement.focus());
      } else {
        this.lockScroll(false);
        if (this.previous instanceof HTMLElement) {
          this.previous.focus();
          this.previous = null;
        }
      }
    });
    inject(DestroyRef).onDestroy(() => this.lockScroll(false));
  }

  /** Uses a class rather than an inline style so the strict CSP still applies. */
  private lockScroll(lock: boolean): void {
    if (lock === this.locked) return;
    this.locked = lock;
    Dialog.locks += lock ? 1 : -1;
    document.body.classList.toggle('overflow-hidden', Dialog.locks > 0);
  }

  /** Keeps keyboard focus inside the open dialog. */
  protected trap(event: Event, backwards: boolean): void {
    const panel = this.panel()?.nativeElement;
    if (!panel) return;
    const focusable = Array.from(panel.querySelectorAll<HTMLElement>(
      'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])',
    )).filter((element) => element.offsetParent !== null || element === document.activeElement);
    if (!focusable.length) {
      event.preventDefault();
      return;
    }
    const first = focusable[0];
    const last = focusable[focusable.length - 1];
    const active = document.activeElement;
    if (backwards && (active === first || active === panel)) {
      event.preventDefault();
      last.focus();
    } else if (!backwards && active === last) {
      event.preventDefault();
      first.focus();
    }
  }

  protected onEscape(): void {
    if (this.open()) this.dismiss();
  }

  protected dismiss(): void {
    this.closed.emit();
  }
}

@Component({
  selector: 'app-pager',
  template: `
    @if (total() > size()) {
      <nav class="flex items-center justify-between gap-3 px-1 py-3 text-sm text-muted" aria-label="Pagination">
        <span class="num">{{ from() }}–{{ to() }} of {{ total() }}</span>
        <div class="flex gap-2">
          <button type="button" class="btn-secondary btn-sm" [disabled]="page() === 0" (click)="pageChange.emit(page() - 1)">
            Previous
          </button>
          <button type="button" class="btn-secondary btn-sm" [disabled]="to() >= total()" (click)="pageChange.emit(page() + 1)">
            Next
          </button>
        </div>
      </nav>
    }
  `,
})
export class Pager {
  readonly page = input(0);
  readonly size = input(20);
  readonly total = input(0);
  readonly pageChange = output<number>();
  protected readonly from = computed(() => this.page() * this.size() + 1);
  protected readonly to = computed(() => Math.min(this.total(), (this.page() + 1) * this.size()));
}

export const UI = [StatusBadge, KpiCard, EmptyState, ErrorBanner, Dialog, Pager] as const;
