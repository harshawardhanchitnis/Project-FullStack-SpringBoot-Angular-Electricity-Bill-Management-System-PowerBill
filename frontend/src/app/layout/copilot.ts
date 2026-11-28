import { HttpClient } from '@angular/common/http';
import { Component, ElementRef, Injectable, effect, inject, input, output, signal, untracked, viewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { LucideEraser, LucideSend, LucideSparkles } from '@lucide/angular';
import { firstValueFrom } from 'rxjs';
import { CustomerStore } from '../core/customer';
import { CopilotAnswer } from '../core/models';
import { problemMessage } from '../core/ui-state';
import { Dialog } from '../shared/ui';

const DEFAULT_SUGGESTIONS = ['Why did my bill change?', 'What will this month cost?', 'How can I save at peak time?'];

/** Lets any customer page open the Copilot drawer. */
@Injectable({ providedIn: 'root' })
export class CopilotState {
  readonly open = signal(false);
  /** A question to ask as soon as the drawer opens, for example from the command palette. */
  readonly pending = signal<string | null>(null);

  ask(question: string): void {
    this.pending.set(question);
    this.open.set(true);
  }
}

interface Turn {
  /** `divider` marks a meter switch; it is shown in the transcript but never sent as history. */
  role: 'user' | 'assistant' | 'divider';
  content: string;
  html?: string;
  meta?: CopilotAnswer;
}

/** Escapes text first, then applies a tiny markdown subset (bold, bullets, quotes) for assistant answers. */
export function renderMarkdown(text: string): string {
  const escape = (value: string) =>
    value.replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;').replaceAll('"', '&quot;');
  const inline = (value: string) => escape(value).replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>');
  const out: string[] = [];
  let list = false;
  for (const line of text.split('\n')) {
    if (line.startsWith('- ')) {
      if (!list) out.push('<ul>');
      list = true;
      out.push(`<li>${inline(line.slice(2))}</li>`);
      continue;
    }
    if (list) {
      out.push('</ul>');
      list = false;
    }
    if (line.startsWith('> ')) out.push(`<blockquote>${inline(line.slice(2))}</blockquote>`);
    else if (line.trim()) out.push(`<p>${inline(line)}</p>`);
  }
  if (list) out.push('</ul>');
  return out.join('');
}

@Component({
  selector: 'app-copilot',
  imports: [Dialog, FormsModule, LucideEraser, LucideSend, LucideSparkles],
  template: `
    <app-dialog [open]="open()" [drawer]="true" heading="Energy Copilot"
                subheading="Answers use your own bills, tariff and meter data." (closed)="closed.emit()">
      <div class="flex min-h-full flex-col gap-4" aria-live="polite">
        @if (turns().length) {
          <div class="flex justify-end">
            <button type="button" class="btn-ghost btn-sm" [disabled]="busy()" (click)="clearChat()">
              <svg lucideEraser [size]="14" aria-hidden="true"></svg> Clear chat
            </button>
          </div>
        } @else {
          <div class="rounded-xl bg-brand-soft p-4 text-sm text-ink">
            <p class="flex items-center gap-2 font-semibold text-brand-strong">
              <svg lucideSparkles [size]="16" aria-hidden="true"></svg> Ask about your electricity
            </p>
            <p class="mt-1 text-muted">Every figure comes from your ledger and meter readings, never from guesses.</p>
          </div>
        }
        @for (turn of turns(); track $index) {
          @if (turn.role === 'divider') {
            <p class="flex items-center gap-3 text-xs text-faint" role="separator">
              <span class="h-px flex-1 bg-line"></span>{{ turn.content }}<span class="h-px flex-1 bg-line"></span>
            </p>
          } @else if (turn.role === 'user') {
            <p class="ml-auto max-w-[85%] rounded-2xl rounded-br-sm bg-brand px-4 py-2.5 text-sm text-on-brand">
              {{ turn.content }}
            </p>
          } @else {
            <div class="max-w-[95%] rounded-2xl rounded-bl-sm border border-line bg-raised px-4 py-3 text-sm">
              <div class="prose-chat" [innerHTML]="turn.html"></div>
              @if (turn.meta?.citations?.length) {
                <p class="mt-2 text-xs text-faint">Source: {{ citations(turn.meta!) }}</p>
              }
              @if (turn.meta?.providerUsed) {
                <p class="mt-1 text-[11px] text-faint">Answered by {{ turn.meta!.providerUsed }}</p>
              }
            </div>
          }
        }
        @if (busy()) {
          <div class="skeleton h-16 w-3/4" aria-label="The assistant is answering"></div>
        }
        <div #end></div>
      </div>
      <div dialog-footer class="empty:hidden border-t border-line p-4">
        <div class="mb-3 flex flex-wrap gap-2">
          @for (suggestion of suggestions(); track suggestion) {
            <button type="button" class="btn-secondary btn-sm" [disabled]="busy()" (click)="ask(suggestion)">
              {{ suggestion }}
            </button>
          }
        </div>
        <form class="flex gap-2" (ngSubmit)="ask(draft)">
          <label class="sr-only" for="copilot-input">Your question</label>
          <input id="copilot-input" class="input" name="draft" [(ngModel)]="draft" maxlength="1000"
                 placeholder="Why did my bill change?" autocomplete="off" />
          <button type="submit" class="btn-primary" [disabled]="busy() || !draft.trim()" aria-label="Send">
            <svg lucideSend [size]="16" aria-hidden="true"></svg>
          </button>
        </form>
      </div>
    </app-dialog>
  `,
})
export class CopilotDrawer {
  readonly open = input(false);
  readonly closed = output<void>();
  private readonly http = inject(HttpClient);
  private readonly customer = inject(CustomerStore);
  private readonly state = inject(CopilotState);
  private readonly end = viewChild<ElementRef<HTMLElement>>('end');
  protected readonly turns = signal<Turn[]>([]);
  protected readonly busy = signal(false);
  protected readonly suggestions = signal(DEFAULT_SUGGESTIONS);
  protected draft = '';
  private meterId: string | null | undefined;

  constructor() {
    effect(() => {
      const question = this.state.pending();
      if (question && this.open()) {
        untracked(() => {
          this.state.pending.set(null);
          void this.ask(question);
        });
      }
    });
    // Answers are about one meter; mark the transcript when the customer switches meters in the top bar.
    effect(() => {
      const meter = this.customer.meter();
      untracked(() => {
        const id = meter?.id ?? null;
        if (this.meterId !== undefined && id !== this.meterId && this.turns().length && meter) {
          this.turns.update((turns) => [...turns, { role: 'divider', content: `Now answering for ${meter.meterSerial}` }]);
        }
        this.meterId = id;
      });
    });
  }

  protected clearChat(): void {
    this.turns.set([]);
    this.suggestions.set(DEFAULT_SUGGESTIONS);
  }

  protected citations(meta: CopilotAnswer): string {
    return meta.citations.map((citation) => citation.title).join(', ');
  }

  protected async ask(text: string): Promise<void> {
    const message = text.trim();
    if (!message || this.busy()) return;
    this.draft = '';
    // History restarts at the last meter switch, so earlier answers about another meter are not re-sent.
    const turns = this.turns();
    const since = turns.map((turn) => turn.role).lastIndexOf('divider') + 1;
    const history = turns.slice(since)
      .filter((turn) => turn.role === 'user' || (turn.role === 'assistant' && turn.meta !== undefined))
      .map((turn) => ({ role: turn.role, content: turn.content }));
    this.turns.update((turns) => [...turns, { role: 'user', content: message }]);
    this.busy.set(true);
    this.scroll();
    try {
      const answer = await firstValueFrom(this.http.post<CopilotAnswer>('/api/v1/ai/copilot/chat', {
        message, meterId: this.customer.meter()?.id ?? null, history,
      }));
      this.turns.update((turns) => [...turns, {
        role: 'assistant', content: answer.answer, html: renderMarkdown(answer.answer), meta: answer,
      }]);
      this.suggestions.set(answer.suggestions);
    } catch (error) {
      const text = problemMessage(error, 'The assistant is unavailable right now.');
      this.turns.update((turns) => [...turns, { role: 'assistant', content: text, html: renderMarkdown(text) }]);
    } finally {
      this.busy.set(false);
      this.scroll();
    }
  }

  private scroll(): void {
    queueMicrotask(() => this.end()?.nativeElement.scrollIntoView({ block: 'end', behavior: 'smooth' }));
  }
}
