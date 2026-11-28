import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Pager, StatusBadge } from './ui';

@Component({
  imports: [StatusBadge, Pager],
  template: `
    <app-badge [status]="status()" />
    <app-pager [page]="page()" [size]="10" [total]="35" (pageChange)="page.set($event)" />
  `,
})
class Host {
  readonly status = signal('OVERDUE');
  readonly page = signal(0);
}

describe('shared UI', () => {
  it('renders a status badge with its tone and pages through results', async () => {
    const fixture = TestBed.createComponent(Host);
    await fixture.whenStable();
    const view: HTMLElement = fixture.nativeElement;
    const badge = view.querySelector('.badge')!;
    expect(badge.textContent?.trim()).toBe('Overdue');
    expect(badge.className).toContain('text-bad');

    expect(view.querySelector('nav')?.textContent).toContain('1–10 of 35');
    const [previous, next] = Array.from(view.querySelectorAll('button'));
    expect(previous.disabled).toBe(true);
    next.click();
    await fixture.whenStable();
    expect(fixture.componentInstance.page()).toBe(1);
    expect(view.querySelector('nav')?.textContent).toContain('11–20 of 35');

    fixture.componentInstance.status.set('PAID');
    await fixture.whenStable();
    expect(view.querySelector('.badge')?.className).toContain('text-ok');
  });
});
