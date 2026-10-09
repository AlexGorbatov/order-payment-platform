# Backlog

Ideas and follow-ups that are out of scope for the current task. Each entry: what, why, and the related section/ADR.

## Dead-letter persister: no safe default

- **What:** `platform.dead-letters.persister.topic-pattern` defaults to `.*-dlt`, so a service that does not set it stores
  the dead letters of every service on the broker. The end-to-end tests found it (both services kept each other's dead letters) and fixed it
  by setting the pattern in each service's `application.yml`. The starter could instead derive the pattern from the topics
  its own listeners consume, so that a third service cannot make the same mistake.
- **Why not now:** needs a way to know the consumed topics at auto-configuration time.
- **Related:** architecture §7.4, ADR-0007, runbook `dlq.md`.

## E2E against the container images

- **What:** run the E2E scenarios against the Docker images of the services (compose `apps` profile) instead of
  `java -jar` processes, to cover the `Dockerfile` and the container network as well. CI already builds the images.
- **Why not now:** only `ServiceProcess` and the property wiring in `Platform` know how a service is started, so the swap is
  local (docs/testing.md); the processes were chosen for the real `kill -9` and the lack of network plumbing.
- **Related:** architecture §14.

## E2E: scheduled reconciliation and a Stripe outage

- **What:** two scenarios the simulator could already serve: reconciliation running on its own schedule (the scenarios
  trigger it through the admin endpoint), and a Stripe 5xx/429 storm with the circuit breaker opening and closing (F06).
- **Why not now:** F06 and the schedule are covered by `InitiatePaymentsServiceTest`, `StripePaymentGatewayWireMockTest` and
  `ReconciliationIT`; an E2E version adds wall-clock time for little extra signal.
- **Related:** architecture §8.4, §15 (F06).

## Observability: tracing export, ECS logs, dashboard, alert rules

- **What:** OTLP export with spans across the asynchronous boundaries (consumer → worker → Stripe call → outbox, webhook
  processing), structured ECS logs with trace ids, the gauges `payments.by.status` / `orders.by.status`, a Prometheus
  endpoint with a Grafana dashboard and alert rules for the signals the runbooks name.
- **Why not now:** v1.0.0 ships the metrics, the correlation ids and the `traceparent` hook in the outbox, and the backends
  as skeleton compose services; wiring them is a separate piece of work (architecture §13).
- **Related:** architecture §13, runbooks.

## Unify the problem `type` prefixes

- **What:** the services answer errors with `urn:problem-type:<code>`, the idempotency starter with `urn:opp:problem:<code>`.
  One prefix would make the API uniform.
- **Why not now:** changing a published `type` is a (small) API change; no client depends on it yet.
- **Related:** architecture §11, ADR-0006.
