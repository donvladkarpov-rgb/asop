import { formatDateTime } from './dates';

export function formatDate(date: string | undefined): string {
  return formatDateTime(date);
}

export function cn(...classes: (string | false | null | undefined)[]): string {
  return classes.filter(Boolean).join(' ');
}

export function statusColor(status: string): string {
  const colors: Record<string, string> = {
    active: 'green',
    inactive: 'gray',
    blocked: 'red',
    deleted: 'red',
    open: 'green',
    closed: 'gray',
    expired: 'orange',
  };
  return colors[status] || 'gray';
}
