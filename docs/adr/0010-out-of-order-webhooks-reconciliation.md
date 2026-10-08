# ADR-0010: Out-of-order webhook handling and reconciliation

- Status: Accepted
- Date: 2026-10-08
- Related: architecture §5.2, §8.3, §8.4, §15 (F10, F11, F21)

## Context

Stripe does not guarantee webhook ordering — `payment_intent.processing` can arrive after `payment_intent.succeeded`
— and webhooks can be lost (endpoint down, misconfiguration). Applying events blindly would move payments backwards;
relying on webhooks alone could leave payments stuck forever.

## Decision

**Ordering rule.** Each payment stores `last_stripe_event_at`. An event is applied only if
`event.created ≥ last_stripe_event_at` **and** the transition is allowed by the state machine. Otherwise it is
recorded as `STALE_IGNORED` — not an error. Terminal states are never left, except `SUCCEEDED → REFUNDED`.

**Reconciliation.** Every 5 minutes, payments in non-terminal states with a PaymentIntent id that were not updated
for 10 minutes are retrieved from Stripe and applied through the same state machine with source `RECONCILIATION`.
Drift is logged and counted (`reconciliation.drift`), because it means a webhook was lost or late. The job is
rate-limited and can be triggered manually via `POST /admin/reconciliation/run`.

Concurrent updates from webhooks and reconciliation are serialised by optimistic locking (`@Version`) plus the state
machine, yielding one transition and one event (F21).

## Alternatives considered

- **Apply events in arrival order** — non-monotonic state, duplicate or contradictory downstream events.
- **Always re-fetch the PaymentIntent on every webhook** — simple and order-proof, but doubles Stripe calls and moves
  network I/O into the webhook path.
- **No reconciliation** — a single lost webhook leaves an order `PENDING_PAYMENT` until timeout and possible
  wrongful cancellation of a paid order.

## Consequences

- Payment state is monotonic (F10) and eventually consistent with Stripe within ~15 minutes even if webhooks are
  lost (F11).
- Reconciliation adds periodic Stripe traffic, bounded by the staleness filter and rate limit.
- Stale events are visible via `webhook.stale.ignored`, which helps distinguish reordering from bugs.
