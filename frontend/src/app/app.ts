import { Component, inject } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { LucideX } from '@lucide/angular';
import { ThemeStore, Toasts } from './core/ui-state';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, LucideX],
  template: `
    <router-outlet />
    <div class="pointer-events-none fixed inset-x-3 bottom-3 z-[70] flex flex-col items-end gap-2 sm:inset-x-auto sm:right-5 sm:w-96"
         aria-live="polite" aria-atomic="false">
      @for (toast of toasts.items(); track toast.id) {
        <div class="pointer-events-auto flex w-full items-start gap-3 rounded-xl border px-4 py-3 text-sm shadow-lg"
             [class]="toast.kind === 'error' ? 'border-bad/40 bg-bad-soft text-ink'
               : toast.kind === 'success' ? 'border-ok/40 bg-ok-soft text-ink' : 'border-line bg-raised text-ink'"
             [attr.role]="toast.kind === 'error' ? 'alert' : 'status'">
          <p class="min-w-0 flex-1">{{ toast.message }}</p>
          <button type="button" class="-m-1 rounded p-1 text-muted hover:text-ink" aria-label="Dismiss"
                  (click)="toasts.dismiss(toast.id)">
            <svg lucideX [size]="14" aria-hidden="true"></svg>
          </button>
        </div>
      }
    </div>
  `,
})
export class App {
  protected readonly toasts = inject(Toasts);
  // Injected eagerly so the stored theme applies before the first page renders.
  protected readonly theme = inject(ThemeStore);
}
