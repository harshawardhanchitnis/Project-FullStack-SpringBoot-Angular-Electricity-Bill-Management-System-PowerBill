import { humanize, inr, kwh, period } from './format';
import { renderMarkdown } from '../layout/copilot';
import { passwordStrength } from '../features/auth/register';
import { slaText } from '../features/customer/support';
import { Complaint } from '../core/models';

describe('formatting', () => {
  it('formats rupees with Indian digit grouping and kWh with units', () => {
    expect(inr(1234567.5)).toBe('₹12,34,567.50');
    expect(inr(null)).toBe('—');
    expect(inr(2604.18, true)).toBe('₹2,604');
    expect(kwh(412.5)).toBe('412.5 kWh');
    expect(humanize('SOLAR_OFF_PEAK')).toBe('Solar off peak');
    expect(humanize('UPI')).toBe('UPI');
    expect(humanize('NET_BANKING')).toBe('Net banking');
    expect(humanize('SLA_BREACHED')).toBe('SLA breached');
    expect(period('2026-10')).toBe('October 2026');
  });
});

describe('Copilot markdown', () => {
  it('escapes HTML before applying the markdown subset', () => {
    const html = renderMarkdown('**Total** <img src=x onerror=alert(1)>\n- one\n- two\n> quote');
    expect(html).toContain('<strong>Total</strong> &lt;img src=x onerror=alert(1)&gt;');
    expect(html).toContain('<ul><li>one</li><li>two</li></ul>');
    expect(html).toContain('<blockquote>quote</blockquote>');
    expect(html).not.toContain('<img');
  });
});

describe('password strength', () => {
  it('requires twelve characters and rewards variety', () => {
    expect(passwordStrength('Short1!').score).toBe(0);
    expect(passwordStrength('alllowercaseletters').label).toBe('Fair');
    expect(passwordStrength('Mixed-Case-Passphrase-42').score).toBe(5);
  });
});

describe('SLA labels', () => {
  const base = { status: 'OPEN', slaBreached: false } as Complaint;

  it('flags breached and nearly due tickets', () => {
    const late = slaText({ ...base, slaDueAt: new Date(Date.now() - 3600_000).toISOString() });
    expect(late.tone).toContain('text-bad');
    const soon = slaText({ ...base, slaDueAt: new Date(Date.now() + 2 * 3600_000).toISOString() });
    expect(soon.tone).toContain('text-warn');
    const resolved = slaText({ ...base, status: 'RESOLVED', slaDueAt: new Date().toISOString() });
    expect(resolved.text).toBe('Within SLA');
  });
});
