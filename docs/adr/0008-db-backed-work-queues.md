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
