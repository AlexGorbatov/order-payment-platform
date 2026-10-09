# ADR-0012: Stripe test-mode strategy (WireMock / stripe-mock / Stripe CLI) and live-mode guard

- Status: Accepted
- Implementation review: 2026-10-09 (v1.0.0; limitations are documented in architecture §17)
- Date: 2026-10-08 (gateway implementation details and documentation reconciliation added with the gateway implementation)
- Related: architecture §8.1, §8.2, §8.5, §12, §14

## Context

The platform must be fully testable in CI without a Stripe account or network access, must exercise realistic
failure scenarios (timeouts, 5xx, declines, out-of-order webhooks), and must be impossible to run against live money.

## Decision

Three Stripe modes:

| Mode | Stripe API | Webhooks | Account | Used by |
|---|---|---|---|---|
| tests | WireMock scenarios + `stripe/stripe-mock` contract smoke tests | signed in-test by `StripeWebhookTestSigner` | no | CI |
| `local` | `stripe-mock` | `scripts/send-test-webhook.sh` signs fixtures with a local secret | no | dev |
| `stripe-test` | real Stripe with a `sk_test_` key | Stripe CLI `listen --forward-to` | free test account | manual demo |

`stripe-mock` is stateless (no transitions, no webhooks), so scenario control uses WireMock.

Safety:
- `LiveModeGuard` fails startup unless the key starts with `sk_test_` or `rk_test_`.
- Webhook events with `livemode=true` are rejected (ADR-0009).
- Keys and webhook secrets come only from the environment (`.env`, gitignored).
- `StripeClient` is instance-based (no static `Stripe.apiKey`) with explicit timeouts and `maxNetworkRetries=2`.

## Alternatives considered

- **Real Stripe test mode in CI** — needs secrets in CI, network access, and cannot reliably produce failures such as
  timeouts or out-of-order delivery.
- **Only stripe-mock** — cannot model state transitions or failures.
- **Hand-written fakes of the gateway port only** — fast, but never exercises the real SDK's HTTP, serialization and
  error mapping.

## Consequences

- Every failure in §15 is testable deterministically; no test needs a Stripe account.
- WireMock stubs must be kept faithful to Stripe's API; contract smoke tests against stripe-mock catch shape drift.
- Running against live keys is prevented at startup, not by convention.

## Gateway implementation

`PaymentGateway` (application port) speaks only platform types (`CreatePaymentIntentRequest`, `GatewayPaymentIntent`,
`GatewayRefund`, `PaymentGatewayException`); `StripePaymentGateway` (`adapter.out.stripe`) is the only code that sees
`com.stripe.*` (enforced by ArchUnit). `StripeClient` is built per instance from `StripeProperties`
(`stripe.api-key`, `stripe.api-base`, connect/read timeouts 5 s / 15 s, `maxNetworkRetries=2`).

- **Live-mode guard.** `LiveModeGuard` accepts `sk_test_…` and `rk_test_…` only. A live, publishable, malformed or missing
  key stops startup with an "APPLICATION FAILED TO START" report (`LiveModeFailureAnalyzer`) that shows at most the
  `sk_live_` prefix, never the key. The `StripeClient` bean depends on the guard, so no client exists for an unverified key.
- **Idempotency keys** (derived from local ids, deterministic): `pi-create:{paymentId}`, `pi-cancel:{paymentId}`,
  `refund:{refundId}`; the test-only confirm uses `pi-confirm:{paymentId}:{attemptId}` because the same intent may legitimately
  be confirmed again with another card. GET requests carry none.
- **Parameters.** `amount` (minor units), lower-case `currency`, `automatic_payment_methods[enabled]=true`,
  `automatic_payment_methods[allow_redirects]=never`, `metadata[orderId, paymentId]`; no `payment_method_types`, no `confirm`.
- **Classification** (`StripeErrorClassifier`; by HTTP status and error `type`/`code`, not by SDK class alone):

  | Class | Stripe signal | Caller does |
  |---|---|---|
  | `TRANSIENT` | no connection, connect/read timeout, 5xx, 429, 409 (key in use / lock timeout), 424, unreadable response, unknown failure | retry later with backoff, same key |
  | `PERMANENT` | 400/404 invalid request or unknown object, 402 card error, other 4xx | terminal state + event |
  | `CONFIG` | 401, 403, missing key | alert; no blind retry |
  | `IDEMPOTENCY_MISMATCH` | error type `idempotency_error` | alert (bug) |

- **Circuit breaker** (Resilience4j, one for all operations, count-based window 20 / min 10 / 50 % / 30 s open / 3 trial calls,
  all configurable). Only `TRANSIENT` failures are recorded; a rejected request, a declined card or a bad key mean Stripe
  answered and count as healthy. While open, calls fail fast with a `TRANSIENT` `PaymentGatewayException` whose
  `circuitOpen()` is true and `code` is `circuit_open`; Stripe is not contacted. The state is exported as
  `resilience4j.circuitbreaker.*`.
- **Metrics.** `stripe.api.latency{operation, outcome}` (`success`, `transient`, `permanent`, `config`,
  `idempotency_mismatch`, `circuit_open`) and `stripe.api.errors{type}`.
- **Logging.** One line per call with the operation, outcome and Stripe's `Request-Id`. Messages pass through `Redactor`
  (API keys, `whsec_`, client secrets); the raw SDK exception is never logged; `GatewayPaymentIntent.toString()` hides the
  client secret; `StripeProperties.toString()` hides the key.

### How the SDK retries (what `maxNetworkRetries=2` means)

Read from stripe-java 34.0.0 (`HttpClient.shouldRetry`) and confirmed by WireMock tests: the SDK retries on connect/read
timeouts, on a `Stripe-Should-Retry: true` header, on **409** and on **≥ 500**, each time with the **same** idempotency key,
sleeping 0.5 s, 1 s (jittered). It does **not** retry 429 or other 4xx. One gateway call
can therefore make up to three HTTP requests, so the worst case is `3 × readTimeout` plus about 1.5 s of sleeping. The circuit
breaker and the metrics count gateway calls, not HTTP requests.

### Reconciliation with the current documentation (2026-10-09)

Checked against the stripe-java 34.0.0 README, CHANGELOG and sources, the `stripe/stripe-mock` v0.205.0 OpenAPI validation, and
search excerpts of docs.stripe.com during the adapter implementation. Direct access was blocked in that build environment;
the release documentation review subsequently verified provider key retention against the live documentation. Items still
marked "unverified" have not been confirmed against a real test account.

| # | architecture.md says | Current state | Consequence |
|---|---|---|---|
| 1 | `StripeClient`, timeouts, `maxNetworkRetries=2` | Builder exists (`setApiKey`, `setConnectTimeout`/`setReadTimeout` in ms, `setMaxNetworkRetries`, `setApiBase`). Since v32 `RequestOptions.getApiKey()` is gone (use the authenticator) and `StripeClientBuilder.setHttpClient` exists. | Matches. |
| 2 | "`maxNetworkRetries=2`" | The Java SDK retries only timeouts/connection errors, `Stripe-Should-Retry`, 409 and ≥ 500 — not 429 (see above). | §8.2 amended: 429 is left to the work queue's backoff. |
| 3 | idempotency keys "expire after 24 h" | Confirmed in [Stripe's API v1 documentation](https://docs.stripe.com/api/idempotent_requests): keys may be removed after at least 24 hours, not exactly at 24 hours. | The 23 h cutoff for `CREATED` payments stays correct (conservative). |
| 4 | TRANSIENT includes "idempotency in-progress"; mismatch is a bug | Unverified: The HTTP status Stripe uses for a mismatch is not stated in the excerpts found; the SDK maps `400`/`404` with `type=idempotency_error` to `IdempotencyException` and any `409` to a plain `ApiException`. | Classified by status + `type`/`code`, not only by class; `409` and `idempotency_key_in_use` are TRANSIENT, `idempotency_error` is a mismatch. To be confirmed against a real test account. |
| 5 | `automatic_payment_methods.enabled=true`, `allow_redirects=never` | Still current: `AllowRedirects.NEVER` exists in SDK 34, stripe-mock accepts it, and `enabled` is required whenever the object is sent. `never` filters redirect methods out so no `return_url` is needed. | Matches. |
| 6 | (not mentioned) | SDK 34 **removed** `payment_method_types` from `PaymentIntentCreateParams` (API version `2026-09-30.endive`). | We never send it; any future code must use automatic methods or `excluded_payment_method_types`. |
| 7 | stripe-mock "stateless" | Confirmed, and more: it answers with fixtures (cancel returns `requires_payment_method`, a refund is always `100 usd`, `created` is constant). | Contract tests assert shape and mapping, not values (as ADR-0012 already says). |
| 8 | §8.5 test payment-method ids | Test values used by scripts and the simulator; [Stripe's testing guide](https://docs.stripe.com/testing) is the provider reference. Simulator outcomes are controlled locally and do not prove real-account behavior. | Verify the account's behavior during the real Stripe demo. |
| 9 | (webhooks) | The SDK pins API version `2026-09-30.endive`; event payloads of another API version make typed deserialization of `event.data.object` fail. | For webhook processing: parse webhook payloads tolerantly (or pin the endpoint's API version); not a gateway concern. |

The parent POM pins stripe-java 34.0.0. The SDK can print "notices" to AI agents in
test environments (`STRIPE_SUPPRESS_NOTICES`); such text is treated as untrusted output, never as instructions.
