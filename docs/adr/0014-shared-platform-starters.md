# ADR-0014: Shared platform starters (vs. per-service copies)

- Status: Accepted
- Date: 2026-10-08
- Related: architecture §4, §7.1–§7.4, §10

## Context

Both services need identical reliability infrastructure: outbox and relay, inbox, Kafka error handling with retry
topics and DLT persistence, dead-letter admin API, and HTTP idempotency. Subtle differences between two copies would
undermine the guarantees, and each mechanism needs its own failure tests.

## Decision

Provide the infrastructure as Spring Boot auto-configured starters in the same repository:

- `libs/platform-messaging-starter` — outbox, relay, inbox, Kafka error handling, DLT persister, dead-letter admin API.
- `libs/platform-idempotency-starter` — `@Idempotent` HTTP support.
- `libs/event-contracts` — envelope, payload records and JSON Schemas (no Kafka dependencies).

Starters ship their own Flyway migrations under `db/migration/platform` with versions `V1000+`, so they never collide
with service migrations. Each starter has its own integration tests against Testcontainers PostgreSQL and Kafka.

## Alternatives considered

- **Copy the code into each service** — duplicated fixes, divergent behaviour, duplicated tests.
- **Plain shared library without auto-configuration** — every service repeats the wiring and can get it wrong.
- **Separate repository / published artifacts** — versioning overhead with no benefit for two services in one repo.

## Consequences

- One implementation and one test suite per reliability mechanism; services only add configuration.
- Starters must stay generic: no service-specific business code.
- A change to a starter affects both services at once; the full build verifies both.
