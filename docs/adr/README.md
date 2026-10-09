# Architecture Decision Records

Decisions that shape the platform, with the alternatives that were rejected and why ([ADR-0001](0001-record-architecture-decisions.md)
explains the format). [architecture.md](../architecture.md) is the design; these records are the reasoning. All fourteen
are **Accepted** and describe what v1.0.0 implements. Some carry an addendum with details that only the implementation
could settle.

| # | Decision | Status | Where it lives | Where it is tested |
|---|---|---|---|---|
| [0001](0001-record-architecture-decisions.md) | Record architecture decisions (MADR) | Accepted | this directory | - |
| [0002](0002-two-services-choreography-saga.md) | Two services, choreography-based saga, no synchronous calls between them | Accepted | `services/order-service`, `services/payment-service` | `OrderSagaIT`, e2e `PaymentFlowsIT`, `CancellationAndRefundIT` |
| [0003](0003-hexagonal-architecture.md) | Hexagonal layout per service, enforced | Accepted | package layout of both services | `ArchitectureTest` in both services |
| [0004](0004-transactional-outbox-polling-relay.md) | Transactional outbox with a polling relay | Accepted | [`outbox`](../../libs/platform-messaging-starter/src/main/java/com/altronixsoft/opp/platform/messaging/outbox/) | `OutboxPublisherIT`, `OutboxConcurrentRelayIT`, `OutboxKafkaOutageIT`, e2e F01 |
| [0005](0005-inbox-idempotent-consumers.md) | Inbox-based idempotent consumers | Accepted | [`InboxGuard`](../../libs/platform-messaging-starter/src/main/java/com/altronixsoft/opp/platform/messaging/inbox/InboxGuard.java) | `InboxConsumerIT`, e2e F01 and F05 |
| [0006](0006-multi-layer-idempotency.md) | Idempotency at every boundary | Accepted | [`@Idempotent`](../../libs/platform-idempotency-starter/src/main/java/com/altronixsoft/opp/platform/idempotency/Idempotent.java), Stripe keys, webhook event ids, inbox | starter `IdempotencyIT`, e2e `IdempotencyIT` (F16, F17) |
| [0007](0007-retry-topics-dlt-ordering-tradeoff.md) | Retry topics, persisted dead letters with replay, and the ordering trade-off | Accepted | [`consumer`](../../libs/platform-messaging-starter/src/main/java/com/altronixsoft/opp/platform/messaging/consumer/), [`deadletter`](../../libs/platform-messaging-starter/src/main/java/com/altronixsoft/opp/platform/messaging/deadletter/) | `ConsumerRetryAndDltIT`, `DeadLetterAdminIT`, e2e `ChaosIT` (F15) |
| [0008](0008-db-backed-work-queues.md) | DB-backed work queues for every external call | Accepted | `Initiate…`, `CancelPaymentIntents…`, `CreateRefunds…`, `ProcessWebhookEvents…`, `ReconcilePayments…` services | `WorkQueueIT`, `PaymentInitiationIT`, e2e F05 |
| [0009](0009-webhook-ingestion.md) | Webhooks: verify, persist, acknowledge, process asynchronously | Accepted | [`ReceiveWebhookService`](../../services/payment-service/src/main/java/com/altronixsoft/opp/payment/application/ReceiveWebhookService.java), [`ProcessWebhookEventsService`](../../services/payment-service/src/main/java/com/altronixsoft/opp/payment/application/ProcessWebhookEventsService.java) | `WebhookIT`, e2e `WebhookResilienceIT` (F09, F10) |
| [0010](0010-out-of-order-webhooks-reconciliation.md) | Out-of-order webhooks and reconciliation | Accepted | [`Payment`](../../services/payment-service/src/main/java/com/altronixsoft/opp/payment/domain/Payment.java), [`ReconcilePaymentsService`](../../services/payment-service/src/main/java/com/altronixsoft/opp/payment/application/ReconcilePaymentsService.java) | `PaymentStripeStatusTest`, `ReconciliationIT`, e2e F10, F11, F21 |
| [0011](0011-json-events-explicit-versioning.md) | JSON events with explicit versioning | Accepted | [`libs/event-contracts`](../../libs/event-contracts) | `EventSchemaTest`, `EventCatalogTest` |
| [0012](0012-stripe-test-mode-strategy.md) | Stripe test-mode strategy and the live-mode guard | Accepted | [`StripePaymentGateway`](../../services/payment-service/src/main/java/com/altronixsoft/opp/payment/adapter/out/stripe/StripePaymentGateway.java), [`LiveModeGuard`](../../services/payment-service/src/main/java/com/altronixsoft/opp/payment/adapter/out/stripe/LiveModeGuard.java) | `LiveModeGuardTest`, `StripePaymentGatewayWireMockTest`, `StripeMockContractIT`, the e2e Stripe simulator |
| [0013](0013-keycloak-jwt-no-sync-calls.md) | Keycloak, JWT resource servers, ownership checks | Accepted | both `SecurityConfiguration` classes, [`realm-opp.json`](../../infra/keycloak/realm-opp.json) | `OrderApiSecurityIT`, `TokenValidationTest`, `PaymentApiIT` |
| [0014](0014-shared-platform-starters.md) | Shared platform starters | Accepted | `libs/platform-messaging-starter`, `libs/platform-idempotency-starter` | the starters' own integration tests |

Process: a decision that changes an accepted ADR is a new ADR that supersedes it; the old one is marked
`Superseded by ADR-XXXX` and keeps its text.
