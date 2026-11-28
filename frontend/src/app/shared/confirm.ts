import { Injectable, signal } from '@angular/core';

export interface ConfirmRequest {
  title: string;
  message: string;
  confirmLabel?: string;
  /** `danger` styles the confirm button for destructive or access-reducing actions. */
  tone?: 'default' | 'danger';
}

interface Pending extends ConfirmRequest {
  resolve: (confirmed: boolean) => void;
}

/** Asks the user to confirm an action in a styled, accessible dialog instead of the browser's confirm(). */
@Injectable({ providedIn: 'root' })
export class ConfirmService {
  readonly pending = signal<Pending | null>(null);

  ask(request: ConfirmRequest): Promise<boolean> {
    this.pending()?.resolve(false);
    return new Promise((resolve) => this.pending.set({ ...request, resolve }));
  }

  settle(confirmed: boolean): void {
    const pending = this.pending();
    this.pending.set(null);
    pending?.resolve(confirmed);
  }
}
