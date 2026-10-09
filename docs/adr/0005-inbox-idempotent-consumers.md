# ADR-0005: Inbox-based idempotent consumers

- Status: Accepted
- Date: 2026-10-08 (implementation details added with the inbox implementation)
- Related: architecture §7.2, §10, §15 (F02, F03), ADR-0004, ADR-0007

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

## Implementation details

`InboxGuard` in `libs/platform-messaging-starter` (package `…platform.messaging.inbox`, properties `platform.inbox.*`).

### The contract of `executeOnce(consumerGroup, eventId, action)`

One database transaction contains **both** the `INSERT INTO inbox_message … ON CONFLICT DO NOTHING` and the action
(the business change). Three outcomes:

| Situation | Inbox insert | Action | Transaction | Result |
|---|---|---|---|---|
| first delivery | 1 row | runs | commits both | `true` |
| redelivery after a commit | 0 rows | **not called** | nothing to commit | `false`, `inbox.duplicates{consumerGroup}` +1 |
| the action throws | inserted, then rolled back | partially ran | **rolls back both** | exception propagates; no inbox row remains |

The key is the third row: "an inbox row exists" is equivalent to "the business change committed", never "processing was
attempted". Consequences:

- **F03 (crash after commit, before the offset commit).** The record is redelivered, the insert finds the row, the action
  is skipped, the listener returns and the offset is committed. The business effect happened exactly once.
- **Retries are safe.** A failed attempt leaves nothing behind, so blocking retries, retry topics and DLT replays are
  not mistaken for duplicates. This is why **replaying a dead letter with the same `eventId` is not rejected by the
  inbox**: the processing that failed rolled back, and its inbox row with it (verified by `DeadLetterAdminIT`).
  The opposite also holds and is wanted: a dead letter whose event *was* processed (for example a duplicate whose
  redelivery failed later) is skipped as a duplicate when replayed.
- **Concurrent deliveries** of the same event (same group) are serialized by the unique index: the second insert waits
  for the first transaction and then sees its outcome (duplicate if it committed, runs if it rolled back).
- The key includes the consumer group, so two groups consuming one topic each process every event once.

The guard joins an already active transaction; call it from the `@KafkaListener` method with the listener's group id.
Idempotency is keyed by the envelope `eventId`; redelivery of the *same logical* event under a new `eventId` (a producer
bug) is not detected here — state machines (ADR-0006) remain the second line of defence.

### Retention

`InboxCleanup` deletes rows older than `platform.inbox.cleanup.retention` (default 14 days) in batches of 1000, each in
its own transaction. The retention must exceed the topic retention (7 days) so that any record still readable from a
topic is still recognised; the property is validated to be at least one day, and operators must keep it above the topic
retention if they change either.

