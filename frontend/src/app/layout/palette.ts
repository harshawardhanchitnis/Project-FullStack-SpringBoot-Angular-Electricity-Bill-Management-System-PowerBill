import { HttpClient } from '@angular/common/http';
import { Component, ElementRef, computed, effect, inject, input, output, signal, untracked, viewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { LucideSearch } from '@lucide/angular';
import { firstValueFrom } from 'rxjs';
import { Account, BillSummary, Complaint, Page } from '../core/models';
import { Dialog } from '../shared/ui';
import { CopilotState } from './copilot';

export interface PaletteRoute {
  label: string;
  path: string;
}

interface Command {
  group: string;
  label: string;
  detail?: string;
  run: () => void;
}

const COPILOT_QUESTIONS = [
  'Why did my bill change?', 'What will this month cost?', 'How can I save at peak time?', 'Explain my slabs',
];

/**
 * Ctrl/Cmd+K command palette: jump to any page the user may open, or search records. Staff search consumers, bills
 * and tickets; customers search their bills and tickets or ask the Energy Copilot. Arrow keys move, Enter opens.
 */
@Component({
  selector: 'app-palette',
  imports: [Dialog, FormsModule, LucideSearch],
  host: { '(document:keydown)': 'onShortcut($event)' },
  template: `
    <app-dialog [open]="open()" heading="Go to or search" (closed)="close()">
      <div class="space-y-3">
        <label class="sr-only" for="palette-input">Search pages and records</label>
        <div class="relative">
          <svg lucideSearch [size]="16" class="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-faint" aria-hidden="true"></svg>
          <input #search id="palette-input" class="input pl-9" autocomplete="off" role="combobox"
                 aria-controls="palette-results" [attr.aria-activedescendant]="results().length ? 'palette-option-' + active() : null"
                 aria-expanded="true" [placeholder]="portal() === 'ops' ? 'Pages, consumers, bills, tickets…' : 'Pages, bills, tickets, questions…'"
                 [ngModel]="query()" (ngModelChange)="query.set($event)" (keydown)="onKey($event)" />
        </div>
        <ul id="palette-results" role="listbox" aria-label="Results" class="max-h-[50vh] overflow-y-auto">
          @for (item of results(); track $index; let index = $index) {
            @if (index === 0 || results()[index - 1].group !== item.group) {
              <li role="presentation" class="px-2 pb-1 pt-3 text-xs font-semibold uppercase tracking-wide text-faint">{{ item.group }}</li>
            }
            <li [id]="'palette-option-' + index" role="option" tabindex="-1" [attr.aria-selected]="index === active()"
                (keydown.enter)="choose(item)"
                class="flex cursor-pointer items-baseline justify-between gap-3 rounded-lg px-3 py-2 text-sm"
                [class]="index === active() ? 'bg-brand-soft text-ink' : 'text-ink'"
                (mousemove)="active.set(index)" (click)="choose(item)">
              <span class="min-w-0 truncate">{{ item.label }}</span>
              @if (item.detail) { <span class="shrink-0 text-xs text-faint">{{ item.detail }}</span> }
            </li>
          } @empty {
            <li class="px-3 py-6 text-center text-sm text-muted">{{ searching() ? 'Searching…' : 'No matches.' }}</li>
          }
        </ul>
        <p class="text-xs text-faint">↑ ↓ to move · Enter to open · Esc to close</p>
      </div>
    </app-dialog>
  `,
})
export class CommandPalette {
  readonly open = input(false);
  readonly portal = input<'customer' | 'ops'>('customer');
  readonly routes = input<PaletteRoute[]>([]);
  readonly openChange = output<boolean>();
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);
  private readonly copilot = inject(CopilotState);
  private readonly search = viewChild<ElementRef<HTMLInputElement>>('search');
  protected readonly query = signal('');
  protected readonly active = signal(0);
  protected readonly searching = signal(false);
  private readonly records = signal<Command[]>([]);
  private timer: ReturnType<typeof setTimeout> | undefined;
  private request = 0;

  protected readonly results = computed(() => {
    const term = this.query().trim().toLowerCase();
    const pages = this.routes().filter((route) => !term || route.label.toLowerCase().includes(term))
      .map((route): Command => ({ group: 'Pages', label: route.label, run: () => this.go(route.path) }));
    const questions = this.portal() === 'customer'
      ? COPILOT_QUESTIONS.filter((question) => !term || question.toLowerCase().includes(term))
        .map((question): Command => ({ group: 'Ask Copilot', label: question, run: () => this.copilot.ask(question) }))
      : [];
    return [...pages, ...this.records(), ...questions].slice(0, 30);
  });

  constructor() {
    effect(() => {
      if (this.open()) {
        untracked(() => {
          this.query.set('');
          this.records.set([]);
          this.active.set(0);
        });
        queueMicrotask(() => this.search()?.nativeElement.focus());
      }
    });
    // Debounced record search; a newer keystroke cancels an older response.
    effect(() => {
      const term = this.query().trim();
      untracked(() => {
        this.active.set(0);
        clearTimeout(this.timer);
        if (term.length < 2) {
          this.records.set([]);
          return;
        }
        this.timer = setTimeout(() => void this.lookup(term), 200);
      });
    });
  }

  protected onShortcut(event: KeyboardEvent): void {
    if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') {
      event.preventDefault();
      this.openChange.emit(!this.open());
    }
  }

  protected onKey(event: KeyboardEvent): void {
    const count = this.results().length;
    if (event.key === 'ArrowDown' && count) {
      event.preventDefault();
      this.active.update((index) => (index + 1) % count);
    } else if (event.key === 'ArrowUp' && count) {
      event.preventDefault();
      this.active.update((index) => (index - 1 + count) % count);
    } else if (event.key === 'Enter' && count) {
      event.preventDefault();
      this.choose(this.results()[Math.min(this.active(), count - 1)]);
    }
  }

  protected choose(item: Command): void {
    this.close();
    item.run();
  }

  protected close(): void {
    this.openChange.emit(false);
  }

  private go(url: string): void {
    void this.router.navigateByUrl(url);
  }

  private async lookup(term: string): Promise<void> {
    const request = ++this.request;
    this.searching.set(true);
    const q = encodeURIComponent(term);
    const found: Command[] = [];
    const settle = <T>(promise: Promise<T>) => promise.catch(() => null);
    if (this.portal() === 'ops') {
      const [consumers, bills, tickets] = await Promise.all([
        settle(firstValueFrom(this.http.get<Page<Account>>(`/api/v1/ops/consumers?q=${q}&size=5`))),
        settle(firstValueFrom(this.http.get<Page<BillSummary>>(`/api/v1/ops/bills?q=${q}&size=5`))),
        settle(firstValueFrom(this.http.get<Page<Complaint>>(`/api/v1/ops/complaints?q=${q}&size=5`))),
      ]);
      for (const account of consumers?.content ?? []) {
        found.push({ group: 'Consumers', label: account.fullName, detail: account.consumerNumber,
          run: () => this.go(`/ops/consumers?account=${account.id}`) });
      }
      for (const bill of bills?.content ?? []) {
        found.push({ group: 'Bills', label: bill.billNumber, detail: bill.status,
          run: () => this.go(`/ops/billing?accountId=${bill.consumerAccountId}`) });
      }
      for (const ticket of tickets?.content ?? []) {
        found.push({ group: 'Tickets', label: ticket.subject, detail: ticket.ticketNumber,
          run: () => this.go(`/ops/support?ticket=${ticket.id}`) });
      }
    } else {
      const [bills, tickets] = await Promise.all([
        settle(firstValueFrom(this.http.get<Page<BillSummary>>('/api/v1/bills?size=50'))),
        settle(firstValueFrom(this.http.get<Page<Complaint>>(`/api/v1/complaints?size=5&q=${q}`))),
      ]);
      const lower = term.toLowerCase();
      for (const bill of (bills?.content ?? []).filter((item) =>
        item.billNumber.toLowerCase().includes(lower) || item.period.includes(lower)).slice(0, 5)) {
        found.push({ group: 'Bills', label: bill.billNumber, detail: bill.period,
          run: () => this.go(`/app/bills?bill=${bill.id}`) });
      }
      for (const ticket of tickets?.content ?? []) {
        found.push({ group: 'Tickets', label: ticket.subject, detail: ticket.ticketNumber,
          run: () => this.go(`/app/support?ticket=${ticket.id}`) });
      }
    }
    if (request !== this.request) return;
    this.records.set(found);
    this.searching.set(false);
  }
}
