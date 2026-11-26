import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ServiceStatus } from './service-status';

describe('ServiceStatus', () => {
  it('shows a failed connection and checks again when requested', async () => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    const fixture = TestBed.createComponent(ServiceStatus);
    const http = TestBed.inject(HttpTestingController);
    const view: HTMLElement = fixture.nativeElement;
    const status = () => view.querySelector('[role="status"]')?.textContent?.trim();

    fixture.detectChanges();
    const check = view.querySelector('button');
    if (!check) throw new Error('Connection check button is missing');
    expect(check.disabled).toBe(true);
    http.expectOne('/api/v1/status').flush({}, { status: 503, statusText: 'Unavailable' });
    await fixture.whenStable();
    expect(status()).toBe('Core API unavailable.');
    expect(check.disabled).toBe(false);

    check.click();
    http.expectOne('/api/v1/status').flush({ status: 'UP', service: 'gridiq-core' });
    await fixture.whenStable();
    expect(status()).toBe('Connected');
    http.verify();
  });
});
