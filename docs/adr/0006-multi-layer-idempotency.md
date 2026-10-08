# ADR-0006: Multi-layer idempotency

- Status: Accepted
- Date: 2026-10-08
- Related: architecture §7.3, §8.2, §15 (F04, F05, F09, F16, F17, F21, F22)

## Context

Duplicates can enter the system at every boundary: clients retry HTTP requests, the platform retries Stripe calls,
Stripe redelivers webhooks, Kafka redelivers events, and reconciliation can race with webhooks. Quality goal 1 — at
most one successful PaymentIntent per order — must hold regardless of where the duplicate originates.

## Decision

Apply idempotency explicitly at each boundary:

| Boundary | Mechanism | Key |
|---|---|---|
| Client → API | `Idempotency-Key` header + request hash, stored in `idempotency_record` (24 h) | (principal, key) |
| Service → Stripe | Stripe `Idempotency-Key` derived from local IDs | `pi-create:{paymentId}`, `pi-cancel:{paymentId}`, `refund:{refundId}` |
| Stripe → webhook | `stripe_webhook_event` primary key (30 days) | `evt_...` |
| Kafka → consumer | Inbox (ADR-0005) | (group, eventId) |
| Relay → Kafka | Idempotent producer | producer id / sequence |
| Business | State machine + optimistic locking (`@Version`) | aggregate version |

HTTP semantics: same key and hash ⇒ stored response replayed (`Idempotent-Replayed: true`); same key, different hash
⇒ 422; still in progress ⇒ 409 with `Retry-After`; a 5xx removes the record so the client may retry.

Stripe keys expire after 24 h, so a payment still `CREATED` after 23 h becomes `INITIATION_FAILED` instead of being
retried with a fresh key that could create a second PaymentIntent.

## Alternatives considered

- **Single idempotency layer (e.g. only the inbox)** — leaves HTTP retries and Stripe retries unprotected.
- **Random Stripe idempotency keys per attempt** — defeats the purpose: a retry after a timeout could create a
  second PaymentIntent (F04, F05).
- **Redis-backed HTTP idempotency store** — higher throughput, but new infrastructure; listed as future work (§17).

## Consequences

- Every duplicate path in the failure matrix has a named mechanism and a test.
- More tables and retention jobs; key derivation rules must be followed by every new Stripe call.
