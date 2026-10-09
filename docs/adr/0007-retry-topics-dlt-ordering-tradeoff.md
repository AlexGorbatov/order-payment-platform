# ADR-0007: Retry topics + DLT + persisted dead letters; ordering trade-off

- Status: Accepted
- Date: 2026-10-08 (implementation details added with the consumer implementation)
- Related: architecture §7.2, §7.4, §7.5, §10, §11, §15 (F03, F15), ADR-0005, ADR-0006

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

## Why giving up per-key ordering on the retry path is acceptable

A record that fails after its blocking retries moves to `<topic>-retry-0` and is delivered again after the configured
delay, while later records of the same key are processed on the main topic in the meantime. So the order of two events
of one order can be inverted — but only on the **failure path**: the normal path and the short blocking retries (2 ×
200 ms, enough for an optimistic-lock conflict or a brief connection blip) keep the order intact.

This is acceptable because every consumer is built to be order-tolerant, with two independent mechanisms:

1. **Inbox (ADR-0005)** — an event is applied at most once, however many times and by whichever path it arrives, so a
   delayed copy can never double-apply.
2. **Monotonic state machines (ADR-0006, architecture §5)** — an event is applied only if the transition is still
   allowed from the current state; otherwise it is recorded as stale and ignored. Examples from this system:
   - `OrderCancelled` overtakes a retried `OrderCreated` in payment-service: the cancel is remembered
     (`payment.cancel_requested`), and when `OrderCreated` finally arrives the payment is created already marked for
     cancellation instead of being charged;
   - a delayed `PaymentActionRequired` reaches order-service after `PaymentSucceeded`: the order is already `PAID`, the
     transition is not allowed, the event is ignored;
   - `PaymentRefunded` before `PaymentSucceeded` cannot happen across the saga (a refund is only requested for a paid
     order), and Stripe webhooks are already treated as unordered (§8.3).

A consumer that cannot tolerate reordering must not rely on retry topics for that event type: throw
`NonRetryableEventException` (straight to the DLT) or keep its handling idempotent and commutative.

## Implementation details

Auto-configured in `libs/platform-messaging-starter` (package `…platform.messaging.consumer` and `…deadletter`).

### Consumer defaults

Applied as the lowest-priority property source, so a service can override any of them
(`platform.consumer.defaults.enabled=false` switches them off):

| Setting | Value | Why |
|---|---|---|
| key/value deserializer | `ErrorHandlingDeserializer` → `StringDeserializer` | an undecodable record is a handled error, not a crashed consumer |
| `enable.auto.commit` | `false` | offsets are committed by the container |
| listener ack mode | `RECORD` | the offset moves only after the listener (and its transaction) returned for that record |
| `isolation.level` | `read_committed` | never read records of aborted producer transactions |
| `auto.offset.reset` | `earliest` | a new consumer group must not skip events published before it joined |

Listeners receive the record value as raw JSON and call `EventEnvelopeReader`. This is deliberate: the dead letter then
holds the **exact bytes received**, including fields this consumer version does not know, so a later replay loses
nothing. Malformed JSON raises `EventSerdeException` (non-retryable, F15); an unknown event type or version is
skipped with an INFO log (architecture §9.3), neither retried nor dead-lettered.

### Policy (`platform.consumer.retry.*`)

| Property | Default | Meaning |
|---|---|---|
| `blocking-retries` / `blocking-interval` | 2 / 200 ms | retries on the source topic (`DefaultErrorHandler`) |
| `topic-delays` | 1 s, 10 s, 60 s | one retry topic per entry: `<topic>-retry-0`, `-1`, `-2` |
| `topic-partitions`, `topic-replication-factor`, `auto-create-topics` | 3, 1, true | for retry and DLT topics created at startup |
| `non-retryable` | empty | extra exception types that skip retries |

Non-retryable out of the box: `DeserializationException`, `NonRetryableEventException`, `EventSerdeException` and
bean-validation errors. They skip the blocking retries and the retry topics and go straight to `<topic>-dlt`. Retry and
DLT topics are named from the source topic, and the infrastructure script pre-creates them with 14 days retention.

### Dead letters

- A message-listener container (not an `@KafkaListener`, so the retry machinery does not wrap it) subscribes to
  `.*-dlt` with its own group (`<application>-dlt-persister`) and stores every record in `dead_letter_message`: payload
  (`bytea`, exactly as received), key, all headers as text, the exception class (the cause of Spring's listener
  exception) and message, the **original** topic (retry suffix stripped), and the coordinates of both the DLT record
  (unique, which makes the persister idempotent) and the failing record.
- It never skips a record. While PostgreSQL is unavailable it retries the same record indefinitely. A record whose
  *content* PostgreSQL rejects (NUL characters, for example, which `text`/`jsonb` cannot hold, are replaced when
  stored) is stored again without its headers and with a note, rather than blocking the persister forever.
- `dlt.messages{topic}` (topic = original topic) counts every new dead letter and is the alert signal.
- The log line for a dead letter carries coordinates only; payloads (customer ids) are not logged.

### Replay and resolve (`/admin/dead-letters`, role `OPS`)

- `POST /{id}/replay` re-publishes the stored payload, unchanged, to the **original topic through the outbox** and sets
  `REPLAYED`, in one transaction: either both happen or neither. Headers `eventType`, `eventVersion`, `correlationId`,
  `traceparent` are carried over (missing ones are filled from the envelope) and `x-replay-of` holds the dead letter
  id. The outbox row gets a new id; the envelope keeps its `eventId`.
- Replay is allowed **once** per dead letter (second call or a resolved one: `409`). It is serialized by a row lock, so
  concurrent calls queue the event once. A payload that is not an event envelope cannot be replayed (`422`); resolve it.
- Replaying the same `eventId` is not blocked by the inbox, because the failed processing rolled back its inbox row
  (ADR-0005). If the replayed event fails again it becomes a **new** dead letter; resolve the old one with a comment.
- `POST /{id}/resolve` with a mandatory comment closes a dead letter (`RESOLVED`, comment in `note`).
- The starter configures **no security**: the controller carries `@PreAuthorize("hasRole('OPS')")`, the service owns the
  filter chain and must enable method security. The controller is only registered in servlet applications that have
  Spring Security, and startup **fails** if method security is not enabled, so the endpoints cannot be exposed without
  their role check.
- Operational steps: `docs/runbooks/dlq.md`.


## Addendum: which dead letters a service stores

The persister subscribes to the pattern `platform.dead-letters.persister.topic-pattern`, default `.*-dlt`. Both services share
one broker, so with the default each service stored the dead letters of the other one's consumer, which contradicts the
rule above (a dead letter lives in the service that consumed the record) and let an operator replay an event from the
wrong service. The end-to-end poison-message scenario found it. Each service now sets the pattern to the dead-letter topic of
the topic it consumes (`payment.events.v1-dlt` in order-service, `order.events.v1-dlt` in payment-service). The default
stays as it is; deriving it from the service's own listeners is in the backlog.
