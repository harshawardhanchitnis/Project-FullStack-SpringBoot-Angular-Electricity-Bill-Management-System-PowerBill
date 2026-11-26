import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';

@Component({
  imports: [RouterLink],
  template: `
    <main class="mx-auto max-w-3xl px-6 py-20 sm:px-12">
      <h1 class="text-4xl font-bold">Page not found</h1>
      <a routerLink="/" class="mt-5 inline-block text-[var(--accent)] underline">Back to GridIQ</a>
    </main>
  `,
})
export class PageNotFound {}
