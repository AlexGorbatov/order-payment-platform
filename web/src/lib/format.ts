import type { Money } from "./types";

const money = new Map<string, Intl.NumberFormat>();

/** 3499 EUR cents -> "€34.99". Amounts are minor units everywhere; this is the only place that divides. */
export function formatMoney({ amountMinor, currency }: Money): string {
  let formatter = money.get(currency);
  if (!formatter) {
    formatter = new Intl.NumberFormat("en-IE", { style: "currency", currency });
    money.set(currency, formatter);
  }
  return formatter.format(amountMinor / 100);
}

export function shortId(id: string): string {
  return id.length > 13 ? `${id.slice(0, 8)}…${id.slice(-4)}` : id;
}

const dateTime = new Intl.DateTimeFormat("en-GB", {
  day: "2-digit",
  month: "short",
  hour: "2-digit",
  minute: "2-digit",
  hour12: false,
});

const clock = new Intl.DateTimeFormat("en-GB", {
  hour: "2-digit",
  minute: "2-digit",
  second: "2-digit",
  hour12: false,
});

export function formatDateTime(iso: string): string {
  return dateTime.format(new Date(iso));
}

export function formatClock(iso: string): string {
  return clock.format(new Date(iso));
}

/** "+3.2 s" between two instants, for the journey of an order. */
export function formatOffset(fromIso: string, toIso: string): string {
  const seconds = (new Date(toIso).getTime() - new Date(fromIso).getTime()) / 1000;
  if (seconds < 0.05) return "+0 s";
  if (seconds < 60) return `+${seconds.toFixed(1)} s`;
  const minutes = Math.floor(seconds / 60);
  return `+${minutes} min ${Math.round(seconds - minutes * 60)} s`;
}

export function formatRelative(iso: string, now = Date.now()): string {
  const seconds = Math.round((now - new Date(iso).getTime()) / 1000);
  if (seconds < 5) return "just now";
  if (seconds < 60) return `${seconds} s ago`;
  const minutes = Math.round(seconds / 60);
  if (minutes < 60) return `${minutes} min ago`;
  const hours = Math.round(minutes / 60);
  if (hours < 24) return `${hours} h ago`;
  return formatDateTime(iso);
}
