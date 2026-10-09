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

## Addendum (2026-10-09, T13 and T15)

Implementation details; the decision above stands.

**Stale webhooks (T13).** "Recorded as `STALE_IGNORED`" is the outcome of the ordering rule
(`Payment.applyStripeStatus` returns `StripeOutcome.STALE_IGNORED`), not a stored webhook status: the event was
processed — nothing is left to retry — so it ends `PROCESSED`, the payment is untouched, and `webhook.stale.ignored`
counts it.

**`observedAt` of a reconciliation report = the second the `retrieve` request started (T15).** A webhook carries
`event.created`; a retrieved PaymentIntent carries no timestamp of its current state, so the watermark has to come
from our side:

- *Request start, not response.* The state Stripe returns is at least as new as the moment the request was sent.
  Taking the response time would claim more than we know: an event created while the request was in flight may or may
  not be in the snapshot, and with a response-time watermark its webhook would be judged stale and dropped even if the
  snapshot missed it. With the request start it is judged by the transition rule, which is always safe.
- *Truncated to whole seconds.* `event.created` has one-second resolution. A watermark of 12:00:00.700 would make a
  webhook of 12:00:00 — possibly *later* in real time — look older and be dropped; `12:00:00 ≥ 12:00:00` lets the
  transition rule decide, exactly as for two webhooks of the same second.
- *Not the server time of Stripe.* There is none in the object; clock skew between the service and Stripe is bounded
  by NTP and, at worst, makes a newer webhook compete on the transition rule instead of being dropped.

**Other choices (T15).** Only a status change counts as drift and is written (history, `last_stripe_event_at`, outbox
event, WARN, `reconciliation.drift{from,to}`); when Stripe agrees nothing is written, so a quiet payment does not move
its watermark and cannot shadow a slightly older webhook. Candidates are locked with `FOR UPDATE SKIP LOCKED` and marked
in `payment.last_reconciled_at` (bookkeeping, not mapped into the aggregate, no version bump), which keeps a checked
payment from being checked again — by this or another instance — for the stale period; a payment that could not be
checked (Stripe unavailable) is released for the next run. Calls go through a rate limiter
(`payment.reconciliation.rate-limit-per-second`, default 5, well below Stripe's test-mode read limit); a run that gets
no permit in time leaves the rest for the next one. F21 is the optimistic lock: if a webhook changed the payment while
Stripe was being asked, the reconciliation reloads, finds the status already applied, and writes nothing.
