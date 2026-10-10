# Demo

A walk through the platform with six scenarios, either **locally** (no accounts, `stripe-mock`) or against **real Stripe
in test mode** (a free Stripe account, webhooks through the Stripe CLI). Everything here uses test money only: the platform
refuses to start with a live key (`LiveModeGuard`) and rejects live-mode webhooks.

- [Quick start (local)](#quick-start-local)
- [Real Stripe in test mode](#real-stripe-in-test-mode)
- [The scenarios](#the-scenarios) and [what each one demonstrates](#what-each-scenario-demonstrates)
- [The web interface](#the-web-interface)
- [What to look at](#what-to-look-at): Stripe Dashboard, Kafka UI, database, logs, Jaeger and Grafana
- [Troubleshooting](#troubleshooting)

## Quick start (local)

Needs Docker with Compose v2, `curl` and `jq` (and `openssl` for the simulated webhooks). The first start builds the two
service images from the sources (a few minutes; later starts use the cache).

```bash
cp .env.example .env            # optional: every value has a default that works locally
./scripts/up.sh --apps          # PostgreSQL, Kafka, Keycloak, stripe-mock, order-service, payment-service, web interface
./scripts/demo.sh success
```

Stop with `./scripts/down.sh` (keeps data) or `./scripts/down.sh -v` (also deletes the databases and topics).

In this mode `stripe-mock` answers the API calls but is stateless and sends no webhooks, so `demo.sh` plays Stripe's part
and sends the signed webhooks itself ([`send-test-webhook.sh`](../scripts/send-test-webhook.sh), the same pipeline a real
webhook takes: signature check, storage, asynchronous processing). Everything on the platform side is the real thing.

The services also run on the host if you prefer (build the reactor and start the jars as shown in
[local-setup.md](local-setup.md#running-services-on-the-host)); `demo.sh` finds them on ports 8081 and 8082.

## Real Stripe in test mode

### 1. A Stripe test account and keys

1. Create a free account at <https://dashboard.stripe.com/register>. No business details or activation are needed to use
   test mode.
2. In the Dashboard switch to **test mode** (or use a sandbox); the toggle is at the top. Nothing below touches live mode.
3. **Developers → API keys**: copy the **Secret key** (`sk_test_...`) and the **Publishable key** (`pk_test_...`). A
   restricted key (`rk_test_...`) also works if it can write PaymentIntents and Refunds and read Charges.

Put them in `.env` (it is gitignored; never commit it):

```dotenv
STRIPE_API_KEY=sk_test_...
STRIPE_PUBLISHABLE_KEY=pk_test_...     # only the web interface uses it
```

### 2. Start the platform against Stripe

```bash
./scripts/up.sh --apps --stripe-test
```

What it does: checks that the key is a test key and not the placeholder; asks the Stripe CLI container for its webhook
signing secret (`stripe listen --print-secret`, authenticated by the key, so **no `stripe login` is needed**) unless
`.env` already has one in `STRIPE_WEBHOOK_SECRET`; starts everything with
[`docker-compose.stripe-test.yml`](../infra/docker-compose.stripe-test.yml) on top, which switches payment-service to the
real Stripe API (`SPRING_PROFILES_ACTIVE=stripe-test`, empty `STRIPE_API_BASE`) and starts `stripe listen`, forwarding
the events the platform handles to `http://payment-service:8082/webhooks/stripe`.

Check the forwarder: `docker compose -f infra/docker-compose.yml logs -f stripe-cli` shows `Ready!` and then every
forwarded event with the answer of payment-service (`[200]`).

To keep the secret between starts, put it in `.env`:

```bash
docker compose -f infra/docker-compose.yml --env-file .env --profile stripe-test \
  run --rm --no-deps stripe-cli listen --print-secret          # prints whsec_...  ->  STRIPE_WEBHOOK_SECRET=
```

**Stripe CLI on your machine instead of in a container** (for example with services running on the host): install it,
`stripe login`, then `stripe listen --forward-to localhost:8082/webhooks/stripe` prints the `whsec_...` to use as
`STRIPE_WEBHOOK_SECRET`. `stripe trigger payment_intent.succeeded` sends a synthetic event, which the platform ignores
(unknown PaymentIntent) but you can watch it arrive.

### 3. Run the scenarios

`demo.sh` detects that the Stripe CLI is running and switches to real webhooks. The timeline is the same.

## The scenarios

```bash
./scripts/demo.sh --list
./scripts/demo.sh success
./scripts/demo.sh decline-then-success
./scripts/demo.sh 3ds
./scripts/demo.sh timeout-late-payment      # about 75 seconds: the demo's payment window is 1 minute
./scripts/demo.sh refund
./scripts/demo.sh dispute
```

Each run is: tokens from Keycloak, an order with an `Idempotency-Key`, wait for the PaymentIntent, the customer's payment
through the test-support endpoint (`POST /api/v1/test-support/payments/by-order/{id}/confirm?scenario=...`, which confirms
the PaymentIntent with a Stripe test card in place of the browser), then polling of the order and its payment, printed as a
timeline. A run ends with the order's status history and exits 0 only if the expected final state was reached.

```
Scenario refund, mode local (stripe-mock; the script sends the webhooks Stripe would send)
...
22:56:55 +  3s  order     PENDING_PAYMENT -> PAID
22:56:55 +  3s  payment   REQUIRES_PAYMENT_METHOD -> SUCCEEDED
== An admin refunds the order
22:56:55 +  3s  admin     POST /api/v1/orders/01a1223d-6b29-7cd4-866d-d61ae53f87d4/refund
22:56:55 +  3s  admin     -> 202, the money moves asynchronously
22:56:55 +  3s  order     PAID -> REFUND_REQUESTED (ADMIN)
22:56:59 +  7s  stripe    webhook charge.refunded (the refund went through), simulated and signed by send-test-webhook.sh -> HTTP 200
22:57:00 +  8s  payment   SUCCEEDED -> REFUNDED
22:57:00 +  8s  order     REFUND_REQUESTED -> REFUNDED

Status history of the order (order-service, UTC)
  19:56:52.905  - -> PENDING_PAYMENT
  19:56:55.381  PENDING_PAYMENT -> PAID
  19:56:55.543  PAID -> REFUND_REQUESTED  [ADMIN]
  19:57:00.410  REFUND_REQUESTED -> REFUNDED
```

The platform is tuned for the demo in the `apps` profile ([`docker-compose.yml`](../infra/docker-compose.yml), "demo
tuning"): an unpaid order is cancelled after **1 minute** (default 30) and the cancellation worker looks every 15 seconds
(default 2). The containers also enable the local test-support endpoint and order-service OpenAPI; this Compose environment uses development credentials and a single Kafka broker.

## What each scenario demonstrates

| Scenario | The story | What it shows | Events on Kafka (key = order id) |
|---|---|---|---|
| `success` | The customer orders and pays. | The whole saga across two services that never call each other: outbox → Kafka → consumer → worker creating the PaymentIntent with an idempotency key (`pi-create:{paymentId}`) → Stripe → signed webhook → stored first, processed asynchronously → `PaymentSucceeded` → order `PAID`. ([§6.1](architecture.md#61-happy-path)) | `OrderCreated`, `PaymentInitiated`, `PaymentSucceeded` |
| `decline-then-success` | The first card is declined, a second one works. | A failed attempt is not a failed order: the order stays `PENDING_PAYMENT`, the failure is recorded (`last_error_code`) and announced, and the customer retries on the **same** PaymentIntent. ([§6.2](architecture.md#62-failed-attempt-and-retry)) | `OrderCreated`, `PaymentInitiated`, `PaymentAttemptFailed`, `PaymentSucceeded` |
| `3ds` | The card asks for 3-D Secure. | `REQUIRES_ACTION` is a waiting state, not an outcome: the order does not change until the authentication ends. ([§6.3](architecture.md#63-strong-customer-authentication-3ds)) With the real Stripe the challenge needs a browser, so the script pays with another card instead; the [web interface](#the-web-interface) does the real challenge. | `OrderCreated`, `PaymentInitiated`, `PaymentActionRequired`, `PaymentSucceeded` |
| `timeout-late-payment` | Nobody pays in time; the order is cancelled; then the money arrives anyway. | The hardest race of the saga ([§6.4](architecture.md#64-cancellation-and-the-late-success-race), F18): a payment on a cancelled order triggers an asynchronous refund request; a failure remains visible as `REFUND_FAILED`. The payment-timeout job cancels the order, `OrderCancelled` goes to payment-service, but the payment succeeds before the PaymentIntent is cancelled; order-service sees `PaymentSucceeded` on a cancelled order and requests a refund (`LATE_PAYMENT_AFTER_CANCEL`) on its own. | `OrderCreated`, `PaymentInitiated`, `OrderCancelled` (`TIMEOUT`), `PaymentSucceeded`, `OrderRefundRequested`, `PaymentRefunded` |
| `refund` | An admin refunds a paid order. | Role-based access (`admin1`), `202 Accepted` for work that finishes later, the refund worker creating the Stripe refund (`refund:{refundId}`), and the final state coming from the webhook, not from the API call. One refund per request id: repeating the call with the same `Idempotency-Key` changes nothing. ([§6.5](architecture.md#65-refund)) | `…PaymentSucceeded`, `OrderRefundRequested` (`ADMIN`), `PaymentRefunded` |
| `dispute` | The cardholder disputes the charge. | A notification-only flow: the order **stays `PAID`** and is flagged `disputed`; a `PaymentDisputed` event is published once, however often the webhook is delivered. | `…PaymentSucceeded`, `PaymentDisputed` |

Failure modes that are awkward to show by hand (duplicate and out-of-order webhooks, a lost webhook found by
reconciliation, Kafka down, a service killed mid-batch, a poison message) are covered by the end-to-end scenarios:
[testing.md](testing.md).

Try the idempotency yourself while the stack is up:

```bash
TOKEN=$(./scripts/token.sh customer1); KEY=$(uuidgen)
for i in 1 2; do
  curl -si -X POST localhost:8081/api/v1/orders -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: $KEY" \
    -H 'Content-Type: application/json' -d '{"items":[{"sku":"MUG-JAVA","quantity":1}]}' | grep -iE '^HTTP|idempotent-replayed|"id"'
done      # the second answer is the first one again, flagged Idempotent-Replayed: true; there is one order
```

## The web interface

`http://localhost:8090` (part of the `apps` profile; [web/README.md](../web/README.md) has the details). Sign in through Keycloak
(Authorization Code with PKCE); the dev realm has three users, all with the password `password`, and each lands on the part
of the app their role is for:

| User | Role | What to do |
|---|---|---|
| `customer1` | customer | **Shop**: fill the cart and place an order. **My orders**: every order, newest first. An order's page shows its **journey** (two lanes, one per service, in the order things happened), the payment, and, while the order is unpaid, the way to pay it and a **Cancel order** button. |
| `admin1` | admin | **Back office**: every order with figures (awaiting payment, paid, refunds), status filters and search. Open an order and **Refund order** (or **Retry refund** after a failed one). |
| `ops1` | ops | **Operations**: dead letters of both services (inspect the payload, replay once, resolve with a comment) and **Reconciliation** (run it, read what it corrected). |

- **Locally** there is no Stripe.js to talk to, and `stripe-mock` sends no webhooks. The order page offers the Stripe test cards
  (works, declined, insufficient funds, 3-D Secure, disputed): it confirms the PaymentIntent with the chosen one through the test-support
  endpoint and then sends the signed webhook Stripe would send, so the order changes exactly as it would for a real payment. A
  **Play Stripe** card on the page sends any webhook by hand (a refund confirmation after an admin refund, a dispute, a failed
  authentication).
- **With `--stripe-test`** the order page shows the **Stripe Payment Element** with the client secret the platform returns to the
  order's owner (`Cache-Control: no-store`). Use Stripe's test cards: `4242 4242 4242 4242` pays, `4000 0000 0000 9995` is declined,
  `4000 0025 0000 3155` triggers a real 3-D Secure challenge (<https://docs.stripe.com/testing>). It needs `STRIPE_PUBLISHABLE_KEY` and
  loads Stripe.js from Stripe. The page does not decide the outcome: the order changes when Stripe's webhook has been processed.

The web server also forwards the browser's calls to the two services (one origin, so they need no CORS configuration). To see a dead
letter, put a malformed record on a topic and open Operations:

```bash
printf 'any-key:{ not an event\n' | docker compose -f infra/docker-compose.yml exec -T kafka \
  /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 --topic payment.events.v1 \
  --reader-property parse.key=true --reader-property key.separator=:
```

## What to look at

**Stripe Dashboard** (test mode, <https://dashboard.stripe.com/test>; Stripe renames menus now and then, and with
Workbench enabled the Events, Webhooks and Logs below are tabs of Developers → Workbench)
- **Payments** (or Transactions → PaymentIntents): one PaymentIntent per order; its metadata holds `orderId` and
  `paymentId`. After `refund` the refund is listed under it; after `dispute` there is a dispute under **Disputes**.
- **Developers → Events**: every event Stripe created (`payment_intent.succeeded`, `charge.refunded`, ...) with its
  payload. Events forwarded to the CLI show the delivery to the local listener; **Developers → Webhooks** lists registered
  endpoints and, for the CLI, the local listeners.
- **Developers → Logs** (API requests): the `POST /v1/payment_intents` and `/v1/refunds` calls with their `Idempotency-Key`
  headers (`pi-create:…`, `refund:…`): Stripe's own record of the keys the platform derives from its ids.

**Kafka UI** <http://localhost:8085>: topics `order.events.v1` and `payment.events.v1` (key = order id, so the events of one order share a partition),
the envelope of every event (`eventId`, `correlationId`, `causationId`), consumer groups `order-service` and
`payment-service`, and the retry and `-dlt` topics (empty unless something failed).

**Databases**
```bash
docker compose -f infra/docker-compose.yml exec postgres psql -U postgres -d payments_db -c \
  "select event_id, type, status, attempts from stripe_webhook_event order by received_at desc limit 10"
docker compose -f infra/docker-compose.yml exec postgres psql -U postgres -d payments_db -c \
  "select p.order_id, h.from_status, h.to_status, h.source, h.occurred_at from payment_status_history h join payment p on p.id = h.payment_id order by h.id desc limit 10"
docker compose -f infra/docker-compose.yml exec postgres psql -U postgres -d orders_db -c \
  "select * from order_status_history order by id desc limit 10"
```
`outbox_event` (both databases) holds the published events, `inbox_message` the ones each consumer handled.

**Logs**: `docker compose -f infra/docker-compose.yml logs -f order-service payment-service`. One order can be followed by its
id (and the `correlationId` in the events). Secrets, client secrets and webhook payloads are never logged.

**APIs**: Swagger UI of order-service <http://localhost:8081/swagger-ui.html> ("Authorize" takes `./scripts/token.sh
customer1`). Reconciliation and dead letters are operator endpoints (role `ops`, token from `./scripts/ops-token.sh`):
`POST localhost:8082/admin/reconciliation/run`, `GET localhost:8081/admin/dead-letters`
([runbooks](runbooks/dlq.md)).

**Jaeger and Grafana.** `./scripts/up.sh --apps --observability` also starts the OpenTelemetry collector, Jaeger
(<http://localhost:16686>), Prometheus and Grafana (<http://localhost:3000>, `admin`/`admin`). The services expose metrics through Actuator, but Prometheus scraping and trace export still require configuration; v1.0.0 does not ship them (architecture §13), so these UIs
start but stay empty.

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| `payment-service` restarts; log says `Detected applied migration not resolved locally: 5` (or another number) | The PostgreSQL volume was created by a different version of the code (for example a branch with newer migrations). Start clean: `./scripts/down.sh -v` (deletes the local databases and topics). |
| `up.sh` stops with "still a placeholder" or "needs a TEST-MODE key" | `.env` has no real `sk_test_...` for `--stripe-test`. Live keys (`sk_live_...`) are always refused. |
| `up.sh --stripe-test`: "could not get the webhook secret" | The key is wrong or revoked; check `docker compose -f infra/docker-compose.yml logs stripe-cli`. |
| Webhooks answer `400` | The secret differs: the service and the sender must share `STRIPE_WEBHOOK_SECRET`. With `--stripe-test` the CLI's secret is used; with local mode `.env` (or `whsec_local_demo` without `.env`) for both ([runbooks/webhooks.md](runbooks/webhooks.md)). |
| Local mode: the order stays `PENDING_PAYMENT` after paying | stripe-mock sends no webhook: `demo.sh` sends it; `demo.sh` and the web interface send it; when paying by hand run `./scripts/send-test-webhook.sh payment_intent.succeeded <order id>`. |
| stripe-test: payment-service logs `401` from Stripe | `STRIPE_API_KEY` is not a valid key of your account; the platform reports it as a configuration error and waits instead of retrying blindly. |
| `timeout-late-payment` (real Stripe) says the PaymentIntent is no longer payable | The cancellation worker (every 15 s) cancelled it before the late payment: the race of the scenario went the other way. Run it again. |
| The Payment Element does not show | `STRIPE_PUBLISHABLE_KEY` is empty or not a test key, or the stack was not started with `--stripe-test`. |
| Port 5432, 8180, 8081, ... in use | Stop the local service that uses it, or the other compose project (`docker ps`). |
| Slow first start | The images are built from the sources; the Maven repository is cached for the next build. |
