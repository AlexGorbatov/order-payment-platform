/** Two lanes and a hand-off: the order's service and the payment's service. */
export function LogoMark({ className }: { className?: string }) {
  return (
    <svg viewBox="0 0 32 32" className={className} aria-hidden>
      <defs>
        <linearGradient id="opp-logo" x1="0" y1="0" x2="32" y2="32" gradientUnits="userSpaceOnUse">
          <stop offset="0" stopColor="#22d6f4" />
          <stop offset=".55" stopColor="#1a7cfb" />
          <stop offset="1" stopColor="#8b5cf6" />
        </linearGradient>
      </defs>
      <rect width="32" height="32" rx="9" fill="url(#opp-logo)" />
      <path d="M7 11h8.5c2.5 0 3.5 1.2 4.5 3.5S22 21 25 21" fill="none" stroke="#fff" strokeWidth="2.4" strokeLinecap="round" />
      <circle cx="7" cy="11" r="2.4" fill="#fff" />
      <circle cx="25" cy="21" r="2.4" fill="#fff" />
      <path d="M7 21h7" fill="none" stroke="#fff" strokeOpacity=".55" strokeWidth="2.4" strokeLinecap="round" />
    </svg>
  );
}
