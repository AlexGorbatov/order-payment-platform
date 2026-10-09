# ADR-0008: DB-backed work queues for external calls

- Status: Accepted
- Date: 2026-10-08
- Related: architecture §7.6, §8.2, §15 (F04–F08, F19)

## Context

Stripe calls are slow, can time out, and may succeed on Stripe's side while the local commit fails. Calling Stripe
from a Kafka listener ties retries to Kafka redelivery and partition progress; calling it inside a DB transaction
holds locks and connections across a network call and makes the outcome ambiguous when the transaction rolls back.

## Decision

- Kafka consumers and webhook ingress **only write state**. They never call Stripe.
- External calls are performed by scheduled workers — `PaymentInitiationWorker`, `PaymentCancellationWorker`,
  `RefundWorker`, `WebhookProcessor`, `ReconciliationJob` — that:
  1. claim due rows (`status`, `next_attempt_at`) with `FOR UPDATE SKIP LOCKED`,
  2. call Stripe **outside** a transaction with an idempotency key derived from local IDs,
  3. persist the result in a new transaction (state change + history + outbox event).
- Transient errors ⇒ exponential backoff with jitter; permanent errors ⇒ terminal state + event; config errors
  (401/403) ⇒ alert, no blind retry.
- `SKIP LOCKED` makes workers safe with multiple instances.

## Alternatives considered

- **Call Stripe from the Kafka listener** — retries become redeliveries, a slow Stripe blocks the partition, and a
  crash after the call but before commit is resolved only by luck.
- **In-memory async executors** — work is lost on restart.
- **Dedicated job framework / message queue for commands** — new infrastructure without clear benefit at this size.

## Consequences

- Work survives restarts; retry state is visible and queryable in the database.
- Latency of at least one worker poll interval between event and Stripe call.
- Enforced statically where possible (ArchUnit: no Stripe/Kafka access in `@Transactional` code, no Stripe in Kafka
  consumers) and dynamically by failure tests F04–F08.

## Addendum (T12): the claim lease and the 23-hour rule of the PaymentInitiationWorker

**Lease.** The claim transaction ends before the Stripe call, so its row locks do too. To keep a second worker (or the
next run of the same one) from claiming the same payment while Stripe is being called, the claim also *leases* the row:
`next_attempt_at = now + lease` (default 5 minutes, larger than the longest gateway call of a whole batch). A worker
that dies leaves a lease that simply expires; the payment is then claimed again and the call repeats with the same
idempotency key (`pi-create:{paymentId}`), so Stripe answers with the PaymentIntent it already created (F05). Only the
single recording transaction after the call clears the lease.

**The 23-hour rule (F08).** Stripe remembers an idempotency key for *at least* 24 hours and then forgets it. A retry
with a forgotten key is not recognised as a repeat: if the first request had in fact succeeded, the retry would create a
**second PaymentIntent** for the same order, and the customer could pay twice. A payment that is still `CREATED` more
than 23 hours after it was created (`payment.initiation.idempotency-window`, one hour of margin for clock skew and a
running call) is therefore **not retried**: it becomes `INITIATION_FAILED` with the code `idempotency_window_elapsed`,
`PaymentInitiationFailed` is published, and order-service cancels the order. The check runs *before* the Stripe call, so
a payment that is too old never reaches Stripe. A customer who still wants to pay places a new order.

**Failure handling in the worker** (all of it recorded in a new transaction together with the outbox event):

| Stripe answer | Effect |
|---|---|
| PaymentIntent created | `attachPaymentIntent` → `REQUIRES_PAYMENT_METHOD`, outbox `PaymentInitiated` |
| `TRANSIENT` (5xx, timeout, 429, 409) | `scheduleRetry` with backoff; after the last attempt `INITIATION_FAILED` (`retries_exhausted`) |
| `PERMANENT` (400, 402, 404...) | `INITIATION_FAILED` with Stripe's code at once, outbox `PaymentInitiationFailed` (F07) |
| `CONFIG`, `IDEMPOTENCY_MISMATCH`, circuit open | not the payment's fault: ERROR log, wait `payment.initiation.deferral`, no attempt counted |
| the payment was cancelled meanwhile | no PaymentIntent is created; one that already exists is cancelled best-effort |

