# Order & Payment Integration Platform

Reference implementation of a reliable, event-driven order → payment integration with **Stripe in test mode only**:
choreography-based saga over Kafka, transactional outbox and inbox, multi-layer idempotency, signed webhook ingestion,
reconciliation, and Testcontainers-based tests that never need a real Stripe account.

> Status: early bootstrap. Build skeleton, CI and architecture decisions are in place; features land task by task.

## Documentation

- [Architecture](docs/architecture.md) — scope, flows, state machines, contracts, data model, failure matrix
- [Architecture Decision Records](docs/adr/)
- [Local setup](docs/local-setup.md) — docker compose infrastructure, ports, dev accounts, tokens
- [Backlog](docs/backlog.md)

## Build

Requires JDK 21. Maven is provided by the wrapper.

```bash
./mvnw verify
```

Format code with `./mvnw spotless:apply`.

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
