# ADR-0003: Hexagonal architecture per service

- Status: Accepted
- Date: 2026-10-08
- Related: architecture §4, §7.6

## Context

Each service integrates with several technologies — HTTP, Kafka, PostgreSQL/JPA, Stripe, webhooks — while the core
value is in state machines and invariants (no double charge, monotonic transitions). Those rules must be unit-testable
without infrastructure, and the integration code must be replaceable by test doubles (WireMock, stripe-mock).

## Decision

Every service uses a ports-and-adapters layout under `com.altronixsoft.opp.<service>`:

- `domain` — aggregates, value objects (`Money`), state machines. No Spring, JPA, Jackson, Kafka or Stripe.
- `application` — use cases and port interfaces; depends on `domain` and ports only. Spring transaction
  annotations are allowed.
- `adapter.in.{web,kafka,webhook}` and `adapter.out.{persistence,stripe,messaging}` — technology-specific code,
  depending inward only; adapters never depend on each other.
- `config` — Spring wiring and validated `@ConfigurationProperties` records.

JPA entities live in `adapter.out.persistence` and are mapped explicitly to domain types (no Lombok/MapStruct).
The rules are enforced by ArchUnit tests in every service, so violations fail the build.

## Alternatives considered

- **Classic layered (controller/service/repository)** — familiar, but domain logic tends to absorb JPA entities and
  framework annotations, making state machines hard to test in isolation.
- **Package-by-feature without layering** — good for cohesion, but provides no structural guarantee that Stripe or
  Kafka calls stay out of transactional or consumer code.
- **Separate Maven modules per layer** — strongest isolation, but heavy for two small services; ArchUnit gives the
  same guarantees at lower cost.

## Consequences

- Domain logic is covered by fast unit tests; a JaCoCo gate (≥ 80% lines) applies to `domain` and `application`.
- Explicit mapping between JPA entities, domain objects and DTOs costs some boilerplate.
- Architectural drift is caught in CI rather than in review.
