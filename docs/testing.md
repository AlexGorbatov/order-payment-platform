# Testing

How the platform is tested, what each level proves, and how to run it. The strategy itself is
[architecture §14](architecture.md#14-testing-strategy); the failure modes are the matrix in
[§15](architecture.md#15-failure-mode-matrix). No test needs a real Stripe account.

## The pyramid

| Level | Where | Runs with | Needs Docker | What it proves |
|---|---|---|---|---|
| Unit | `*Test` in every module | `./mvnw verify` | no | state machines, money, backoff, mappers; use cases against in-memory ports. JaCoCo gate: ≥ 80 % lines in `domain` and `application` |
| Architecture | `ArchitectureTest` in both services | `./mvnw verify` | no | layering (ArchUnit): the domain knows no framework, adapters never meet, no direct Stripe/Kafka access from annotated transactional methods (port-mediated calls require runtime tests and review) |
| Starter IT | `*IT` in `libs/platform-*-starter` | `./mvnw verify` | yes (PostgreSQL, Kafka) | outbox, inbox, retry topics, dead letters, HTTP idempotency, each against a real broker and database |
| Service IT | `*IT` in `services/*` | `./mvnw verify` | yes (PostgreSQL, Kafka, Keycloak) | one service as a Spring context: API and security matrix, consumers, workers, webhooks. Stripe is WireMock; the clock is injected, so retries and timeouts are exact |
| Contract smoke | [`StripeMockContractIT`](../services/payment-service/src/test/java/com/altronixsoft/opp/payment/adapter/out/stripe/StripeMockContractIT.java) | `./mvnw verify` | yes (`stripe-mock`) | the gateway sends and reads what the Stripe API specification says |
| **E2E and chaos** | `e2e-tests` | `./mvnw verify -Pe2e` | yes | **both services, as the jars the build produces, against real infrastructure**: sagas across services and the failures of §15 that only show up when the pieces run together |
| Manual demo | `docs/local-setup.md` | by hand | yes | real Stripe in test mode, with the Stripe CLI forwarding webhooks |

Each level exists because the one below it cannot see something: a service IT has one service and a fake clock, so it
cannot show that two services agree; the E2E has real processes and real time, so it is slower and judges outcomes, not
internals.

## Running

Prerequisites: JDK 21, Docker. The first run pulls the images (PostgreSQL 17, Kafka 4, Keycloak 26).

```bash
# everything except the E2E scenarios: unit, architecture, starter and service integration tests
./mvnw verify

# the E2E scenarios: build the executable jars of the services, then run them
./mvnw -DskipTests -DskipITs -Djacoco.skip=true package
./mvnw -pl e2e-tests -am -Pe2e verify

# one scenario while developing (-am would run the tests of event-contracts too, which have none matching)
./mvnw -pl e2e-tests -Pe2e verify -Dit.test='ChaosIT#F05*'
```

`./mvnw verify` compiles the E2E module and runs nothing in it: the scenarios take minutes and are a CI job of their own
(`.github/workflows/ci.yml`, job `e2e`, which needs `build`). The jars come from the module build, not from a rebuild:
the `build` job uploads them as an artifact and `e2e` downloads them. Their location is `e2e.order-service.jar` /
`e2e.payment-service.jar` in `e2e-tests/pom.xml`.

<a id="ci"></a>
CI (`.github/workflows/ci.yml`) has four jobs: `build` (`./mvnw verify`, coverage reports, the service jars as an artifact),
`e2e` (needs `build`; the scenarios above, 25 minutes at most), `images` (the compose files are valid and both images build) and
`badges` (pushes to `main` only: publishes an overall coverage number to the `badges` branch, which the README badge
reads). The number is the line coverage of everything JaCoCo measures in the libraries and services, computed by
`.github/scripts/coverage-badge.sh`; the 80 % gate of the build applies to `domain` and `application`.

Logs of the two services are written to `e2e-tests/target/e2e-logs/` (appended across restarts within a run). When a
scenario fails, the tail of both is printed with the failure, and CI uploads the directory.

## E2E: how it is built

### Decision: the services run as processes

The E2E needs both services, with their own databases, on one Kafka, talking to Keycloak and a Stripe double. Three ways
to get there were weighed:

| | Two Spring contexts in the test JVM | Docker images (`spring-boot:build-image`, or a Dockerfile) | **Processes: `java -jar` of the built jars** |
|---|---|---|---|
| Speed | fastest start | image build takes minutes with buildpacks; seconds with a Dockerfile, plus container start | about 12 s per service, in parallel |
| What runs | the classes, not the artifact | the artifact | the artifact |
| Isolation | none: both services' `db/migration/V1__*.sql`, `application.yml` and `spring.factories` collide on one classpath, so it would need class loader tricks that no real deployment has | full | full (own JVM, own class path, own heap) |
| Crash fidelity | closing a context is a graceful shutdown | `docker kill` | `kill -9` of the process: nothing runs on the way out |
| Network | none to speak of | container network: Kafka needs a second listener, Keycloak a fixed hostname so `iss` matches inside and outside | `localhost` and mapped ports, as the service ITs already do |
| CI dependencies | none | Docker daemon + image build | Docker (containers) + the jars |

Processes won: they test the **artifact** (fat jar, Flyway on an empty database, configuration binding, start-up) with
a real **crash** (`ServiceProcess.kill()`), and cost none of the networking and image plumbing that an in-JVM setup
cannot avoid or a container setup needs. What it gives up against containers is network isolation and the `Dockerfile`
itself; the compose `apps` profile builds the images (docs/demo.md) and could be the target of the same scenarios (backlog); nothing in them depends on how a service is
started: only `ServiceProcess` knows.

### What runs

```
 test JVM (failsafe)
   ├── Testcontainers: PostgreSQL 17 (orders_db, payments_db — infra/postgres/init), Kafka (apache/kafka),
   │                   Keycloak with infra/keycloak/realm-opp.json
   ├── StripeSimulator   WireMock + a model of PaymentIntents, refunds, idempotency keys and webhooks
   ├── ServiceProcess ×2 java -jar order-service.jar / payment-service.jar   (own JVMs)
   └── scenarios         HTTP with real Keycloak tokens, JDBC to look inside, Kafka to inject records
```

The platform is started once per JVM (`Platform.get()`); the scenario classes share it and run one after another. Each
scenario places its own orders and looks only at them.

The services run with the production code and configuration; only the **waiting** is shortened, through properties:
workers poll every 300 ms, the outbox relay every 100 ms, retry topics wait 0.3 / 0.6 / 1 s, a claimed work item is
leased for 6 s. Two things are deliberately not real time:

- **The payment timeout** is the production 30 minutes. A scenario makes an order that old in `orders_db`
  (`created_at`) and lets the timeout job, which polls every 500 ms, find it. The job's logic is what runs; only the
  clock reading is arranged.
- **Reconciliation** does not run on its schedule (5 minutes). Scenarios call `POST /admin/reconciliation/run`
  (role `ops`) and a payment counts as quiet after 3 s. Scheduled reconciliation would make payments change under the
  scenarios that hold, drop or reorder webhooks on purpose.

### The Stripe simulator

`StripeSimulator` is WireMock with one stateful handler behind it. `payment-service` talks to it through the real Stripe
SDK (`stripe.api-base`), so serialization, error mapping and the circuit breaker are the real ones. It models what the
platform's guarantees depend on:

- **PaymentIntents** with Stripe's life cycle. Confirming with a test payment method does what Stripe does:
  `pm_card_visa` succeeds, `pm_card_chargeDeclined` is refused with a 402 and a `payment_failed` event,
  `pm_card_authenticationRequired` asks for 3DS, `pm_card_createDispute` succeeds and is disputed,
  `pm_card_refundFail` succeeds but its refunds stay `pending` until a test settles them. Cancelling a succeeded
  PaymentIntent is refused with `payment_intent_unexpected_state`.
- **Idempotency keys**: the same key and parameters return the stored response, even if it never reached the caller;
  the same key with other parameters is an `idempotency_error`. Without it "exactly one PaymentIntent" would be true for
  the wrong reason. A response can be delayed *after* the effect (`delayResponses`), which is how a scenario puts a crash
  between Stripe's answer and the local commit.
- **Webhooks**, signed with the service's secret (`Stripe-Signature`, HMAC-SHA256 over `t.payload`), emitted for every state
  change. `WebhookMode.AUTO` delivers at once, `HOLD` keeps them for the test to release or reorder (`deliver(event)`),
  `DROP` loses them. `webhookCopies(n)` posts each one `n` times concurrently. A delivery that fails is retried, as Stripe
  does. Events carry strictly increasing `created` seconds.
- **A journal** of every API call (operation, order, idempotency key, whether it was a replay) for assertions.

The simulator's refund-failure scenario keeps a refund pending until the test supplies its outcome. Stripe documents a different sequence for [the real `pm_card_refundFail` test method](https://docs.stripe.com/testing#refunds): an initially succeeded refund later fails. That provider sequence is not reproduced by this E2E scenario; the platform ignores a `refund.failed` that arrives after the refund was recorded as succeeded, logs an ERROR and leaves the payment and order `REFUNDED` (architecture §5.3, §17), so a real Stripe test account can end in that state.

### Helpers

| Class | Role |
|---|---|
| `Platform` | starts and wires everything; `pauseKafka()` / `unpauseKafka()` (`docker pause`), `heal()` |
| `ServiceProcess` | `start()`, `stop()` (SIGTERM), `kill()` (SIGKILL), log file, health wait |
| `TestClient` | the HTTP API as `customer1`, `customer2`, `admin1`, `ops1` (real tokens from Keycloak), and the waits: `awaitOrder`, `awaitPayment`, `awaitPayable`, `eventually` (Awaitility, 45 s, 100 ms poll) |
| `StripeSimulator` | see above |
| `Kafka` | puts records on the saga topics from outside: a valid event, or garbage |
| `Outbox` | the events a service published for an order, read from its outbox |
| `Invariants` | the global assertions, below |

### Invariants after every scenario

`E2eTest.endScenario()` first lets the platform go quiet (webhooks delivered and processed, both outboxes drained,
every placed order has its payment and no payment waits for its PaymentIntent, no refund unsent), then asserts
architecture §14 and a few more:

1. at most one PaymentIntent per order, and never two succeeded;
2. the amount of every payment and of its PaymentIntent equals the order total, and the payment holds the PaymentIntent
   the simulator has;
3. no unpublished outbox row in either service;
4. no `DEAD` webhook event, no dead letter left in `NEW`;
5. no money returned twice: per PaymentIntent at most one refund that has not failed, never more than was paid;
6. every mutating call to Stripe carried an idempotency key derived from local ids (`pi-create:`, `pi-cancel:`,
   `pi-confirm:`, `refund:`);
7. order and payment agree (a `PAID` order has a `SUCCEEDED` payment, a `REFUNDED` order a `REFUNDED` one, a `CANCELLED`
   order a cancelled payment, ...).

All violations are reported together. A scenario that breaks something on purpose (kills a service, pauses Kafka) is
healed before the invariants are checked: they describe the platform when it is whole again.

## Scenarios

Names carry the flow of [§6](architecture.md#6-key-flows) or the failure ID of [§15](architecture.md#15-failure-mode-matrix).

| Class | Test | Covers |
|---|---|---|
| [`PaymentFlowsIT`](../e2e-tests/src/test/java/com/altronixsoft/opp/e2e/PaymentFlowsIT.java) | `flow_6_1_happyPath` | order → PaymentIntent → payment → webhook → `PAID`; client secret only for the owner, `no-store`; one correlation id across the services |
| | `flow_6_2_declineThenSuccessfulRetry` | declined card keeps the order open; second attempt on the same PaymentIntent pays |
| | `flow_6_3_authenticationRequiredThenSuccess`, `..._authenticationFailedThenAnotherCardPays` | 3DS success and failure |
| [`CancellationAndRefundIT`](../e2e-tests/src/test/java/com/altronixsoft/opp/e2e/CancellationAndRefundIT.java) | `flow_6_4_paymentTimeoutCancelsOrderAndPaymentIntent`, `flow_6_4_customerCancelsUnpaidOrder` | timeout and customer cancellation cancel the PaymentIntent; ownership |
| | `F18_F19_paymentSucceedsAfterCustomerCancel...`, `F18_F19_paymentSucceedsAfterTimeout...` | payment wins the race against the cancel: Stripe refuses the cancel (not retried), the late success is refunded automatically → `REFUNDED` |
| | `flow_6_5_adminRefund` | one Stripe refund, `REFUNDED`; role check; the admin's retry with the same key is a replay; refunding twice is a 409 |
| | `F20_refundFailsThenAdminRetries` | `REFUND_FAILED`, retry with a new request, success |
| [`WebhookResilienceIT`](../e2e-tests/src/test/java/com/altronixsoft/opp/e2e/WebhookResilienceIT.java) | `F09_duplicateWebhooksOfEveryType...` | seven event types, each delivered three times at once: one effect, one event, all acknowledged |
| | `F10_olderWebhookArrivingAfterSuccess...`, `F10_olderFailureArrivingAfterSuccess...` | out-of-order delivery: the older event is processed and dropped as stale |
| | `F11_lostWebhook_isRecoveredByReconciliation` | a dropped webhook; reconciliation finds the drift (source `RECONCILIATION`); the webhook that arrives late changes nothing (F21) |
| [`ChaosIT`](../e2e-tests/src/test/java/com/altronixsoft/opp/e2e/ChaosIT.java) | `F01_kafkaPausedDuringOrderCreation...` | `docker pause` Kafka while orders are created and half of them cancelled; after unpause everything arrives once, in order per key |
| | `F05_paymentServiceKilledMidBatch...` | `kill -9` while PaymentIntents are being created (Stripe has made one the service never heard of); after restart exactly one per order, the retry answered from the idempotency key |
| | `F15_garbageOnTheTopic...` | poison message → dead letter → stored in the consuming service only → consumer not blocked → not replayable (422) → resolved |
| | `F15_eventForUnknownOrder...` | valid event the state cannot explain → dead letter → data fixed → replay applies it, once |
| [`IdempotencyIT`](../e2e-tests/src/test/java/com/altronixsoft/opp/e2e/IdempotencyIT.java) | `F16_twentyConcurrentCreates...` | 20 concurrent requests, one key: one order, one payment, one PaymentIntent; the rest replay or get 409 |
| | `F17_sameKeyDifferentBody...` | 422; keys are per customer |

### Where every failure mode is tested

The E2E does not repeat every lower-level check. `-` means evidence is at a lower level; rows state when a mechanism is tested without reproducing the exact crash. F16 covers concurrent HTTP requests while the service remains running; it does not cover the business-commit/response-recording crash window ([§7.3](architecture.md#73-idempotency-layers)).

| ID | Failure | E2E | Lower level |
|---|---|---|---|
| F01 | Kafka down while orders are created | [`ChaosIT`](../e2e-tests/src/test/java/com/altronixsoft/opp/e2e/ChaosIT.java) | [`OutboxKafkaOutageIT`](../libs/platform-messaging-starter/src/test/java/com/altronixsoft/opp/platform/messaging/outbox/OutboxKafkaOutageIT.java) |
| F02 | relay crashes after send | - | [`OutboxKafkaOutageIT`](../libs/platform-messaging-starter/src/test/java/com/altronixsoft/opp/platform/messaging/outbox/OutboxKafkaOutageIT.java) permits duplicate publication after ack ambiguity; [`InboxConsumerIT`](../libs/platform-messaging-starter/src/test/java/com/altronixsoft/opp/platform/messaging/consumer/InboxConsumerIT.java) verifies duplicate effects are suppressed. No dedicated relay process-kill test |
| F03 | consumer crashes before offset commit | - | [`InboxConsumerIT`](../libs/platform-messaging-starter/src/test/java/com/altronixsoft/opp/platform/messaging/consumer/InboxConsumerIT.java), [`OrderSagaIT`](../services/order-service/src/test/java/com/altronixsoft/opp/order/OrderSagaIT.java) |
| F04 | Stripe timeout on create | ([`ChaosIT`](../e2e-tests/src/test/java/com/altronixsoft/opp/e2e/ChaosIT.java), F05 shares the mechanism) | [`InitiatePaymentsServiceTest`](../services/payment-service/src/test/java/com/altronixsoft/opp/payment/application/InitiatePaymentsServiceTest.java), [`PaymentInitiationIT`](../services/payment-service/src/test/java/com/altronixsoft/opp/payment/PaymentInitiationIT.java) |
| F05 | crash between Stripe's answer and the commit | [`ChaosIT`](../e2e-tests/src/test/java/com/altronixsoft/opp/e2e/ChaosIT.java) | [`PaymentInitiationIT`](../services/payment-service/src/test/java/com/altronixsoft/opp/payment/PaymentInitiationIT.java) |
| F06 | Stripe 5xx / 429 storm, circuit breaker | - | [`InitiatePaymentsServiceTest`](../services/payment-service/src/test/java/com/altronixsoft/opp/payment/application/InitiatePaymentsServiceTest.java), [`StripePaymentGatewayWireMockTest`](../services/payment-service/src/test/java/com/altronixsoft/opp/payment/adapter/out/stripe/StripePaymentGatewayWireMockTest.java) |
| F07 | permanent 4xx on create | - | [`InitiatePaymentsServiceTest`](../services/payment-service/src/test/java/com/altronixsoft/opp/payment/application/InitiatePaymentsServiceTest.java), [`PaymentInitiationIT`](../services/payment-service/src/test/java/com/altronixsoft/opp/payment/PaymentInitiationIT.java) |
| F08 | payment stuck in `CREATED` for 23 h | - | [`InitiatePaymentsServiceTest`](../services/payment-service/src/test/java/com/altronixsoft/opp/payment/application/InitiatePaymentsServiceTest.java), [`PaymentInitiationIT`](../services/payment-service/src/test/java/com/altronixsoft/opp/payment/PaymentInitiationIT.java) (needs a clock) |
| F09 | duplicate webhook | [`WebhookResilienceIT`](../e2e-tests/src/test/java/com/altronixsoft/opp/e2e/WebhookResilienceIT.java) | [`WebhookIT`](../services/payment-service/src/test/java/com/altronixsoft/opp/payment/WebhookIT.java) |
| F10 | out-of-order webhooks | [`WebhookResilienceIT`](../e2e-tests/src/test/java/com/altronixsoft/opp/e2e/WebhookResilienceIT.java) | [`WebhookIT`](../services/payment-service/src/test/java/com/altronixsoft/opp/payment/WebhookIT.java) |
| F11 | webhook lost | [`WebhookResilienceIT`](../e2e-tests/src/test/java/com/altronixsoft/opp/e2e/WebhookResilienceIT.java) | [`ReconciliationIT`](../services/payment-service/src/test/java/com/altronixsoft/opp/payment/ReconciliationIT.java) |
| F12, F13 | bad signature, live mode | - | [`WebhookIT`](../services/payment-service/src/test/java/com/altronixsoft/opp/payment/WebhookIT.java), [`StripeWebhookVerifierTest`](../services/payment-service/src/test/java/com/altronixsoft/opp/payment/adapter/out/stripe/StripeWebhookVerifierTest.java) |
| F14 | webhook handler keeps failing → `DEAD` | - | [`WebhookIT`](../services/payment-service/src/test/java/com/altronixsoft/opp/payment/WebhookIT.java), [`ProcessWebhookEventsServiceTest`](../services/payment-service/src/test/java/com/altronixsoft/opp/payment/application/ProcessWebhookEventsServiceTest.java) |
| F15 | poison message | [`ChaosIT`](../e2e-tests/src/test/java/com/altronixsoft/opp/e2e/ChaosIT.java) | [`ConsumerRetryAndDltIT`](../libs/platform-messaging-starter/src/test/java/com/altronixsoft/opp/platform/messaging/consumer/ConsumerRetryAndDltIT.java), [`DeadLetterAdminIT`](../libs/platform-messaging-starter/src/test/java/com/altronixsoft/opp/platform/messaging/deadletter/DeadLetterAdminIT.java) |
| F16, F17 | same Idempotency-Key | [`IdempotencyIT`](../e2e-tests/src/test/java/com/altronixsoft/opp/e2e/IdempotencyIT.java) | [`IdempotencyIT`](../libs/platform-idempotency-starter/src/test/java/com/altronixsoft/opp/platform/idempotency/IdempotencyIT.java) (starter) |
| F18, F19 | payment after cancel; cancel races success | [`CancellationAndRefundIT`](../e2e-tests/src/test/java/com/altronixsoft/opp/e2e/CancellationAndRefundIT.java) | [`OrderSagaIT`](../services/order-service/src/test/java/com/altronixsoft/opp/order/OrderSagaIT.java), [`CancelAndRefundIT`](../services/payment-service/src/test/java/com/altronixsoft/opp/payment/CancelAndRefundIT.java) |
| F20 | refund fails | [`CancellationAndRefundIT`](../e2e-tests/src/test/java/com/altronixsoft/opp/e2e/CancellationAndRefundIT.java) | [`CancelAndRefundIT`](../services/payment-service/src/test/java/com/altronixsoft/opp/payment/CancelAndRefundIT.java) |
| F21 | webhook and reconciliation on one payment | [`WebhookResilienceIT`](../e2e-tests/src/test/java/com/altronixsoft/opp/e2e/WebhookResilienceIT.java) (late webhook after reconciliation) | [`ReconciliationIT`](../services/payment-service/src/test/java/com/altronixsoft/opp/payment/ReconciliationIT.java) (lock race) |
| F22 | duplicate `OrderRefundRequested` | (`flow_6_5` for the HTTP key) | [`CancelAndRefundIT`](../services/payment-service/src/test/java/com/altronixsoft/opp/payment/CancelAndRefundIT.java), [`ApplyOrderEventServiceTest`](../services/payment-service/src/test/java/com/altronixsoft/opp/payment/application/ApplyOrderEventServiceTest.java) |

## Rules for new scenarios

- Async assertions use Awaitility (`TestClient.eventually`, `await*`); no `Thread.sleep`, no timing-dependent assertion. A
  "nothing happens" check uses `await().during(...)`.
- A scenario places its own orders through `TestClient` and asserts on them only; the invariants cover them. Orders created by
  other means are `client.adopt(id)`-ed.
- Calls to Stripe are asserted per order (`stripe.calls(operation, orderId)`): the simulator is shared and keeps its journal.
- A scenario that holds, drops or reorders webhooks (`WebhookMode`) puts them back (`AUTO`) before it ends.
- A chaos scenario must be able to fail: check that it does when the guarantee is removed (for example, make the simulator
  forget idempotency keys and watch `F05` fail).
