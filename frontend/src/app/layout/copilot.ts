import { HttpClient } from '@angular/common/http';
import { Component, ElementRef, Injectable, inject, input, output, signal, viewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { LucideSend, LucideSparkles } from '@lucide/angular';
import { firstValueFrom } from 'rxjs';
import { CustomerStore } from '../core/customer';
import { CopilotAnswer } from '../core/models';
import { problemMessage } from '../core/ui-state';
import { Dialog } from '../shared/ui';

/** Lets any customer page open the Copilot drawer. */
@Injectable({ providedIn: 'root' })
export class CopilotState {
  readonly open = signal(false);
}

interface Turn {
  role: 'user' | 'assistant';
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
  imports: [Dialog, FormsModule, LucideSend, LucideSparkles],
  template: `
    <app-dialog [open]="open()" [drawer]="true" heading="Energy Copilot"
                subheading="Answers use your own bills, tariff and meter data." (closed)="closed.emit()">
      <div class="flex min-h-full flex-col gap-4" aria-live="polite">
        @if (!turns().length) {
          <div class="rounded-xl bg-brand-soft p-4 text-sm text-ink">
            <p class="flex items-center gap-2 font-semibold text-brand-strong">
              <svg lucideSparkles [size]="16" aria-hidden="true"></svg> Ask about your electricity
            </p>
            <p class="mt-1 text-muted">Every figure comes from your ledger and meter readings, never from guesses.</p>
          </div>
        }
        @for (turn of turns(); track $index) {
          @if (turn.role === 'user') {
            <p class="ml-auto max-w-[85%] rounded-2xl rounded-br-sm bg-brand px-4 py-2.5 text-sm text-on-brand">
              {{ turn.content }}
            </p>
          } @else {
            <div class="max-w-[95%] rounded-2xl rounded-bl-sm border border-line bg-raised px-4 py-3 text-sm">
              <div class="prose-chat" [innerHTML]="turn.html"></div>
              @if (turn.meta?.citations?.length) {
                <p class="mt-2 text-xs text-faint">Source: {{ citations(turn.meta!) }}</p>
              }
              <p class="mt-1 text-[11px] text-faint">Answered by {{ turn.meta?.providerUsed }}</p>
            </div>
          }
        }
        @if (busy()) {
          <div class="skeleton h-16 w-3/4" aria-label="The assistant is answering"></div>
        }
        <div #end></div>
      </div>
      <div dialog-footer class="border-t border-line p-4">
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
  private readonly end = viewChild<ElementRef<HTMLElement>>('end');
  protected readonly turns = signal<Turn[]>([]);
  protected readonly busy = signal(false);
  protected readonly suggestions = signal([
    'Why did my bill change?', 'What will this month cost?', 'How can I save at peak time?',
  ]);
  protected draft = '';

  protected citations(meta: CopilotAnswer): string {
    return meta.citations.map((citation) => citation.title).join(', ');
  }

  protected async ask(text: string): Promise<void> {
    const message = text.trim();
    if (!message || this.busy()) return;
    this.draft = '';
    const history = this.turns().map((turn) => ({ role: turn.role, content: turn.content }));
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
