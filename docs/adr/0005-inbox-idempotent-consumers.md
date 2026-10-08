# ADR-0005: Inbox-based idempotent consumers

- Status: Accepted
- Date: 2026-10-08
- Related: architecture §7.2, §10, §15 (F02, F03)

## Context

Kafka delivery is at-least-once: the outbox may publish an event twice (ADR-0004), and a consumer may crash after
committing its DB transaction but before committing the offset. Business effects (create a payment, mark an order
paid, request a refund) must still happen exactly once.

## Decision

- Every consumer inserts `(consumer_group, event_id)` into `inbox_message` with `ON CONFLICT DO NOTHING` in the
  **same** transaction as the business change.
- Zero rows inserted means the event was already processed: the handler is skipped and `inbox.duplicates` is counted.
- Inbox retention (14 days) is longer than topic retention (7 days), so any redelivery is still detected.
- The mechanism ships in `platform-messaging-starter` so both services behave identically (ADR-0014).

## Alternatives considered

- **Natural idempotency only** (state machine rejects repeated transitions) — works for some events, but not for
  events that create things (payments, refunds), and it couples dedupe correctness to every handler's logic.
- **Kafka exactly-once semantics** — only covers Kafka-to-Kafka processing, not side effects in PostgreSQL.
- **Dedupe cache in memory or Redis** — not transactional with the business change; Redis would also be new
  infrastructure without an ADR.

## Consequences

- Effectively-once business effects on top of at-least-once delivery (quality goal 3).
- One extra insert per consumed event and a table that needs periodic cleanup.
- State machines remain monotonic as a second line of defence (ADR-0006), e.g. for replays from the DLT.
