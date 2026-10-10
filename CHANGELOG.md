# Changelog

All notable changes to this project are recorded here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/);
versions follow [Semantic Versioning](https://semver.org/). The event contracts are versioned separately and per event type
([docs/events.md](docs/events.md)).

## [Unreleased]

### Added

- **Web interface** (`web/`, Next.js): a storefront (catalog, cart, order placement with an idempotency key per cart), "my orders" and an
  order page with the order's journey on two lanes (one per service) and payment with Stripe test cards or the Payment Element; a back office
  (every order, figures, filters, search, refunds); an operations console (dead letters: inspect, replay, resolve; reconciliation). Keycloak
  sign-in with PKCE, role-based landing; the services are reached through the app's own server, so they need no CORS. Part of the `apps`
  compose profile on port 8090; the CI `web` job runs lint, types, unit tests and a production build ([web/README.md](web/README.md)).

### Changed

- The demo checkout page (nginx, `demo/checkout`) is replaced by the web interface; `scripts/up.sh` removes orphaned containers.

## [1.0.0] - 2026-10-09

First documented platform milestone: the services, reliability starters, automated tests and manual demo. Maven coordinates remain `0.1.0-SNAPSHOT`; this entry does not create a release artifact or a Git tag.

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
that the compose files are valid and the images build, and publication of overall coverage to the `badges` branch, which the README coverage badge reads (it shows "not found" until the job has run once on `main`).

**Documentation**: architecture with a 22-entry failure-mode matrix mapped to tests, fourteen ADRs with an index, the event
catalog, runbooks for dead letters and webhooks, the testing guide, the demo guide, a backlog.

### Documentation corrections

- Bound reliability claims to the mechanisms actually implemented: HTTP response-recording crash window, finite inbox retention, bounded worker retries and asynchronous compensation.
- Correct `OrderCancelled` reordering behavior, local `PaymentCanceled` semantics, manual webhook retention and the reconciliation scope.
- Review all fourteen ADRs as Accepted; link implementation and test evidence. Update recovery procedures, host startup instructions and GitHub Markdown checks.

### Known limitations

The limitations of this version, with their reasons, are listed once, in [architecture §17](docs/architecture.md#17-out-of-scope--future-work).
