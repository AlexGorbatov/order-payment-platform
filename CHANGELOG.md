# Changelog

All notable changes to this project are recorded here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/);
versions follow [Semantic Versioning](https://semver.org/). The event contracts are versioned separately and per event type
([docs/events.md](docs/events.md)).

## [1.0.0] - 2026-10-09

First complete version: the platform, its tests, a demo and its documentation.

### Added

**Platform libraries**
- `event-contracts`: the event envelope, twelve payload records and a JSON Schema per event type, with serialization and
  schema tests ([ADR-0011](docs/adr/0011-json-events-explicit-versioning.md)).
- `platform-messaging-starter`: transactional outbox with a polling relay, inbox for idempotent consumers, retry topics
  with a dead-letter topic, persistence of dead letters and an operator API (`/admin/dead-letters`: list, replay through the
  outbox, resolve) ([ADR-0004](docs/adr/0004-transactional-outbox-polling-relay.md),
  [0005](docs/adr/0005-inbox-idempotent-consumers.md), [0007](docs/adr/0007-retry-topics-dlt-ordering-tradeoff.md)).
- `platform-idempotency-starter`: `@Idempotent` HTTP support with request hashing, replay and in-progress handling
  ([ADR-0006](docs/adr/0006-multi-layer-idempotency.md)).

**order-service** (port 8081)
- Orders with a server-side catalog, an order state machine (`PENDING_PAYMENT`, `PAID`, `CANCELLED`, `REFUND_REQUESTED`,
  `REFUNDED`, `REFUND_FAILED`) and a status history; REST API with RFC 9457 errors, `Idempotency-Key` on every state change,
  ownership checks.
- Saga participant: reacts to every payment event (including the automatic refund of a payment that succeeds after
  cancellation), and cancels orders that stay unpaid past the payment timeout.

**payment-service** (port 8082)
- Payment and refund state machines; PaymentIntent creation, cancellation and full refunds by DB-backed workers that call
  Stripe outside any transaction, with idempotency keys derived from local ids, a circuit breaker and error classification
  ([ADR-0008](docs/adr/0008-db-backed-work-queues.md), [0012](docs/adr/0012-stripe-test-mode-strategy.md)).
- Stripe webhook ingestion: signature verification with secret rotation, live-mode rejection, persist-then-acknowledge,
  asynchronous processing with backoff and a `DEAD` state, an ordering rule for out-of-order events
  ([ADR-0009](docs/adr/0009-webhook-ingestion.md), [0010](docs/adr/0010-out-of-order-webhooks-reconciliation.md)).
- Reconciliation with Stripe for lost webhooks, scheduled and on demand (`POST /admin/reconciliation/run`).
- Test-support endpoint (`/api/v1/test-support/...`, off unless enabled) that confirms a PaymentIntent with a Stripe test
  payment method; `LiveModeGuard` refuses to start with anything but a test key.

**Security**: Keycloak realm `opp` with the roles `customer`, `admin`, `ops`; JWT resource servers validating signature,
issuer, audience and expiry ([ADR-0013](docs/adr/0013-keycloak-jwt-no-sync-calls.md)).

**Tests**: unit tests with an 80 % line-coverage gate on `domain` and `application`; ArchUnit layering rules; Testcontainers
integration tests for every starter and service; a contract smoke test against `stripe-mock`; an end-to-end suite that runs both
services as processes against PostgreSQL, Kafka, Keycloak and a stateful Stripe simulator, with `kill -9` of a service and
`docker pause` of Kafka, and the invariants of architecture §14 asserted after every scenario
([docs/testing.md](docs/testing.md)).

**Demo and infrastructure**: Docker Compose (`apps`, `stripe-test`, `observability` profiles), images of both services,
`scripts/up.sh`, `scripts/demo.sh` with six scenarios against `stripe-mock` or real Stripe in test mode, a checkout page
(Keycloak login with PKCE, Stripe Payment Element), signed test webhooks, token scripts ([docs/demo.md](docs/demo.md)).

**CI**: build and verify with coverage reports, the end-to-end suite as a separate job that reuses the built jars, a check
that the compose files are valid and the images build, and the coverage badge.

**Documentation**: architecture with a 22-entry failure-mode matrix mapped to tests, fourteen ADRs with an index, the event
catalog, runbooks for dead letters and webhooks, the testing guide, the demo guide, a backlog.

### Known limitations

- Test mode only, by design. No partial refunds, multi-currency, manual capture or Kubernetes packaging.
- Tracing export, structured (ECS) logs, dashboards and alert rules are not included; metrics, correlation ids and the
  `traceparent` hook in the outbox are ([architecture §13](docs/architecture.md#13-observability)).
- The dead-letter persister defaults to every `*-dlt` topic; each service sets its own pattern
  ([ADR-0007](docs/adr/0007-retry-topics-dlt-ordering-tradeoff.md), [backlog](docs/backlog.md)).
- Error `type` URNs use two prefixes (`urn:problem-type:` and `urn:opp:problem:`).
- Retry topics do not keep per-key order; the consumers are written to tolerate it ([ADR-0007](docs/adr/0007-retry-topics-dlt-ordering-tradeoff.md)).
