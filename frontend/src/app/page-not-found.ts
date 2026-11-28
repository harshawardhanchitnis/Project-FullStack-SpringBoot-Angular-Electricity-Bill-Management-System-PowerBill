import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';

@Component({
  imports: [RouterLink],
  template: `
    <main class="mx-auto flex min-h-dvh max-w-3xl flex-col justify-center px-6 py-20 sm:px-12">
      <p class="eyebrow">Error 404</p>
      <h1 class="mt-2 text-4xl font-bold">Page not found</h1>
      <p class="mt-3 text-muted">The page you requested does not exist or has moved.</p>
      <a routerLink="/" class="btn-primary mt-6 self-start">Back to GridIQ</a>
    </main>
  `,
})
export class PageNotFound {}
