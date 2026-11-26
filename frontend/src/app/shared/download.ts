import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';

/** Downloads an authenticated file (the interceptor adds the bearer token) and saves it with a filename. */
export async function download(http: HttpClient, url: string, filename: string): Promise<void> {
  const blob = await firstValueFrom(http.get(url, { responseType: 'blob' }));
  const href = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = href;
  anchor.download = filename;
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  setTimeout(() => URL.revokeObjectURL(href), 1000);
}

export function idempotencyKey(): string {
  return crypto.randomUUID();
}
