# Backlog

Ideas and follow-ups that are out of scope for the current task. Each entry: what, why, and the related section/ADR.

## Dead-letter persister: no safe default

- **What:** `platform.dead-letters.persister.topic-pattern` defaults to `.*-dlt`, so a service that does not set it stores
  the dead letters of every service on the broker. T17 found it (both services kept each other's dead letters) and fixed it
  by setting the pattern in each service's `application.yml`. The starter could instead derive the pattern from the topics
  its own listeners consume, so that a third service cannot make the same mistake.
- **Why not now:** needs a way to know the consumed topics at auto-configuration time; out of scope for T17.
- **Related:** architecture §7.4, ADR-0007, runbook `dlq.md`.

## E2E against the container images

- **What:** run the E2E scenarios against Docker images of the services (compose `apps` profile, T18) instead of
  `java -jar` processes, to cover the `Dockerfile` and the container network as well.
- **Why not now:** the images do not exist yet; only `ServiceProcess` and the property wiring in `Platform` know how a
  service is started, so the swap is local (docs/testing.md).
- **Related:** architecture §14, T18.

## E2E: scheduled reconciliation and a Stripe outage

- **What:** two scenarios the simulator could already serve: reconciliation running on its own schedule (the scenarios
  trigger it through the admin endpoint), and a Stripe 5xx/429 storm with the circuit breaker opening and closing (F06).
- **Why not now:** F06 and the schedule are covered by `InitiatePaymentsServiceTest`, `StripePaymentGatewayWireMockTest` and
  `ReconciliationIT`; an E2E version adds wall-clock time for little extra signal.
- **Related:** architecture §8.4, §15 (F06).
