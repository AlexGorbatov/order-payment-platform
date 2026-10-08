# ADR-0004: Transactional outbox with polling relay (vs. CDC, vs. dual write)

- Status: Accepted
- Date: 2026-10-08 (implementation details added with T04)
- Related: architecture §7.1, §7.5, §10, §13, §15 (F01, F02), ADR-0005, ADR-0014

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

## Implementation details

Implemented in `libs/platform-messaging-starter` (package `…platform.messaging.outbox`, auto-configured, properties
`platform.outbox.*`). Services only need `spring.kafka.*`: the starter registers its Flyway location
`classpath:db/migration/platform` itself, so `outbox_event` is created even when a service customizes
`spring.flyway.locations` (Flyway collapses the location when the default `classpath:db/migration` already covers it).

### Why a Kafka send inside a transaction that holds row locks is acceptable

The rule of this project (ADR-0008) is that remote calls never run inside a database transaction. `OutboxRelay` is the
single deliberate exception, and the reasons it is safe are specific:

- **The locks are on rows only the relay touches.** Business transactions merely `INSERT` into `outbox_event`; an
  insert never waits for a lock on other rows. A slow or unavailable Kafka therefore cannot block request handling or
  hold up any business table — the failure mode is a growing backlog (F01), not an outage.
- **The lock is short and bounded.** One transaction per cycle, at most `batch-size` rows, each send bounded by
  `ack-timeout` (the producer's `delivery.timeout.ms` is derived from it, so a hung broker cannot extend it). One
  connection per relay instance is held for that time.
- **`FOR UPDATE SKIP LOCKED` scales it out.** Another instance does not wait; it claims *other* rows. Holding the lock
  until the broker acknowledged is exactly what makes a claim exclusive — no second instance can send the same row
  concurrently.
- **The alternative is worse.** Claiming in one transaction, sending outside, and marking in a second transaction
  needs a lease/status column, a reaper for abandoned claims, and still duplicates on a crash. It buys no stronger
  guarantee for more moving parts.

**Risk:** if the process dies, or the commit fails, *after* Kafka acknowledged a record and *before* `published_at` is
committed, the row stays unpublished and is sent again (F02). The outbox is therefore **at least once**; the consumer
inbox (`(consumer_group, event_id)`, ADR-0005) turns that into effectively once. The same applies when an
acknowledgement times out although the broker eventually appended the record (a paused or slow broker): the row is
retried and the topic may hold the event twice.

### Row and message format

- `id` is the envelope `eventId`; `aggregate_*`, `partition_key`, `topic`, `event_type`, `event_version` are copied from
  the envelope for diagnostics and routing.
- `payload` (jsonb) holds the **complete envelope** — it is the Kafka record value. Consumers read the envelope with
  `EventSerde`; storing only `envelope.payload` would lose `occurredAt`, `correlationId`, `causationId` and the
  producer. jsonb normalizes whitespace and key order, so the wire JSON is semantically, not byte-for-byte, the
  serialized form; no consumer may depend on property order.
- `headers` (jsonb) holds the Kafka headers `eventType`, `eventVersion`, `correlationId` and, when a span is
  current, `traceparent` — captured at write time, sent unchanged by the relay.
- The Kafka key is `partition_key` (the order id), so `order.events.v1` and `payment.events.v1` are ordered per order.

### Ordering

- `created_at` is `clock_timestamp()` at insert, distinct per statement; the relay orders by `(created_at, id)`.
  Writers of one aggregate are serialized by that aggregate's row lock / `@Version` check, and the outbox insert happens
  while the lock is held, so for one key insertion order equals commit order. Across different keys no order is
  promised or needed.
- A failed send stops the whole batch (a later row must not overtake an earlier one), records `attempts + 1` and
  `last_error`, and the next cycle starts again with the same head row.
- **Several instances.** `SKIP LOCKED` alone is not enough: instance A may hold the older rows of a key (uncommitted)
  while instance B claims the key's newer rows and sends them first. After claiming, the relay therefore drops every key
  that still has an older unpublished row it does not hold itself (`OutboxRepository.keysBlockedByOlderRows`, backed by
  a partial index on `(partition_key, created_at, id)`); the key is left to the instance that owns the older row and
  is picked up in a later cycle. The price is some lost parallelism when the events of one key are interleaved with
  others across instances; correctness over throughput.
- Sends are sequential (send, await ack, next). Pipelining a batch would be faster but a failure in the middle would
  let later rows of a key overtake the failed one; a throughput optimisation can come later if `outbox.pending` shows
  it is needed.

### Producer

A dedicated producer private to the outbox (`KafkaOutboxSender`): `acks=all`, `enable.idempotence=true`,
`linger.ms=0`, `delivery.timeout.ms = max.block.ms = ack-timeout`, `request.timeout.ms = ack-timeout / 2`. Bootstrap
servers and security come from `spring.kafka.*`; serializers and transactions of the service's own producer are not
inherited. It is not exposed as a `KafkaTemplate` bean, so it does not displace the service's own.

### Operations

- Publishing without a transaction fails with `IllegalTransactionStateException` (`Propagation.MANDATORY` plus an
  explicit guard).
- `platform.outbox.relay.enabled=false` turns the relay off for an instance that should only write events;
  `platform.outbox.cleanup.*` controls retention (default 7 days, deleted in batches of 1000 in separate
  transactions; unpublished rows are never deleted).
- Metrics: `outbox.pending`, `outbox.oldest.age.seconds` (one cached query, `metrics.cache-ttl`),
  `outbox.publish.success|failure` and `outbox.publish.latency` tagged by `topic`, `outbox.cleanup.deleted`.
  `outbox.oldest.age.seconds` is the alert signal: it grows whether the relay is slow, failing or not running.
- The relay and cleanup run on Spring's shared task scheduler, whose default pool has one thread; a relay cycle can
  block for up to `batch-size × ack-timeout` while Kafka is down. Services with other scheduled work should raise
  `spring.task.scheduling.pool.size`.

### Verified by tests (Testcontainers PostgreSQL + Kafka)

Delivery with key and headers; rollback leaves no row and no event; publishing outside a transaction fails; Kafka paused
with `docker pause` (rows accumulate, `attempts` grows on the head row only, gauges and failure counter react) and, after
unpausing, every event arrives in per-key order (F01); two relays with simultaneously open transactions claim disjoint
rows; an instance does not overtake an older row of the same key held by another instance; relays running in a loop
produce neither duplicates nor reordering; retention cleanup.

