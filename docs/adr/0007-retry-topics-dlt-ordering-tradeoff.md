# ADR-0007: Retry topics + DLT + persisted dead letters; ordering trade-off

- Status: Accepted
- Date: 2026-10-08
- Related: architecture §7.4, §7.5, §15 (F15)

## Context

Consumers will meet transient failures (DB unavailable, optimistic lock conflicts) and permanent ones (malformed
payloads, validation errors, bugs). A poison message must not block a partition, and nothing may be lost silently;
operators need to inspect and replay failed messages.

## Decision

- `ErrorHandlingDeserializer` so deserialization failures become handled errors, not consumer crashes.
- Short blocking retry (2 × 200 ms), then non-blocking retry topics (1 s, 10 s, 60 s), then `<topic>-dlt`.
- Non-retryable errors (deserialization, validation, `NonRetryableEventException`) go straight to the DLT.
- A DLT listener persists every dead letter to `dead_letter_message` (status `NEW | REPLAYED | RESOLVED`).
- Ops can list, replay and resolve dead letters via `/admin/dead-letters`. Replay re-publishes through the outbox
  to the original topic with header `x-replay-of`.
- Unknown event types are skipped (forward compatibility), not dead-lettered.

## Alternatives considered

- **Blocking retries only** — preserves ordering but a poison message stalls the whole partition.
- **DLT without persistence** — dead letters are only visible with Kafka tooling and expire with topic retention.
- **Skip and log** — silent loss, violates quality goal 2.

## Consequences

- Non-blocking retry topics **break per-key ordering**: a retried event may be applied after a later one. This is
  accepted because consumers are order-tolerant: the inbox deduplicates and state machines are monotonic, so a late
  event is either still valid or ignored.
- Additional topics per source topic (retry and DLT, 14 days retention).
- Replay goes through the outbox, so it inherits the same delivery guarantees and tracing.
