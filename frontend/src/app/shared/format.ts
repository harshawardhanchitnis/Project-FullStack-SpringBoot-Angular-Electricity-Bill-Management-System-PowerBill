import { Pipe, PipeTransform } from '@angular/core';

const INR = new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', minimumFractionDigits: 2 });
const INR_SHORT = new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 });
const NUMBER = new Intl.NumberFormat('en-IN', { maximumFractionDigits: 2 });
const DATE = new Intl.DateTimeFormat('en-IN', { day: 'numeric', month: 'short', year: 'numeric' });
const DATE_TIME = new Intl.DateTimeFormat('en-IN', {
  day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit', timeZone: 'Asia/Kolkata',
});
const MONTH = new Intl.DateTimeFormat('en-IN', { month: 'long', year: 'numeric', timeZone: 'UTC' });
const MONTH_SHORT = new Intl.DateTimeFormat('en-IN', { month: 'short', year: '2-digit', timeZone: 'UTC' });

export function inr(value: number | null | undefined, short = false): string {
  return value === null || value === undefined ? '—' : (short ? INR_SHORT : INR).format(value);
}

export function kwh(value: number | null | undefined): string {
  return value === null || value === undefined ? '—' : `${NUMBER.format(value)} kWh`;
}

export function day(value: string | null | undefined): string {
  if (!value) return '—';
  const date = value.length === 10 ? new Date(`${value}T00:00:00`) : new Date(value);
  return DATE.format(date);
}

export function moment(value: string | null | undefined): string {
  return value ? DATE_TIME.format(new Date(value)) : '—';
}

export function period(value: string, short = false): string {
  return (short ? MONTH_SHORT : MONTH).format(new Date(`${value}-01T00:00:00Z`));
}

export function relative(value: string): string {
  const seconds = Math.round((new Date(value).getTime() - Date.now()) / 1000);
  const format = new Intl.RelativeTimeFormat('en', { numeric: 'auto' });
  const units: [Intl.RelativeTimeFormatUnit, number][] = [['day', 86400], ['hour', 3600], ['minute', 60]];
  for (const [unit, size] of units) {
    if (Math.abs(seconds) >= size) return format.format(Math.round(seconds / size), unit);
  }
  return format.format(seconds, 'second');
}

export function humanize(value: string | null | undefined): string {
  if (!value) return '';
  const text = value.replaceAll('_', ' ').toLowerCase();
  return text.charAt(0).toUpperCase() + text.slice(1);
}

@Pipe({ name: 'inr' })
export class InrPipe implements PipeTransform {
  transform(value: number | null | undefined, short = false): string {
    return inr(value, short);
  }
}

@Pipe({ name: 'kwh' })
export class KwhPipe implements PipeTransform {
  transform(value: number | null | undefined): string {
    return kwh(value);
  }
}

@Pipe({ name: 'day' })
export class DayPipe implements PipeTransform {
  transform(value: string | null | undefined): string {
    return day(value);
  }
}

@Pipe({ name: 'moment' })
export class MomentPipe implements PipeTransform {
  transform(value: string | null | undefined): string {
    return moment(value);
  }
}

@Pipe({ name: 'period' })
export class PeriodPipe implements PipeTransform {
  transform(value: string, short = false): string {
    return period(value, short);
  }
}

@Pipe({ name: 'relative' })
export class RelativePipe implements PipeTransform {
  transform(value: string): string {
    return relative(value);
  }
}

@Pipe({ name: 'humanize' })
export class HumanizePipe implements PipeTransform {
  transform(value: string | null | undefined): string {
    return humanize(value);
  }
}

export const FORMAT_PIPES = [InrPipe, KwhPipe, DayPipe, MomentPipe, PeriodPipe, RelativePipe, HumanizePipe] as const;
