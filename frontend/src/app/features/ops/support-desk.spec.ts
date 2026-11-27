import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { AuthStore } from '../../core/auth';
import { Complaint, ComplaintDetail, Outage, Page, TokenResponse } from '../../core/models';
import { SupportDesk } from './support-desk';

const AGENT: TokenResponse = {
  accessToken: 'token-agent', tokenType: 'Bearer', expiresIn: 900,
  user: { id: 'agent-1', email: 'support@example.test', fullName: 'Support Agent', mobileNumber: '9876543210', role: 'SUPPORT_AGENT',
    emailVerified: true, themePreference: 'SYSTEM', budgetAlerts: true },
};

function complaint(id: string, status: string, priority = 'HIGH'): Complaint {
  const now = Date.now();
  return {
    id, ticketNumber: `CMP-${id}`, consumerAccountId: 'account-1', meterId: null, billId: null, complaintType: 'BILLING_DISPUTE',
    priority, status, subject: `Subject ${id}`, description: 'Details', contactNumber: '9876543210', assignedAgentId: null,
    slaDueAt: new Date(now + 48 * 3600_000).toISOString(), slaBreached: false, resolutionSummary: null, resolvedAt: null,
    csatRating: null, csatFeedback: null, createdAt: new Date(now).toISOString(), updatedAt: new Date(now).toISOString(), version: 3,
  } as Complaint;
}

function queue(...items: Complaint[]): Page<Complaint> {
  return { content: items, page: 0, size: 20, totalElements: items.length };
}

function detail(item: Complaint): ComplaintDetail {
  return { complaint: item, comments: [], history: [] };
}

describe('SupportDesk', () => {
  let http: HttpTestingController;

  beforeEach(async () => {
    localStorage.clear();
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    const auth = TestBed.inject(AuthStore);
    http.expectOne('/api/v1/auth/refresh').flush(AGENT);
    await auth.ready;
  });

  afterEach(() => {
    http.verify();
    vi.restoreAllMocks();
    localStorage.clear();
  });

  /** Lets promise continuations land, then waits for the change detection they schedule. */
  async function settle(fixture: ComponentFixture<SupportDesk>): Promise<void> {
    await new Promise((resolve) => setTimeout(resolve));
    await fixture.whenStable();
  }

  async function render(items: Complaint[]): Promise<{ fixture: ComponentFixture<SupportDesk>; view: HTMLElement }> {
    const fixture = TestBed.createComponent(SupportDesk);
    http.expectOne((request) => request.url.startsWith('/api/v1/ops/complaints?')).flush(queue(...items));
    http.expectOne('/api/v1/ops/staff').flush([]);
    await settle(fixture);
    return { fixture, view: fixture.nativeElement };
  }

  function button(view: HTMLElement, text: string): HTMLButtonElement {
    const match = Array.from(view.querySelectorAll('button')).find((candidate) => candidate.textContent?.trim() === text);
    if (!match) throw new Error(`No button labelled "${text}"`);
    return match;
  }

  it('groups the queue into kanban columns, remembers the view and opens the drawer from a card', async () => {
    const assigned = complaint('b', 'ASSIGNED');
    const { fixture, view } = await render([
      complaint('a', 'OPEN', 'CRITICAL'), assigned, complaint('c', 'IN_PROGRESS'), complaint('d', 'RESOLVED'), complaint('e', 'CLOSED', 'LOW'),
    ]);
    expect(view.querySelector('table')).not.toBeNull();

    button(view, 'Kanban').click();
    await settle(fixture);
    expect(localStorage.getItem('gridiq-desk-view')).toBe('kanban');
    expect(view.querySelector('table')).toBeNull();
    expect(button(view, 'Kanban').getAttribute('aria-selected')).toBe('true');

    const columns = Array.from(view.querySelectorAll<HTMLElement>('section[aria-labelledby^="kanban-"]'));
    expect(columns.map((column) => column.querySelector('h2')?.textContent?.replace(/\s+/g, ' ').trim()))
      .toEqual(['Open 1 tickets', 'Assigned 1 tickets', 'In progress 1 tickets', 'Resolved 2 tickets']);
    const resolved = Array.from(columns[3].querySelectorAll('li button')).map((card) => card.textContent ?? '');
    expect(resolved).toHaveLength(2);
    expect(resolved[1]).toContain('CMP-e');
    expect(resolved[1]).toContain('Closed');

    const card = columns[0].querySelector<HTMLButtonElement>('li button')!;
    expect(card.textContent).toContain('Subject a');
    expect(card.textContent).toContain('CMP-a');
    expect(card.textContent).toContain('Critical');
    expect(card.textContent).toContain('Due in 2 days');

    columns[1].querySelector<HTMLButtonElement>('li button')!.click();
    http.expectOne('/api/v1/ops/complaints/b').flush(detail(assigned));
    await settle(fixture);
    expect(view.querySelector('[role="dialog"] h2')?.textContent).toContain('Subject b');
  });

  it('restores the remembered view and still switches when storage is unavailable', async () => {
    localStorage.setItem('gridiq-desk-view', 'kanban');
    const first = await render([complaint('a', 'OPEN')]);
    expect(first.view.querySelector('table')).toBeNull();
    first.fixture.destroy();

    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new DOMException('Blocked', 'SecurityError'); });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new DOMException('Blocked', 'SecurityError'); });
    const { fixture, view } = await render([complaint('a', 'OPEN')]);
    expect(view.querySelector('table')).not.toBeNull();
    button(view, 'Kanban').click();
    await settle(fixture);
    expect(view.querySelector('section[aria-labelledby="kanban-OPEN"]')).not.toBeNull();
  });

  it('shows the triage provider and applies the suggested priority through the ticket update', async () => {
    const ticket = complaint('a', 'OPEN', 'LOW');
    const { fixture, view } = await render([ticket]);
    view.querySelector<HTMLButtonElement>('tbody button')!.click();
    http.expectOne('/api/v1/ops/complaints/a').flush(detail(ticket));
    await settle(fixture);

    button(view, 'Analyse').click();
    http.expectOne({ method: 'POST', url: '/api/v1/ops/ai/complaints/a/triage' }).flush({
      summary: 'Bill dispute', likelyRootCause: 'Estimated read', suggestedPriority: 'HIGH', suggestedResponse: 'We are checking.',
      checks: [], evidence: [], providerUsed: 'deterministic',
    });
    await settle(fixture);
    expect(view.querySelector('[role="dialog"] .badge[title]')?.textContent).toContain('deterministic');

    button(view, 'Apply suggested priority').click();
    const patch = http.expectOne({ method: 'PATCH', url: '/api/v1/ops/complaints/a' });
    expect(patch.request.body).toEqual({ priority: 'HIGH', version: 3 });
    patch.flush(detail({ ...ticket, priority: 'HIGH', version: 4 }));
    await settle(fixture);
    http.expectOne((request) => request.url.startsWith('/api/v1/ops/complaints?')).flush(queue({ ...ticket, priority: 'HIGH', version: 4 }));
    await settle(fixture);
    expect(Array.from(view.querySelectorAll('button')).some((candidate) => candidate.textContent?.includes('Apply suggested priority')))
      .toBe(false);
  });

  it('reloads outages with resolved ones only while the toggle is on', async () => {
    const { fixture, view } = await render([]);
    view.querySelectorAll<HTMLButtonElement>('[role="tab"]')[1].click();
    http.expectOne('/api/v1/ops/outages').flush([]);
    await settle(fixture);
    expect(view.textContent).toContain('No active outages');

    const toggle = Array.from(view.querySelectorAll('label')).find((label) => label.textContent?.includes('Show resolved outages'))!
      .querySelector('input')!;
    toggle.click();
    http.expectOne('/api/v1/ops/outages?includeResolved=true').flush([{
      id: 'o1', incidentCode: 'OUT-1', title: 'Feeder fault', description: 'Restored', severity: 'UNPLANNED', status: 'RESOLVED',
      regionZone: 'Pune', city: 'Pune', affectedPincodes: ['411001'], startsAt: new Date().toISOString(), estimatedRestorationAt: null,
      resolvedAt: new Date().toISOString(), version: 2,
    } as Outage]);
    await settle(fixture);
    expect(view.textContent).toContain('Feeder fault');

    toggle.click();
    http.expectOne('/api/v1/ops/outages').flush([]);
    await settle(fixture);
    expect(view.textContent).not.toContain('Feeder fault');
  });
});
