# Final audit — T20

Audit date: 2026-10-10 (Europe/Kiev). Audited revision: af2c5359abe2e97aeafc534298ceb6314eae2a53, branch main.

Scope: CLAUDE.md, README, all docs/ and ADRs, production Java and resources, migrations, infrastructure, scripts, demo, build/CI configuration, and all test/support sources. The review was divided into reliability, security and test-evidence passes, with independent verification of the principal findings. No application, test, configuration or existing documentation files were changed.

**Assessment:** three new High correctness defects need fixes: a late initiation worker can cancel an already attached PaymentIntent; refund retry exhaustion can permanently strand a refund; an expired HTTP idempotency claim has no ownership fence. There is also a Medium webhook processing race, a Medium secret-retention issue, and defects in test evidence. Two existing, documented design limitations remain High risks: the order-creation crash window and ignoring a provider refund failure after an apparent success.

No Critical finding, demonstrated duplicate captured/refunded money, or customer/admin authorization bypass was established. “Two gateway calls” does not mean “two provider objects”: stable provider keys protect normal retries within the provider's retention window.

Priority definitions: Critical means demonstrated uncontrolled money movement or broad authorization compromise; High means a consequential correctness failure or major loss of assurance; Medium means bounded integrity, confidentiality, recovery or test-evidence weakness. Estimates below are engineer-days including regression coverage, excluding deployment and review.

## 1. Architecture and ADR discrepancies

All fourteen ADRs are currently Accepted. Their status is not evidence that every invariant holds under the schedules below. A change to an accepted decision's meaning should use a new/superseding ADR.

| Reference | Implementation evidence | Discrepancy or qualification |
|---|---|---|
| [architecture.md:303][arch:303]; [ADR-0008:23][adr8:23] | [InitiatePaymentsService.java:180][init:180], [187][init:187] | Leases and optimistic locking do not fence the actual initiation result path. It reloads the latest aggregate, then treats every non-CREATED result as an orphan. An expired worker can cancel the same PI another worker attached (A01). |
| [ADR-0008:20][adr8:20]; [architecture.md:536][arch:536] | [CreateRefundsService.java:164][refundWorker:164], [217][refundWorker:217]; [Refund.java:211][refund:211] | Retry exhaustion commits removal from the due queue before the separate terminal-state/outbox transaction. A crash between them prevents the promised recoverable refund failure (A02). |
| [architecture.md:292][arch:292]; [ADR-0006][adr6:1] | [IdempotencyRepository.java:46][idemRepo:46], [92][idemRepo:92], [107][idemRepo:107] | Claim takeover replaces the request hash, but completion/release only match principal, key and status. An older request can complete or delete its successor's claim (A03). |
| [architecture.md:525][arch:525]; [ADR-0009:24][adr9:24] | [ProcessWebhookEventsService.java:105][webhookWorker:105]; [JpaWebhookEventRepository.java:41][webhookRepo:41]; [StripeWebhookEventEntity.java:17][webhookEntity:17] | Ingress PK dedupe does not ensure one processing effect after lease expiry. There is no event version, processing row lock or claim-generation check (A06). |
| [CLAUDE.md:69][claude:69]; [architecture.md:479][arch:479] versus [ADR-0009:40][adr9:40] | [ReceiveWebhookService.java:50][receive:50]; [StripeWebhookEventJpaRepository.java:19][webhookSql:19] | “client_secret never persisted” conflicts with deliberately storing full provider snapshots. The latter can contain client_secret (S01). This is an unresolved policy contradiction. |
| [architecture.md:513][arch:513]; [testing.md:187][testing:187] | [OutboxKafkaOutageIT.java:101][outage:101] | F02's claimed ack-ambiguity evidence does not force an acknowledged send followed by rollback/crash, or even require a duplicate record. Supplied-duplicate inbox tests cover a different mechanism (T02). |
| [architecture.md:537][arch:537] | [ReconciliationIT.java:194][reconcileIT:194] | F21's named simultaneous test can pass after reconciliation checks zero payments. Its asserted final state does not establish participation in the race (T02). |
| [CLAUDE.md:62][claude:62], [74][claude:74] | [architecture.md:488][arch:488], [552][arch:552]; tests listed in §4 | CLAUDE mandates ECS logging and no Thread.sleep/timing assertions. Architecture explicitly defers ECS, while multiple tests sleep or depend on wall-clock windows. Resolve the guidance; distinguish fault-injection delays from synchronization. |

The following are **documented limitations**, not newly discovered implementation/documentation divergences:

- The non-atomic business commit/HTTP response recording window is explicitly described at [architecture.md:294][arch:294], [553][arch:553] (A04).
- Ignoring refund.failed after SUCCEEDED is intentional at [StripeNotificationHandler.java:167][handler:167] and [runbooks/webhooks.md:90][webhooks:90]. It conflicts with a supported provider lifecycle and the broad F20 recovery expectation (A05).
- Pending refunds and disputes are outside reconciliation; refund/cancel workers have no initiation-style age cutoff; tracing/export, dashboards and alerts are deferred; webhook cleanup is manual; retry topics break per-key ordering. These are recorded at [architecture.md:552][arch:552]–[562][arch:562].
- The relay sends to Kafka inside a DB transaction intentionally. This is ADR-0004's exception, not an accidental Stripe transaction violation.

## 2. Correctness

### A01 — High: an expired initiation worker cancels a valid attached PaymentIntent

**Location:** [InitiatePaymentsService.java:101][init:101], [146][init:146], [180][init:180], [187][init:187], [210][init:210].

All work in a batch receives a lease at claim time. Worker A reads CREATED, calls Stripe, and is delayed beyond its lease. Worker B reclaims the payment, calls with the same idempotency key, receives PI X and attaches it. When A eventually receives that same PI X, its recording transaction reloads the current payment. Since it is no longer CREATED, attach returns SKIPPED_NO_LONGER_CREATED. The caller invokes cancelOrphan on X even though X is the payment's attached, payable intent.

Optimistic locking of an old aggregate does not help: the worker deliberately reloads the latest version. The stale-save test at [WorkQueueIT.java:308][workIT:308] tests another path. Existing orphan tests at [InitiatePaymentsServiceTest.java:417][initTest:417] model genuine local cancellation, not another worker attaching the same PI.

**Impact:** cancellation of a valid pending payment and, through its webhook, possible cancellation of the order. This is not a second PI: both calls can return the same provider object.

**Runtime evidence:** a controlled clock/reentrant gateway harness using the compiled production services produced:

    B: INITIATED=1
    A: SKIPPED_NO_LONGER_CREATED=1
    local status=REQUIRES_PAYMENT_METHOD, attached=pi_same
    gateway create calls=2, cancel calls=1, canceled id=pi_same

**Fix:** acknowledge the already attached identical PI as an idempotent result; compensate only a genuine orphan/cancelled flow. Fence claims/results and test two actual worker executions overlapping lease expiry. Size leases for the whole batch or claim per item. Estimate: 1–2 days.

### A02 — High: refund retry exhaustion can leave REQUESTED work permanently unclaimable

**Location:** [CreateRefundsService.java:164][refundWorker:164], [188][refundWorker:188], [217][refundWorker:217]; [Refund.java:211][refund:211]; [RefundJpaRepository.java:19][refundRepo:19].

On the final transient failure, scheduleRetry increments attempts and sets nextAttemptAt to null without changing REQUESTED. record commits that state. Only afterward does fail start another transaction to mark FAILED and emit PaymentRefundFailed.

If the process stops, the DB is unavailable, or the second transaction otherwise fails between these steps, due-work SQL excludes the REQUESTED row because next_attempt_at is null. The worker cannot resume it. The open-refund uniqueness constraint also prevents a replacement from simply bypassing it.

**Impact:** refund and order remain REQUESTED/REFUND_REQUESTED indefinitely; no terminal failure event reaches the order. A live process error also causes this, so an actual process kill is not required.

**Runtime evidence:** with the final retry configured and the second recording transaction rejected:

    batch outcome=ERROR
    refund status=REQUESTED, attempts=1, nextAttemptAt=null
    published events=0
    future claim count=0

[CreateRefundsServiceTest.java:141][refundTest:141] covers uninterrupted exhaustion, not failure between the two commits.

**Fix:** record exhaustion, FAILED state, payment history and failure outbox event in one transaction. Provide recovery for existing REQUESTED rows with null due time. Add a real DB interruption/rollback regression. Estimate: 1–2 days.

### A03 — High: HTTP idempotency takeover does not fence the previous owner

**Location:** [IdempotencyRepository.java:42][idemRepo:42], [86][idemRepo:86], [103][idemRepo:103]; [IdempotencyInterceptor.java:131][idemInterceptor:131], [171][idemInterceptor:171].

The repository takes over expired/abandoned claims, including replacement of request_hash. Its completion and deletion predicates carry no claim UUID, generation or original hash. The interceptor retains only principal/key ownership.

A pauses beyond the two-minute abandonment threshold. B takes over the same principal/key and is still running. A resumes:

1. If A completes, it can store response A under request hash B. A later request matching B can replay A's order response.
2. If A releases its failed request, it deletes B's active claim. C can then start another execution.
3. A need not have died: an alive, stalled request and its replacement can both perform business effects.

**Impact:** wrong response/request association and duplicate execution within the same principal. JWT-subject keying and security-chain ordering prevent this finding from being a demonstrated cross-user IDOR.

**Evidence:** direct SQL predicates establish the schedule; no new PostgreSQL reproduction was run for this finding. Existing concurrency tests remain within a live claim's lifetime.

**Fix:** issue a claim token/generation and require it in every complete/release update; check affected row counts. Test late completion and late failure after takeover. This fixes ownership, but the business-effect duplication in A04 needs its own durable request identity. Estimate: 1–2 days.

### A04 — High, documented limitation: a retry after order commit can create another order and PI

**Location:** [PlaceOrderService.java:51][place:51], [77][place:77]; [IdempotencyInterceptor.java:146][idemInterceptor:146], [171][idemInterceptor:171]; [IdempotencyRepository.java:32][idemRepo:32].

Order/history/outbox commit in the business transaction. The HTTP response cache completes in a separate REQUIRES_NEW transaction after MVC completion. A crash between them leaves the claim abandoned. After takeover, PlaceOrderService generates a fresh order ID; no business uniqueness ties it to the original HTTP request.

**Impact:** two orders/payments/PaymentIntents for one logical customer request. The unique payment.order_id and pi-create:{paymentId} protect each separate order, not the original client operation. This does not prove two captured payments; the customer would still need to confirm the additional intent.

The limitation is accurately documented at [architecture.md:294][arch:294]. F16 only proves concurrent requests without this crash window.

**Fix:** persist a stable business request identity/result atomically with the order and outbox, and replay it after recovery. Add an after-business-commit/before-response-recording process crash test. Estimate: 2–4 days.

### A05 — High, documented design limitation: supported asynchronous refund failure stays REFUNDED

**Location:** [StripeNotificationHandler.java:143][handler:143], [167][handler:167]; [testing.md:123][testing:123]; [runbooks/webhooks.md:90][webhooks:90].

Stripe's supported asynchronous-failure test method, pm_card_refundFail, initially reports a succeeded refund and later sends refund.failed. The platform applies the success, sets refund SUCCEEDED/payment REFUNDED and emits PaymentRefunded. A later failure for that same refund is deliberately ignored, leaving the order REFUNDED and preventing an ordinary retry.

This provider lifecycle is documented by [Stripe's asynchronous refund test cases](https://docs.stripe.com/testing#refunds), verified during this audit. The simulator instead uses pending → failed, so its F20 success does not validate the real failure method.

**Impact:** local state reports returned funds after the provider reports failure. The manual runbook instruction makes the discrepancy visible to an attentive operator but does not restore the intended recoverable order state.

**Runtime evidence:** the real handler, given success followed by failure for the same refund, yielded:

    success outcome=APPLIED
    later failure outcome=IGNORED
    refund=SUCCEEDED, payment=REFUNDED
    published=[Refunded]  (no RefundFailed)

**Fix:** introduce an identity-aware correction for failed provisional/apparent refund success, including payment/order behavior and a failure/correction event. Cover the actual provider sequence in simulator and tests, with a new ADR for the changed terminal-state meaning. Estimate: 3–5 days.

### A06 — Medium: an expired webhook processor can emit the same failed-attempt effect twice

**Location:** [ProcessWebhookEventsService.java:105][webhookWorker:105], [131][webhookWorker:131]; [JpaWebhookEventRepository.java:41][webhookRepo:41]; [StripeWebhookEventEntity.java:17][webhookEntity:17]; [StripeNotificationHandler.java:89][handler:89], [235][handler:235].

A loads an open stored event, then stalls before handling. Its lease expires; B reclaims and processes that event. A resumes using its already loaded open event. The handler reloads the payment after B's commit, so payment optimistic locking need not conflict. An equal-time payment_failed report can register another AttemptFailed event. The webhook row itself has neither version nor a processing lock.

Ingress's unique event ID prevents two stored rows, not these two processing effects. The analogous late failure-recording path can overwrite completed processing state with a retry state.

**Runtime evidence:** a parser gate and clock advanced past the lease produced two PROCESSED outcomes for evt_same and two AttemptFailed events. No extra money movement was shown.

**Fix:** hold an event-row lock through its processing transaction, or enforce event version/claim generation so the stale transaction rolls back aggregate/outbox effects. Apply the same fence to failure recording. Add expired-lease processing tests against PostgreSQL. Estimate: 1–2 days.

### Transaction boundaries and event-loss review

| Path | Result |
|---|---|
| PI initiation, cancellation, refund creation | The Stripe calls are outside TransactionOperations callbacks: [initiation:156][init:156], [cancellation:124][cancelWorker:124], [refund:121][refundWorker:121]. No hidden synchronous call from the Kafka consumers was found. |
| Reconciliation and payment GET | Stripe retrieval is outside the DB transaction: [ReconcilePaymentsService.java:133][reconcile:133], [GetPaymentService.java:51][getPayment:51]. |
| Webhook ingress/processing | Signature verification and DB persistence; processing changes aggregate/history/outbox together. It makes no Stripe call. A06 weakens uniqueness after lease expiry. |
| Outbox relay | [OutboxRelay.java:85][relay:85], [103][relay:103] deliberately send while holding the claim transaction. An acknowledged send followed by rollback is at-least-once delivery, handled by the inbox. It also holds DB resources during broker waits; the scheduler delay is documented. |
| Inbox/business handler | [InboxGuard.java:54][inbox:54] inserts the dedupe marker and runs the action in the same transaction. No general dual-write loss between aggregate and outbox was found. |
| Refund failure | A02 loses the terminal event/recovery path through a two-commit window. It is the confirmed stranded-work case. |
| DLT/replay | Persisted dead letters and outbox-backed replay preserve recoverability. An explicit resolve or unsupported-event policy can discard work intentionally; these are operator/protocol decisions, not silent relay loss. |

Additional limits affecting duplication and recovery:

- Initiation's 23-hour cutoff prevents automatic create retries with a potentially pruned provider key. An ambiguous provider result that never attached locally may leave an orphan, but this audit did not establish a second PI from that path.
- Refund/cancel keys are stable but have no age cutoff ([architecture.md:556][arch:556]). After provider pruning, a retry is not guaranteed to identify the original operation. Verify unresolved provider outcomes before resuming old work. This is a second-operation risk, not evidence of a second successful full refund: provider remaining-amount rules still apply.
- Reconciliation repairs unfinished PaymentIntent statuses; it cannot repair a lost pending-refund or dispute webhook. Re-delivery/manual inspection remains necessary ([architecture.md:555][arch:555]).
- Kafka/inbox dedupe is finite. After the 14-day inbox retention period, replayed attempt/action-required events can add history again at [ApplyPaymentEventService.java:64][applyPayment:64]. Terminal payment/refund state guards and business request keys provide stronger protection for financial effects. The claim is bounded effectively-once behavior, not unlimited replay idempotence.
- Retry/DLT auto-creation at [ConsumerAutoConfiguration.java:74][consumerConfig:74] sets partitions/replication but no retention. [infra/kafka/create-topics.sh:15][topics:15] correctly provisions local 14-day retention; other auto-created topics inherit broker defaults. The retention promise is conditional on provisioning.
- Cancellation versus success and late-payment compensation have state-machine and request-identity guards with useful tests. No additional duplicate-money defect was established in these normal paths; A01 is a separate expired-worker cancellation error.

## 3. Security

### S01 — Medium: client_secret is persisted inside signed webhook snapshots

**Location:** [ReceiveWebhookService.java:50][receive:50]; [StripeWebhookEventJpaRepository.java:19][webhookSql:19].

A legitimate signed PaymentIntent snapshot can contain data.object.client_secret. The receiver stores the verified payload unchanged as JSONB. Someone with webhook-table or backup read access can retrieve a secret intended for the paying customer. This is test-mode confidentiality exposure; there is no public webhook-payload retrieval endpoint.

Stripe's [PaymentIntent object](https://docs.stripe.com/api/payment_intents/object) and [Event object](https://docs.stripe.com/api/events/object) describe these fields. The issue contradicts the no-persistence rule but follows ADR-0009's full-payload decision.

[PaymentApiIT.java:192][paymentApiIT:192] tests that initiation/GET do not persist or log the secret, but does not ingest a secret-bearing webhook. Synthetic PaymentIntent webhook fixtures omit it.

**Fix:** verify original raw bytes first, then redact client_secret recursively before persistence. Preserve processor-required fields, scrub existing stored rows and add signed-webhook DB/log assertions. Update the accepted payload-storage decision through a superseding ADR. Estimate: 1–2 days.

| Area | Audit result |
|---|---|
| Secret/token logs, exception chains and API responses | Gateway errors/redacted toString paths were reviewed. No additional confirmed API-key, bearer-token or client-secret log exposure was found. Payment GET deliberately returns an owner-only clientSecret with no-store; admins do not receive it. S01 concerns durable storage. |
| IDOR and RBAC | JWT signature/issuer/expiry/audience and role checks are present. Ownership is checked for order/payment reads and mutations. Foreign and missing objects return the same 404. No bypass was established. A03 is same-principal response integrity. |
| Webhook endpoint | Public by design; raw-body HMAC verification, multiple rotation secrets, 300-second tolerance and live-mode rejection precede storage. Invalid/tampered/old events are refused. Ingress dedupe is sound; asynchronous processor uniqueness has A06. |
| Actuator | Health/info public; metrics require ops; env is not exposed, including to ops. No public sensitive actuator endpoint was found. |
| Disabled test-support | Conditional beans are absent when the flag is false. Disabled behavior is tested. When enabled, customer role and payment ownership apply. |
| CORS/demo | No permissive CORS setup was found. The demo proxies same-origin requests; dynamic values use textContent. Static innerHTML is not an identified injection sink. |
| Body limits | Webhook reads are bounded at 256 KiB, including unknown content length ([StripeWebhookController.java:1][webhookController:1]). Idempotency buffering is bounded at 1 MB by default ([IdempotencyProperties.java:35][idemProps:35], [IdempotencyBufferingFilter.java:37][idemBuffer:37]). This is not a universal cap for every route; the idempotency buffer applies to requests carrying the key. |
| Local infrastructure trust | Compose passwords, HTTP/Kafka and published development ports are explicit demo defaults. No new claim of production hardening is inferred from them. Live Stripe keys/events are rejected. |

## 4. Tests: F01–F22 and flakiness

A row marked Covered has automated mechanism evidence in the audited source. It does not mean the E2E profile was executed in this audit or that every adjacent crash schedule is covered. Partial identifies the missing part precisely; no F ID is entirely without functional evidence.

| ID | Evidence | Assessment / missing evidence |
|---|---|---|
| F01 | [ChaosIT:32][chaos:32]; [OutboxKafkaOutageIT:31][outage:31] | Covered: real broker pause, pending outbox and recovery. |
| F02 | [OutboxKafkaOutageIT:101][outage:101]; [InboxConsumerIT:36][inboxIT:36] | **Partial:** no forced successful send then local rollback/crash; duplicate production is optional. Supplied duplicates prove inbox behavior only. |
| F03 | [InboxConsumerIT:55][inboxIT:55]; [OrderSagaIT:149][orderSaga:149] | Covered mechanism: injected failure after business commit before acknowledgement; no process kill. |
| F04 | [StripePaymentGatewayWireMockTest:506][gatewayTest:506]; [PaymentInitiationIT:152][initIT:152] | **Partial system scenario:** gateway timeouts/stable keys covered; initiation IT injects 500, not timeout; stateful single-PI timeout-after-provider-effect is absent. |
| F05 | [ChaosIT:113][chaos:113]; [PaymentInitiationIT:238][initIT:238] | Covered crash/retry mechanism; process-kill timing is flaky. Stateless IT alone cannot prove provider dedupe. |
| F06 | [gateway test:374][gatewayTest:374], [419][gatewayTest:419], [579][gatewayTest:579], [673][gatewayTest:673]; [InitiatePaymentsServiceTest:183][initTest:183] | Covered in layers: retry, 429 classification, breaker/backoff/exhaustion. No E2E prolonged storm. |
| F07 | [PaymentInitiationIT:186][initIT:186]; [OrderSagaIT:175][orderSaga:175] | Covered: permanent create failure and order cancellation. |
| F08 | [PaymentInitiationIT:208][initIT:208], [225][initIT:225] | Covered: cutoff without provider create, inside-window case. |
| F09 | [WebhookIT:222][webhookIT:222]; [WebhookResilienceIT:27][webhookE2E:27] | Covered ingress duplicates; E2E delivery-count race. No expired processor-lease case (A06). |
| F10 | [WebhookIT:247][webhookIT:247]; [WebhookResilienceIT:126][webhookE2E:126], [159][webhookE2E:159]; [PaymentStripeStatusTest:103][statusTest:103] | Covered old and equal-second reports; simulator excludes equal timestamps. |
| F11 | [ReconciliationIT:106][reconcileIT:106]; [WebhookResilienceIT:188][webhookE2E:188] | Covered lost PI success webhook; E2E manually triggers reconciliation with shortened staleness. |
| F12 | [WebhookIT:147][webhookIT:147], [166][webhookIT:166]; [StripeWebhookVerifierTest:65][verifierTest:65], [92][verifierTest:92] | Covered invalid/tampered/old signature and no storage. |
| F13 | [WebhookIT:192][webhookIT:192] | Rejection/no-storage/metric covered; promised ERROR log not captured/asserted. |
| F14 | [WebhookIT:302][webhookIT:302], [334][webhookIT:334]; [ProcessWebhookEventsServiceTest:393][processorTest:393] | **Partial recovery flow:** backoff/DEAD covered; manual DEAD reset followed by successful replay is untested. |
| F15 | [ConsumerRetryAndDltIT:17][retryIT:17], [77][retryIT:77]; [DeadLetterAdminIT:169][dltAdminIT:169]; [ChaosIT:165][chaos:165], [196][chaos:196] | Retry/DLT/persistence/replay covered. “No retry-topic record” checks are vacuous (T01). |
| F16 | [e2e/IdempotencyIT:29][e2eIdem:29]; [starter IdempotencyIT:174][starterIdem:174]; [OrderApiIT:399][orderApiIT:399] | Covered live-process concurrent requests. Commit/response crash and stale-owner takeover are absent (A03/A04). |
| F17 | [e2e/IdempotencyIT:92][e2eIdem:92]; [starter IdempotencyIT:145][starterIdem:145] | Covered changed-body refusal and principal separation within current claim semantics. |
| F18 | [CancellationAndRefundIT:67][cancelE2E:67], [75][cancelE2E:75] | Covered customer/timeout cancellation followed by late success compensation. |
| F19 | [CancelAndRefundIT:189][cancelIT:189]; [CancellationAndRefundIT:87][cancelE2E:87] | Covered provider unexpected_state, no cancel retry and late success handling. |
| F20 | [CancelAndRefundIT:250][cancelIT:250]; [CancellationAndRefundIT:171][cancelE2E:171] | **Partial provider lifecycle:** pending → failed covered; actual succeeded → failed is substituted and remains incorrect (A05). Retry-exhaustion interruption also absent (A02). |
| F21 | [ReconciliationIT:194][reconcileIT:194]; [WebhookResilienceIT:188][webhookE2E:188] | **Partial:** purported concurrent test can check zero payments; E2E is sequential. No forced overlap (T02). |
| F22 | [CancelAndRefundIT:274][cancelIT:274]; [ApplyOrderEventServiceTest:167][applyOrderTest:167] | Covered duplicate refund request with proper same-key marker and one Stripe call. The additional initiation IT marker is weaker (T03). |

### T01 — High assurance defect: negative Kafka assertions always return empty

[KafkaTestSupport.java:80][kafkaHelper:80] calls read(topic, 0, timeout). Its loop at [72][kafkaHelper:72] requires records.size() < minRecords, so it never polls. A populated topic still returns an empty list.

Affected evidence includes the rollback/no-publication assertion at [OutboxPublisherIT.java:134][publisherIT:134] and retry-topic absence checks at [ConsumerRetryAndDltIT.java:151][retryIT:151]. This is a test defect, not proof that production publishes rolled-back events.

Fix by snapshotting end offsets and draining to them. Scope assertions to event IDs or offset baselines: [AbstractMessagingIT.java:80][messagingBase:80] resets DB/handler state but retained Kafka topics are shared. Merely changing the polling loop can introduce test-order failures. Add a populated-topic helper regression. Estimate: 0.5–1 day.

### T02 — Medium: deterministic evidence missing for F02 and F21

F02 permits duplicates and deduplicates records in its assertion without forcing or requiring an acknowledged-send/local-rollback window. Inject failure after acknowledged send, retry the real relay, require the duplicate and then prove one inbox effect.

F21 starts an async task and immediately processes the webhook without waiting for retrieval entry. It only checks summary.failed()==0, so claiming zero payments passes. Gate provider retrieval after entry, commit the webhook, release retrieval and require summary.checked()==1 plus one transition/event. Test the opposing ordering too. Estimate: 1–2 days per scenario.

### T03 — Medium: a random-order Kafka marker is not an ordering barrier

[PaymentInitiationIT.java:123][initIT:123] says the marker follows duplicates on the same partition, but [124][initIT:124] chooses a random order ID. The topic has three partitions and orderId is the key; the marker can complete on another partition before either duplicate. The refund test repeats the pattern at [354][initIT:354].

Use a harmless same-order-key marker or await the specific inbox IDs/offsets. [CancelAndRefundIT.java:284][cancelIT:284] demonstrates a correct same-key marker. Estimate: 0.5 day.

### Potential flakes and limits of test doubles

| Location | Risk and corrective direction |
|---|---|
| [WebhookResilienceIT.java:118][webhookE2E:118]; [WebhookSender.java:102][sender:102] | Final order state can appear after the first webhook copy, before the other HTTP responses/status entries. Await sender idle before asserting all deliveries; AfterEach is too late. |
| [ChaosIT.java:117][chaos:117], [137][chaos:137] | One-second response delay plus waiting for calls then killing depends on host scheduling. The test correctly rejects a missed crash window, but can fail sporadically. Gate the response after provider effect instead. |
| [starter IdempotencyIT.java:225][starterIdem:225]; [DemoController.java:48][idemDemo:48] | Observing IN_PROGRESS then issuing request B can cross the controller's 400 ms sleep. Expecting 409 becomes timing-dependent. Use a gate and bounded futures/HTTP waits. |
| [PaymentInitiationIT.java:270][initIT:270] | Proves outside-transaction behavior using 1,500 ms provider delay versus SQL completion under 1,000 ms. Load can invalidate this threshold; use an explicit call gate and observable lock acquisition. |
| [starter IdempotencyIT.java:370][starterIdem:370]; [OrderApiIT.java:548][orderApiIT:548]; [gateway test:684][gatewayTest:684], [701][gatewayTest:701] | TTL/order/breaker tests use sleeps. Prefer controlled clocks or explicit state barriers where feasible; do not equate every fault-injection delay with a synchronization defect. |
| [StripeSimulator.java:567][simulator:567] | Globally increasing event seconds hide same-second schedules and can move fast events into the future. Its non-expiring key cache and uncached 500s do not reproduce full provider idempotency semantics. Contract smoke validates shape, not these lifecycles. |
| [InMemoryRefunds.java:13][fakeRefunds:13]; [FakeTransactions.java:18][fakeTx:18] | Refund fake returns shared references and has no rollback snapshot support. It cannot substantiate refund rollback/atomicity; use real DB tests for A02. |
| [Invariants.java:222][invariants:222] | The “derived from local ID” assertion checks only any allowed prefix. It does not validate operation-specific key or the corresponding local UUID. Gateway-focused tests give stronger evidence than this global check. |
| [pom.xml:303][pom:303] | JaCoCo append=true can preserve old local hits across verify runs. Fresh CI checkouts avoid this; release coverage should use clean data. No inflated coverage result was demonstrated in this audit. |

## 5. Quality, dead code and hexagonal boundaries

- **No material hexagonal dependency violation was found.** Domain classes remain framework-independent; use cases depend on ports; adapters own provider/JPA/web/Kafka details. Spring transaction support in application is a documented allowance. The ArchUnit check at [payment/ArchitectureTest.java:120][paymentArchTest:120] only detects direct accesses from annotated methods: transitive port calls and TransactionTemplate bodies require review/runtime evidence.
- **Duplication:** the two security configurations/audience/role converters are intentional for two services ([architecture.md:560][arch:560]). Keep their contract tests aligned; a third service should trigger the documented shared-starter decision. Similar worker claim/call/record control flow has already diverged in A02; unify atomic completion invariants before introducing a generic worker abstraction.
- **Dormant production code:** [StripeWebhookEvent.java:132][webhookDomain:132] exposes markStaleIgnored, used only by a unit test. Production stale reports end PROCESSED. Remove the dormant API/status or document its compatibility purpose.
- **Stale comments:** [infra/docker-compose.yml:307][compose:307] and observability YAML headers still promise implementation “in T16”, while current architecture defers that implementation. This is cleanup, not evidence that dashboards/exporters currently run.
- **TODO/FIXME:** none found in production or test sources. No disabled tests were found. Their absence does not close documented backlog items.
- **Test support:** unused GatedSender.gated() in AbstractRelayDisabledIT and permissive fake rollback behavior add maintenance noise. Optional/empty ArchUnit rules can also stop proving boundaries if package layout changes.
- **Documentation alignment:** retain the candid known-limitations section; correct F02/F21 evidence claims, the full-payload/secret policy conflict, and CLAUDE's observability/testing guidance when fixes are undertaken. No existing documents were edited by this audit.

## 6. Top 10 fixes

Effort is an approximate engineering estimate, not a delivery commitment. High items 4 and 5 are already documented design risks; item 6 concerns test assurance rather than a demonstrated production failure.

| Rank | Priority | Finding | Concrete result | Estimate |
|---|---|---|---|---|
| 1 | High | A01 | Make same-attached-PI completion a no-op; fence expired workers and add real overlap/lease-expiry coverage. | 1–2 days |
| 2 | High | A02 | Atomically persist exhausted refund failure and outbox; repair stranded rows; test interruption. | 1–2 days |
| 3 | High | A03 | Add idempotency claim generation/owner CAS to complete/release; test late owner after takeover. | 1–2 days |
| 4 | High | A05 | Handle actual succeeded → failed refund correction across both services; provider-faithful tests and new ADR. | 3–5 days |
| 5 | High | A04 | Add atomic durable business request identity/result for order creation; process-crash regression. | 2–4 days |
| 6 | High | T01 | Repair Kafka negative assertions with offset/event isolation and a positive helper test. | 0.5–1 day |
| 7 | Medium | S01 | Verify then redact stored webhook secrets; scrub existing payloads; signed snapshot tests; superseding ADR. | 1–2 days |
| 8 | Medium | A06 | Lock/version/fence webhook processing and failure recording; prove one effect after expired lease. | 1–2 days |
| 9 | Medium | T02 | Force post-ack relay rollback and actual webhook/reconciliation participation; correct coverage claims. | 2–4 days |
| 10 | Medium | T03 / flakes | Correct same-partition barriers; gate crash/HTTP overlap and wait for webhook deliveries before assertions. | 1–2 days |

## Verification and confidence limits

A fresh default reactor verification completed successfully with Java 21:

    env JAVA_HOME=/Users/oleksandrgorbatov/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home ./mvnw -B -ntp verify

Result: BUILD SUCCESS, finished 2026-10-09 23:59:37 +03:00, 3 min 12 s. Surefire/Failsafe totals: **1,584 tests, no failures or errors**. Spotless and the configured JaCoCo gates passed. The command was verify, not clean verify; the existing JaCoCo append caveat above applies to interpretation of local coverage.

| Module | Unit tests | Integration tests |
|---|---:|---:|
| event-contracts | 196 | 0 |
| platform-messaging-starter | 58 | 40 |
| platform-idempotency-starter | 50 | 28 |
| order-service | 265 | 203 |
| payment-service | 614 | 122 |

The optional E2E profile was **not executed** during this audit; its test code/helpers/resources were read. No live Stripe requests or provider-account changes were performed.

Four focused runtime harnesses exercised compiled production payment services/handler/domain classes with controlled clocks, gates and in-memory repository ports: A01, A02, A05 and A06. They confirm application behavior under the described schedule; they are not PostgreSQL concurrency tests or process-kill tests. SQL/entity inspection supports the persistence consequences, but the recommended fixes still require real DB regressions. A03/A04 and the remaining coverage findings are supported by static control-flow/SQL inspection and existing tests, not new fault-injection runs.

Source references below point to the audited files and one-based lines. This report is the only requested workspace change.

[adr6:1]: ../../docs/adr/0006-multi-layer-idempotency.md#L1
[adr8:20]: ../../docs/adr/0008-db-backed-work-queues.md#L20
[adr8:23]: ../../docs/adr/0008-db-backed-work-queues.md#L23
[adr9:24]: ../../docs/adr/0009-webhook-ingestion.md#L24
[adr9:40]: ../../docs/adr/0009-webhook-ingestion.md#L40
[applyOrderTest:167]: ../../services/payment-service/src/test/java/com/altronixsoft/opp/payment/application/ApplyOrderEventServiceTest.java#L167
[applyPayment:64]: ../../services/order-service/src/main/java/com/altronixsoft/opp/order/application/ApplyPaymentEventService.java#L64
[arch:292]: ../../docs/architecture.md#L292
[arch:294]: ../../docs/architecture.md#L294
[arch:303]: ../../docs/architecture.md#L303
[arch:479]: ../../docs/architecture.md#L479
[arch:488]: ../../docs/architecture.md#L488
[arch:513]: ../../docs/architecture.md#L513
[arch:525]: ../../docs/architecture.md#L525
[arch:536]: ../../docs/architecture.md#L536
[arch:537]: ../../docs/architecture.md#L537
[arch:552]: ../../docs/architecture.md#L552
[arch:553]: ../../docs/architecture.md#L553
[arch:555]: ../../docs/architecture.md#L555
[arch:556]: ../../docs/architecture.md#L556
[arch:560]: ../../docs/architecture.md#L560
[arch:562]: ../../docs/architecture.md#L562
[cancelE2E:67]: ../../e2e-tests/src/test/java/com/altronixsoft/opp/e2e/CancellationAndRefundIT.java#L67
[cancelE2E:75]: ../../e2e-tests/src/test/java/com/altronixsoft/opp/e2e/CancellationAndRefundIT.java#L75
[cancelE2E:87]: ../../e2e-tests/src/test/java/com/altronixsoft/opp/e2e/CancellationAndRefundIT.java#L87
[cancelE2E:171]: ../../e2e-tests/src/test/java/com/altronixsoft/opp/e2e/CancellationAndRefundIT.java#L171
[cancelIT:189]: ../../services/payment-service/src/test/java/com/altronixsoft/opp/payment/CancelAndRefundIT.java#L189
[cancelIT:250]: ../../services/payment-service/src/test/java/com/altronixsoft/opp/payment/CancelAndRefundIT.java#L250
[cancelIT:274]: ../../services/payment-service/src/test/java/com/altronixsoft/opp/payment/CancelAndRefundIT.java#L274
[cancelIT:284]: ../../services/payment-service/src/test/java/com/altronixsoft/opp/payment/CancelAndRefundIT.java#L284
[cancelWorker:124]: ../../services/payment-service/src/main/java/com/altronixsoft/opp/payment/application/CancelPaymentIntentsService.java#L124
[chaos:32]: ../../e2e-tests/src/test/java/com/altronixsoft/opp/e2e/ChaosIT.java#L32
[chaos:113]: ../../e2e-tests/src/test/java/com/altronixsoft/opp/e2e/ChaosIT.java#L113
[chaos:117]: ../../e2e-tests/src/test/java/com/altronixsoft/opp/e2e/ChaosIT.java#L117
[chaos:137]: ../../e2e-tests/src/test/java/com/altronixsoft/opp/e2e/ChaosIT.java#L137
[chaos:165]: ../../e2e-tests/src/test/java/com/altronixsoft/opp/e2e/ChaosIT.java#L165
[chaos:196]: ../../e2e-tests/src/test/java/com/altronixsoft/opp/e2e/ChaosIT.java#L196
[claude:62]: ../../CLAUDE.md#L62
[claude:69]: ../../CLAUDE.md#L69
[claude:74]: ../../CLAUDE.md#L74
[compose:307]: ../../infra/docker-compose.yml#L307
[consumerConfig:74]: ../../libs/platform-messaging-starter/src/main/java/com/altronixsoft/opp/platform/messaging/consumer/ConsumerAutoConfiguration.java#L74
[dltAdminIT:169]: ../../libs/platform-messaging-starter/src/test/java/com/altronixsoft/opp/platform/messaging/deadletter/DeadLetterAdminIT.java#L169
[e2eIdem:29]: ../../e2e-tests/src/test/java/com/altronixsoft/opp/e2e/IdempotencyIT.java#L29
[e2eIdem:92]: ../../e2e-tests/src/test/java/com/altronixsoft/opp/e2e/IdempotencyIT.java#L92
[fakeRefunds:13]: ../../services/payment-service/src/test/java/com/altronixsoft/opp/payment/application/InMemoryRefunds.java#L13
[fakeTx:18]: ../../services/payment-service/src/test/java/com/altronixsoft/opp/payment/application/FakeTransactions.java#L18
[gatewayTest:374]: ../../services/payment-service/src/test/java/com/altronixsoft/opp/payment/adapter/out/stripe/StripePaymentGatewayWireMockTest.java#L374
[gatewayTest:419]: ../../services/payment-service/src/test/java/com/altronixsoft/opp/payment/adapter/out/stripe/StripePaymentGatewayWireMockTest.java#L419
[gatewayTest:506]: ../../services/payment-service/src/test/java/com/altronixsoft/opp/payment/adapter/out/stripe/StripePaymentGatewayWireMockTest.java#L506
[gatewayTest:579]: ../../services/payment-service/src/test/java/com/altronixsoft/opp/payment/adapter/out/stripe/StripePaymentGatewayWireMockTest.java#L579
[gatewayTest:673]: ../../services/payment-service/src/test/java/com/altronixsoft/opp/payment/adapter/out/stripe/StripePaymentGatewayWireMockTest.java#L673
[gatewayTest:684]: ../../services/payment-service/src/test/java/com/altronixsoft/opp/payment/adapter/out/stripe/StripePaymentGatewayWireMockTest.java#L684
[gatewayTest:701]: ../../services/payment-service/src/test/java/com/altronixsoft/opp/payment/adapter/out/stripe/StripePaymentGatewayWireMockTest.java#L701
[getPayment:51]: ../../services/payment-service/src/main/java/com/altronixsoft/opp/payment/application/GetPaymentService.java#L51
[handler:89]: ../../services/payment-service/src/main/java/com/altronixsoft/opp/payment/application/StripeNotificationHandler.java#L89
[handler:143]: ../../services/payment-service/src/main/java/com/altronixsoft/opp/payment/application/StripeNotificationHandler.java#L143
[handler:167]: ../../services/payment-service/src/main/java/com/altronixsoft/opp/payment/application/StripeNotificationHandler.java#L167
[handler:235]: ../../services/payment-service/src/main/java/com/altronixsoft/opp/payment/application/StripeNotificationHandler.java#L235
[idemBuffer:37]: ../../libs/platform-idempotency-starter/src/main/java/com/altronixsoft/opp/platform/idempotency/IdempotencyBufferingFilter.java#L37
[idemDemo:48]: ../../libs/platform-idempotency-starter/src/test/java/com/altronixsoft/opp/platform/idempotency/testapp/DemoController.java#L48
[idemInterceptor:131]: ../../libs/platform-idempotency-starter/src/main/java/com/altronixsoft/opp/platform/idempotency/IdempotencyInterceptor.java#L131
[idemInterceptor:146]: ../../libs/platform-idempotency-starter/src/main/java/com/altronixsoft/opp/platform/idempotency/IdempotencyInterceptor.java#L146
[idemInterceptor:171]: ../../libs/platform-idempotency-starter/src/main/java/com/altronixsoft/opp/platform/idempotency/IdempotencyInterceptor.java#L171
[idemProps:35]: ../../libs/platform-idempotency-starter/src/main/java/com/altronixsoft/opp/platform/idempotency/IdempotencyProperties.java#L35
[idemRepo:32]: ../../libs/platform-idempotency-starter/src/main/java/com/altronixsoft/opp/platform/idempotency/IdempotencyRepository.java#L32
[idemRepo:42]: ../../libs/platform-idempotency-starter/src/main/java/com/altronixsoft/opp/platform/idempotency/IdempotencyRepository.java#L42
[idemRepo:46]: ../../libs/platform-idempotency-starter/src/main/java/com/altronixsoft/opp/platform/idempotency/IdempotencyRepository.java#L46
[idemRepo:86]: ../../libs/platform-idempotency-starter/src/main/java/com/altronixsoft/opp/platform/idempotency/IdempotencyRepository.java#L86
[idemRepo:92]: ../../libs/platform-idempotency-starter/src/main/java/com/altronixsoft/opp/platform/idempotency/IdempotencyRepository.java#L92
[idemRepo:103]: ../../libs/platform-idempotency-starter/src/main/java/com/altronixsoft/opp/platform/idempotency/IdempotencyRepository.java#L103
[idemRepo:107]: ../../libs/platform-idempotency-starter/src/main/java/com/altronixsoft/opp/platform/idempotency/IdempotencyRepository.java#L107
[inbox:54]: ../../libs/platform-messaging-starter/src/main/java/com/altronixsoft/opp/platform/messaging/inbox/InboxGuard.java#L54
[inboxIT:36]: ../../libs/platform-messaging-starter/src/test/java/com/altronixsoft/opp/platform/messaging/consumer/InboxConsumerIT.java#L36
[inboxIT:55]: ../../libs/platform-messaging-starter/src/test/java/com/altronixsoft/opp/platform/messaging/consumer/InboxConsumerIT.java#L55
[init:101]: ../../services/payment-service/src/main/java/com/altronixsoft/opp/payment/application/InitiatePaymentsService.java#L101
[init:146]: ../../services/payment-service/src/main/java/com/altronixsoft/opp/payment/application/InitiatePaymentsService.java#L146
[init:156]: ../../services/payment-service/src/main/java/com/altronixsoft/opp/payment/application/InitiatePaymentsService.java#L156
[init:180]: ../../services/payment-service/src/main/java/com/altronixsoft/opp/payment/application/InitiatePaymentsService.java#L180
[init:187]: ../../services/payment-service/src/main/java/com/altronixsoft/opp/payment/application/InitiatePaymentsService.java#L187
[init:210]: ../../services/payment-service/src/main/java/com/altronixsoft/opp/payment/application/InitiatePaymentsService.java#L210
[initIT:123]: ../../services/payment-service/src/test/java/com/altronixsoft/opp/payment/PaymentInitiationIT.java#L123
[initIT:124]: ../../services/payment-service/src/test/java/com/altronixsoft/opp/payment/PaymentInitiationIT.java#L124
[initIT:152]: ../../services/payment-service/src/test/java/com/altronixsoft/opp/payment/PaymentInitiationIT.java#L152
[initIT:186]: ../../services/payment-service/src/test/java/com/altronixsoft/opp/payment/PaymentInitiationIT.java#L186
[initIT:208]: ../../services/payment-service/src/test/java/com/altronixsoft/opp/payment/PaymentInitiationIT.java#L208
[initIT:225]: ../../services/payment-service/src/test/java/com/altronixsoft/opp/payment/PaymentInitiationIT.java#L225
[initIT:238]: ../../services/payment-service/src/test/java/com/altronixsoft/opp/payment/PaymentInitiationIT.java#L238
[initIT:270]: ../../services/payment-service/src/test/java/com/altronixsoft/opp/payment/PaymentInitiationIT.java#L270
[initIT:354]: ../../services/payment-service/src/test/java/com/altronixsoft/opp/payment/PaymentInitiationIT.java#L354
[initTest:183]: ../../services/payment-service/src/test/java/com/altronixsoft/opp/payment/application/InitiatePaymentsServiceTest.java#L183
[initTest:417]: ../../services/payment-service/src/test/java/com/altronixsoft/opp/payment/application/InitiatePaymentsServiceTest.java#L417
[invariants:222]: ../../e2e-tests/src/test/java/com/altronixsoft/opp/e2e/support/Invariants.java#L222
[kafkaHelper:72]: ../../libs/platform-messaging-starter/src/test/java/com/altronixsoft/opp/platform/messaging/outbox/KafkaTestSupport.java#L72
[kafkaHelper:80]: ../../libs/platform-messaging-starter/src/test/java/com/altronixsoft/opp/platform/messaging/outbox/KafkaTestSupport.java#L80
[messagingBase:80]: ../../libs/platform-messaging-starter/src/test/java/com/altronixsoft/opp/platform/messaging/consumer/AbstractMessagingIT.java#L80
[orderApiIT:399]: ../../services/order-service/src/test/java/com/altronixsoft/opp/order/OrderApiIT.java#L399
[orderApiIT:548]: ../../services/order-service/src/test/java/com/altronixsoft/opp/order/OrderApiIT.java#L548
[orderSaga:149]: ../../services/order-service/src/test/java/com/altronixsoft/opp/order/OrderSagaIT.java#L149
[orderSaga:175]: ../../services/order-service/src/test/java/com/altronixsoft/opp/order/OrderSagaIT.java#L175
[outage:31]: ../../libs/platform-messaging-starter/src/test/java/com/altronixsoft/opp/platform/messaging/outbox/OutboxKafkaOutageIT.java#L31
[outage:101]: ../../libs/platform-messaging-starter/src/test/java/com/altronixsoft/opp/platform/messaging/outbox/OutboxKafkaOutageIT.java#L101
[paymentApiIT:192]: ../../services/payment-service/src/test/java/com/altronixsoft/opp/payment/PaymentApiIT.java#L192
[paymentArchTest:120]: ../../services/payment-service/src/test/java/com/altronixsoft/opp/payment/ArchitectureTest.java#L120
[place:51]: ../../services/order-service/src/main/java/com/altronixsoft/opp/order/application/PlaceOrderService.java#L51
[place:77]: ../../services/order-service/src/main/java/com/altronixsoft/opp/order/application/PlaceOrderService.java#L77
[pom:303]: ../../pom.xml#L303
[processorTest:393]: ../../services/payment-service/src/test/java/com/altronixsoft/opp/payment/application/ProcessWebhookEventsServiceTest.java#L393
[publisherIT:134]: ../../libs/platform-messaging-starter/src/test/java/com/altronixsoft/opp/platform/messaging/outbox/OutboxPublisherIT.java#L134
[receive:50]: ../../services/payment-service/src/main/java/com/altronixsoft/opp/payment/application/ReceiveWebhookService.java#L50
[reconcile:133]: ../../services/payment-service/src/main/java/com/altronixsoft/opp/payment/application/ReconcilePaymentsService.java#L133
[reconcileIT:106]: ../../services/payment-service/src/test/java/com/altronixsoft/opp/payment/ReconciliationIT.java#L106
[reconcileIT:194]: ../../services/payment-service/src/test/java/com/altronixsoft/opp/payment/ReconciliationIT.java#L194
[refund:211]: ../../services/payment-service/src/main/java/com/altronixsoft/opp/payment/domain/Refund.java#L211
[refundRepo:19]: ../../services/payment-service/src/main/java/com/altronixsoft/opp/payment/adapter/out/persistence/RefundJpaRepository.java#L19
[refundTest:141]: ../../services/payment-service/src/test/java/com/altronixsoft/opp/payment/application/CreateRefundsServiceTest.java#L141
[refundWorker:121]: ../../services/payment-service/src/main/java/com/altronixsoft/opp/payment/application/CreateRefundsService.java#L121
[refundWorker:164]: ../../services/payment-service/src/main/java/com/altronixsoft/opp/payment/application/CreateRefundsService.java#L164
[refundWorker:188]: ../../services/payment-service/src/main/java/com/altronixsoft/opp/payment/application/CreateRefundsService.java#L188
[refundWorker:217]: ../../services/payment-service/src/main/java/com/altronixsoft/opp/payment/application/CreateRefundsService.java#L217
[relay:85]: ../../libs/platform-messaging-starter/src/main/java/com/altronixsoft/opp/platform/messaging/outbox/OutboxRelay.java#L85
[relay:103]: ../../libs/platform-messaging-starter/src/main/java/com/altronixsoft/opp/platform/messaging/outbox/OutboxRelay.java#L103
[retryIT:17]: ../../libs/platform-messaging-starter/src/test/java/com/altronixsoft/opp/platform/messaging/consumer/ConsumerRetryAndDltIT.java#L17
[retryIT:77]: ../../libs/platform-messaging-starter/src/test/java/com/altronixsoft/opp/platform/messaging/consumer/ConsumerRetryAndDltIT.java#L77
[retryIT:151]: ../../libs/platform-messaging-starter/src/test/java/com/altronixsoft/opp/platform/messaging/consumer/ConsumerRetryAndDltIT.java#L151
[sender:102]: ../../e2e-tests/src/test/java/com/altronixsoft/opp/e2e/support/WebhookSender.java#L102
[simulator:567]: ../../e2e-tests/src/test/java/com/altronixsoft/opp/e2e/support/StripeSimulator.java#L567
[starterIdem:145]: ../../libs/platform-idempotency-starter/src/test/java/com/altronixsoft/opp/platform/idempotency/IdempotencyIT.java#L145
[starterIdem:174]: ../../libs/platform-idempotency-starter/src/test/java/com/altronixsoft/opp/platform/idempotency/IdempotencyIT.java#L174
[starterIdem:225]: ../../libs/platform-idempotency-starter/src/test/java/com/altronixsoft/opp/platform/idempotency/IdempotencyIT.java#L225
[starterIdem:370]: ../../libs/platform-idempotency-starter/src/test/java/com/altronixsoft/opp/platform/idempotency/IdempotencyIT.java#L370
[statusTest:103]: ../../services/payment-service/src/test/java/com/altronixsoft/opp/payment/domain/PaymentStripeStatusTest.java#L103
[testing:123]: ../../docs/testing.md#L123
[testing:187]: ../../docs/testing.md#L187
[topics:15]: ../../infra/kafka/create-topics.sh#L15
[verifierTest:65]: ../../services/payment-service/src/test/java/com/altronixsoft/opp/payment/adapter/out/stripe/StripeWebhookVerifierTest.java#L65
[verifierTest:92]: ../../services/payment-service/src/test/java/com/altronixsoft/opp/payment/adapter/out/stripe/StripeWebhookVerifierTest.java#L92
[webhookController:1]: ../../services/payment-service/src/main/java/com/altronixsoft/opp/payment/adapter/in/webhook/StripeWebhookController.java#L1
[webhookDomain:132]: ../../services/payment-service/src/main/java/com/altronixsoft/opp/payment/domain/StripeWebhookEvent.java#L132
[webhookE2E:27]: ../../e2e-tests/src/test/java/com/altronixsoft/opp/e2e/WebhookResilienceIT.java#L27
[webhookE2E:118]: ../../e2e-tests/src/test/java/com/altronixsoft/opp/e2e/WebhookResilienceIT.java#L118
[webhookE2E:126]: ../../e2e-tests/src/test/java/com/altronixsoft/opp/e2e/WebhookResilienceIT.java#L126
[webhookE2E:159]: ../../e2e-tests/src/test/java/com/altronixsoft/opp/e2e/WebhookResilienceIT.java#L159
[webhookE2E:188]: ../../e2e-tests/src/test/java/com/altronixsoft/opp/e2e/WebhookResilienceIT.java#L188
[webhookEntity:17]: ../../services/payment-service/src/main/java/com/altronixsoft/opp/payment/adapter/out/persistence/StripeWebhookEventEntity.java#L17
[webhookIT:147]: ../../services/payment-service/src/test/java/com/altronixsoft/opp/payment/WebhookIT.java#L147
[webhookIT:166]: ../../services/payment-service/src/test/java/com/altronixsoft/opp/payment/WebhookIT.java#L166
[webhookIT:192]: ../../services/payment-service/src/test/java/com/altronixsoft/opp/payment/WebhookIT.java#L192
[webhookIT:222]: ../../services/payment-service/src/test/java/com/altronixsoft/opp/payment/WebhookIT.java#L222
[webhookIT:247]: ../../services/payment-service/src/test/java/com/altronixsoft/opp/payment/WebhookIT.java#L247
[webhookIT:302]: ../../services/payment-service/src/test/java/com/altronixsoft/opp/payment/WebhookIT.java#L302
[webhookIT:334]: ../../services/payment-service/src/test/java/com/altronixsoft/opp/payment/WebhookIT.java#L334
[webhookRepo:41]: ../../services/payment-service/src/main/java/com/altronixsoft/opp/payment/adapter/out/persistence/JpaWebhookEventRepository.java#L41
[webhookSql:19]: ../../services/payment-service/src/main/java/com/altronixsoft/opp/payment/adapter/out/persistence/StripeWebhookEventJpaRepository.java#L19
[webhookWorker:105]: ../../services/payment-service/src/main/java/com/altronixsoft/opp/payment/application/ProcessWebhookEventsService.java#L105
[webhookWorker:131]: ../../services/payment-service/src/main/java/com/altronixsoft/opp/payment/application/ProcessWebhookEventsService.java#L131
[webhooks:90]: ../../docs/runbooks/webhooks.md#L90
[workIT:308]: ../../services/payment-service/src/test/java/com/altronixsoft/opp/payment/WorkQueueIT.java#L308
