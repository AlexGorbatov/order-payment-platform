# ADR-0004: Transactional outbox with polling relay (vs. CDC, vs. dual write)

- Status: Accepted
- Date: 2026-10-08
- Related: architecture §7.1, §10, §15 (F01, F02)

## Context

A state change (e.g. order created, payment succeeded) and the event announcing it must be published together:
an event without the state change, or a state change without the event, breaks the saga. PostgreSQL and Kafka cannot
share a transaction.

## Decision

- The business change and an `outbox_event` row are written in **one** DB transaction. `OutboxPublisher` requires an
  active transaction and fails otherwise.
- An `OutboxRelay` polls unpublished rows with `FOR UPDATE SKIP LOCKED`, sends them to Kafka with an idempotent
  producer (`acks=all`, `enable.idempotence=true`), waits for the acknowledgement and sets `published_at`.
- On a send failure the batch stops, preserving per-key order; attempts and last error are recorded.
- The `traceparent` is captured into outbox headers at write time and restored on send (§13).
- Published rows are deleted after 7 days.
- The relay is the **only** sanctioned place where a Kafka send happens while a DB transaction holds the claimed rows.

## Alternatives considered

- **Dual write** (commit, then `KafkaTemplate.send`) — a crash between the two loses the event or publishes a
  phantom one; unacceptable given quality goal 2 ("no lost events").
- **Debezium CDC** — lower latency and no polling load, but requires Kafka Connect and connector operations; out of
  scope for v1 (§17 lists it as future work).
- **Kafka transactions only** — do not cover the PostgreSQL write.

## Consequences

- Events are delivered at least once: a crash after send and before marking published re-publishes the event (F02);
  consumers must deduplicate (ADR-0005).
- Kafka outages do not block business transactions; rows accumulate and drain after recovery (F01).
- Polling adds latency (poll interval) and DB load; `outbox.pending` and `outbox.oldest.age.seconds` are monitored.
