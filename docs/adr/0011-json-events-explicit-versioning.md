# ADR-0011: JSON events with explicit versioning (vs. Avro + Schema Registry)

- Status: Accepted
- Date: 2026-10-08
- Related: architecture §9.2, §9.3, §17

## Context

Two services exchange twelve event types. Contracts must be explicit, testable and evolvable without breaking the
other side, and readable when debugging topics, DLT records and outbox rows. The project must stay runnable with a
single `docker compose up` and no extra infrastructure.

## Decision

- Events are JSON with a common envelope: `eventId` (UUIDv7), `eventType`, `eventVersion`, `aggregateType`,
  `aggregateId`, `partitionKey`, `occurredAt`, `producer`, `correlationId`, `causationId`, `payload`.
- Kafka headers carry `eventType`, `eventVersion`, `correlationId`, `traceparent`.
- Payloads are Java records in `libs/event-contracts`, with a JSON Schema per event type validated in tests.
- Versioning: additive changes keep the version; breaking changes introduce a new version that is published in
  parallel during migration. Topic names carry a major version (`order.events.v1`).
- Consumers skip unknown event types (forward compatibility).

## Alternatives considered

- **Avro + Schema Registry** — compact, with registry-enforced compatibility, but adds infrastructure and tooling, and
  makes payloads opaque in logs and DLT inspection. Listed as future work (§17).
- **Protobuf** — similar trade-offs to Avro.
- **Untyped JSON without schemas** — no contract enforcement; breaking changes are discovered in production.

## Consequences

- Human-readable messages everywhere (topics, outbox, DLT, `dead_letter_message`).
- Compatibility is enforced by schema tests and code review rather than a registry; discipline is required.
- Larger messages than binary formats — irrelevant at this scale.
