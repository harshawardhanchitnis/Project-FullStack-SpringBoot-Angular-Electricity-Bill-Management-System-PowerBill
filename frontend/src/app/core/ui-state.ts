import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { DOCUMENT, Injectable, effect, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { AuthStore } from './auth';
import { Notification, Profile, Theme } from './models';

export type ToastKind = 'success' | 'error' | 'info';

export interface Toast {
  id: number;
  kind: ToastKind;
  message: string;
}

@Injectable({ providedIn: 'root' })
export class Toasts {
  private next = 1;
  readonly items = signal<Toast[]>([]);

  show(message: string, kind: ToastKind = 'info', timeout = 5000): void {
    const toast = { id: this.next++, kind, message };
    this.items.update((items) => [...items.slice(-3), toast]);
    setTimeout(() => this.dismiss(toast.id), timeout);
  }

  success(message: string): void {
    this.show(message, 'success');
  }

  error(error: unknown, fallback = 'Something went wrong. Try again.'): void {
    this.show(problemMessage(error, fallback), 'error', 7000);
  }

  dismiss(id: number): void {
    this.items.update((items) => items.filter((item) => item.id !== id));
  }
}

/** Extracts the RFC 9457 detail or the first field error from an API failure. */
export function problemMessage(error: unknown, fallback = 'Something went wrong. Try again.'): string {
  if (error instanceof HttpErrorResponse) {
    if (error.status === 0) return 'The service is unreachable. Check your connection and try again.';
    const body = error.error;
    if (body && typeof body === 'object') {
      const fields = body.fieldErrors as Record<string, string> | undefined;
      if (fields && Object.keys(fields).length) {
        const [field, message] = Object.entries(fields)[0];
        return `${field}: ${message}`;
      }
      if (typeof body.detail === 'string' && body.detail) return body.detail;
    }
    if (error.status === 429) return 'Too many requests. Wait a moment and try again.';
  }
  return fallback;
}

@Injectable({ providedIn: 'root' })
export class ThemeStore {
  private readonly document = inject(DOCUMENT);
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthStore);
  readonly theme = signal<Theme>(this.stored());
  /** The colours actually in use, for components such as charts that need concrete values. */
  readonly dark = signal(false);

  constructor() {
    const media = this.document.defaultView?.matchMedia?.('(prefers-color-scheme: dark)');
    media?.addEventListener?.('change', () => this.apply(this.theme()));
    effect(() => this.apply(this.theme()));
    effect(() => {
      const preference = this.auth.user()?.themePreference;
      if (preference && preference !== this.theme()) this.theme.set(preference);
    });
  }

  async choose(theme: Theme): Promise<void> {
    this.theme.set(theme);
    try {
      localStorage.setItem('gridiq-theme', theme);
    } catch {
      /* storage may be unavailable */
    }
    if (this.auth.signedIn()) {
      const profile = await firstValueFrom(this.http.patch<Profile>('/api/v1/auth/me/preferences', { theme }));
      this.auth.updateProfile(profile);
    }
  }

  private apply(theme: Theme): void {
    const root = this.document.documentElement;
    const systemDark = this.document.defaultView?.matchMedia?.('(prefers-color-scheme: dark)').matches ?? false;
    if (theme === 'SYSTEM') root.removeAttribute('data-theme');
    else root.setAttribute('data-theme', theme.toLowerCase());
    this.dark.set(theme === 'DARK' || (theme === 'SYSTEM' && systemDark));
  }

  private stored(): Theme {
    try {
      const value = localStorage.getItem('gridiq-theme');
      return value === 'LIGHT' || value === 'DARK' ? value : 'SYSTEM';
    } catch {
      return 'SYSTEM';
    }
  }
}

/** In-app notifications with live delivery over a fetch-based Server-Sent Events stream. */
@Injectable({ providedIn: 'root' })
export class NotificationStore {
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthStore);
  private readonly toasts = inject(Toasts);
  private controller: AbortController | null = null;
  private retry = 1000;
  readonly items = signal<Notification[]>([]);
  readonly unread = signal(0);
  readonly live = signal(false);

  constructor() {
    effect(() => {
      const token = this.auth.token();
      if (token) this.connect(token);
      else {
        // Signed out: close the stream and forget the previous user's inbox.
        this.disconnect();
        this.items.set([]);
        this.unread.set(0);
      }
    });
  }

  async load(): Promise<void> {
    const inbox = await firstValueFrom(
      this.http.get<{ items: Notification[]; unread: number }>('/api/v1/notifications?limit=30'),
    );
    this.items.set(inbox.items);
    this.unread.set(inbox.unread);
  }

  async markRead(item: Notification): Promise<void> {
    if (item.isRead) return;
    const result = await firstValueFrom(
      this.http.patch<{ unread: number }>(`/api/v1/notifications/${item.id}/read`, null),
    );
    this.items.update((items) => items.map((entry) => (entry.id === item.id ? { ...entry, isRead: true } : entry)));
    this.unread.set(result.unread);
  }

  async markAllRead(): Promise<void> {
    await firstValueFrom(this.http.patch('/api/v1/notifications/read-all', null));
    this.items.update((items) => items.map((entry) => ({ ...entry, isRead: true })));
    this.unread.set(0);
  }

  private disconnect(): void {
    this.controller?.abort();
    this.controller = null;
    this.live.set(false);
  }

  private connect(token: string): void {
    this.disconnect();
    const controller = new AbortController();
    this.controller = controller;
    void this.load().catch(() => undefined);
    void this.stream(token, controller);
  }

  private async stream(token: string, controller: AbortController): Promise<void> {
    try {
      const response = await fetch('/api/v1/notifications/stream', {
        headers: { Authorization: `Bearer ${token}`, Accept: 'text/event-stream' },
        signal: controller.signal,
      });
      if (!response.ok || !response.body) throw new Error(`stream ${response.status}`);
      this.live.set(true);
      this.retry = 1000;
      const reader = response.body.pipeThrough(new TextDecoderStream()).getReader();
      let buffer = '';
      for (;;) {
        const { value, done } = await reader.read();
        if (done) break;
        // SSE allows CRLF, LF or CR line endings; normalise before splitting events on blank lines.
        buffer = (buffer + value).replace(/\r\n?/g, '\n');
        let boundary = buffer.indexOf('\n\n');
        while (boundary >= 0) {
          this.handle(buffer.slice(0, boundary));
          buffer = buffer.slice(boundary + 2);
          boundary = buffer.indexOf('\n\n');
        }
      }
    } catch {
      /* reconnect below */
    }
    this.live.set(false);
    if (!controller.signal.aborted && this.controller === controller) {
      setTimeout(() => {
        const current = this.auth.token();
        if (current && this.controller === controller) this.connect(current);
      }, this.retry);
      this.retry = Math.min(this.retry * 2, 30_000);
    }
  }

  /** Parses one SSE event block. Malformed frames are skipped rather than breaking the stream. */
  handle(block: string): void {
    let event = 'message';
    const data: string[] = [];
    for (const line of block.split('\n')) {
      if (line.startsWith('event:')) event = line.slice(6).trim();
      else if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''));
    }
    if (!data.length) return;
    let payload: { unread?: number; notification?: Notification };
    try {
      payload = JSON.parse(data.join('\n'));
    } catch {
      return;
    }
    if (typeof payload !== 'object' || payload === null) return;
    if (typeof payload.unread === 'number') this.unread.set(payload.unread);
    const item = payload.notification;
    if (event === 'notification' && item && typeof item.id === 'string') {
      this.items.update((items) => [item, ...items.filter((entry) => entry.id !== item.id)].slice(0, 50));
      this.toasts.show(`${item.title}: ${item.message}`, 'info', 6000);
    }
  }
}
