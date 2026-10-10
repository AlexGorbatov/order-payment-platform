/** Two lanes and a hand-off: the order's service and the payment's service. */
export function LogoMark({ className }: { className?: string }) {
  return (
    <svg viewBox="0 0 32 32" className={className} aria-hidden>
      <rect width="32" height="32" rx="9" className="fill-accent" />
      <path d="M7 11h8.5c2.5 0 3.5 1.2 4.5 3.5S22 21 25 21" fill="none" stroke="var(--accent-ink)" strokeWidth="2.4" strokeLinecap="round" />
      <circle cx="7" cy="11" r="2.4" fill="var(--accent-ink)" />
      <circle cx="25" cy="21" r="2.4" fill="var(--accent-ink)" />
      <path d="M7 21h7" fill="none" stroke="var(--accent-ink)" strokeOpacity=".55" strokeWidth="2.4" strokeLinecap="round" />
    </svg>
  );
}
