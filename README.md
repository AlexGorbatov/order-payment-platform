# Order & Payment Integration Platform

Reference implementation of a reliable, event-driven order → payment integration with **Stripe in test mode only**:
choreography-based saga over Kafka, transactional outbox and inbox, multi-layer idempotency, signed webhook ingestion,
reconciliation, and Testcontainers-based tests that never need a real Stripe account.

> Status: early bootstrap. Build skeleton, CI and architecture decisions are in place; features land task by task.

## Documentation

- [Architecture](docs/architecture.md) — scope, flows, state machines, contracts, data model, failure matrix
- [Architecture Decision Records](docs/adr/)
- [Local setup](docs/local-setup.md) — docker compose infrastructure, ports, dev accounts, tokens
- [Testing](docs/testing.md) — the test pyramid, end-to-end and chaos scenarios, how to run them
- [Backlog](docs/backlog.md)

## Build

Requires JDK 21. Maven is provided by the wrapper.

```bash
./mvnw verify
```

Format code with `./mvnw spotless:apply`.

The end-to-end and chaos scenarios (both services as processes, Docker required) are not part of `verify`; see
[Testing](docs/testing.md):

```bash
./mvnw -DskipTests -DskipITs -Djacoco.skip=true package   # the jars of the services
./mvnw -pl e2e-tests -am -Pe2e verify
```

## Modules

| Module | Purpose |
|---|---|
| `libs/event-contracts` | Event envelope, payload records, JSON Schemas |
| `libs/platform-messaging-starter` | Outbox, inbox, Kafka error handling, dead letters |
| `libs/platform-idempotency-starter` | HTTP `Idempotency-Key` support |
| `services/order-service` | Orders, catalog, saga participant (port 8081) |
| `services/payment-service` | Payments, refunds, Stripe gateway, webhooks, reconciliation (port 8082) |
| `e2e-tests` | Cross-service scenarios and chaos tests |

## License

[MIT](LICENSE)
