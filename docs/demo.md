# Demo guide

Run the platform on your machine and watch an order become a payment. Two ways: **locally** (no accounts, a Stripe stand-in) or
against **real Stripe in test mode** (a free Stripe account). Only test money is involved: the application refuses to start with
a live key and rejects live-mode webhooks.

## Start

Needs Docker with Compose v2, `curl`, `jq` and `openssl`. The first start builds the images (a few minutes).

```bash
./scripts/up.sh --apps          # PostgreSQL, Kafka, Keycloak, stripe-mock, both services, web interface
./scripts/demo.sh success       # place an order, pay it, watch it become PAID
./scripts/down.sh               # stop, keep data (./scripts/down.sh -v also deletes it)
```

No configuration is needed locally. `stripe-mock` answers API calls but sends no webhooks, so the demo plays Stripe's part and
sends the signed webhooks itself.

| What | Where |
|---|---|
| Web interface | <http://localhost:8090> |
| Kafka UI | <http://localhost:8085> |
| Swagger UI (order-service) | <http://localhost:8081/swagger-ui.html> |
| Keycloak | <http://localhost:8180> |

## Sign in

All users have the password `password` and land on the part of the app their role is for.

| User | Role | What to do |
|---|---|---|
| `customer1`, `customer2` | customer | **Shop**: fill the cart and place an order. **My orders**: open one to see its journey across the two services, pay it, or cancel it. |
| `admin1` | admin | **Back office**: every order with figures, filters and search; refund a paid order or retry a failed refund. |
| `ops1` | ops | **Operations**: dead letters (inspect, replay, resolve) and reconciliation. |

Locally the order page offers the Stripe test cards (works, declined, insufficient funds, 3-D Secure, disputed) and sends the
webhook Stripe would send. With real Stripe it shows the Stripe Payment Element instead.

## Scenarios

```bash
./scripts/demo.sh --list
./scripts/demo.sh <scenario>
```

| Scenario | Story |
|---|---|
| `success` | order, PaymentIntent, card accepted, webhook, order `PAID` |
| `decline-then-success` | a declined card leaves the order open; a second card pays on the same PaymentIntent |
| `3ds` | the card asks for 3-D Secure; the order waits, then it is paid |
| `timeout-late-payment` | nobody pays, the order is cancelled, the money arrives anyway and is refunded automatically (about 75 seconds) |
| `refund` | an admin refunds a paid order: `REFUND_REQUESTED`, `REFUNDED` |
| `dispute` | the cardholder disputes the charge: the order stays `PAID` and is flagged |

Each run prints a timeline of order and payment statuses and exits non-zero unless the expected final state is reached. In the
demo, an unpaid order is cancelled after 1 minute (30 by default).

## Real Stripe in test mode

1. Create a free account at <https://dashboard.stripe.com/register> and switch the Dashboard to **test mode**.
2. Under **Developers → API keys**, copy the secret key (`sk_test_...`) and the publishable key (`pk_test_...`) into `.env` (it is
   gitignored):

   ```dotenv
   STRIPE_API_KEY=sk_test_...
   STRIPE_PUBLISHABLE_KEY=pk_test_...
   ```

3. Start with the Stripe CLI forwarding webhooks:

   ```bash
   ./scripts/up.sh --apps --stripe-test
   ./scripts/demo.sh success
   ```

Test cards: `4242 4242 4242 4242` pays, `4000 0000 0000 9995` is declined, `4000 0025 0000 3155` asks for 3-D Secure
(<https://docs.stripe.com/testing>). In the Dashboard you will see one PaymentIntent per order, the events Stripe sent, and the
API requests with their `Idempotency-Key` headers.

## Try it by hand

```bash
TOKEN=$(./scripts/token.sh customer1)
curl -s -H "Authorization: Bearer $TOKEN" localhost:8081/api/v1/products | jq
curl -s -X POST localhost:8081/api/v1/orders -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: $(uuidgen)" -H 'Content-Type: application/json' \
  -d '{"items":[{"sku":"MUG-JAVA","quantity":2}]}' | jq
```

Prices are never sent; every state-changing call needs an `Idempotency-Key`; repeating a call with the same key returns the first
answer. In Kafka UI, `order.events.v1` and `payment.events.v1` show the events of each order.

## Accounts and ports (development only)

| What | Value |
|---|---|
| order-service, payment-service | 8081, 8082 |
| PostgreSQL | 5432 (`orders`/`orders`, `payments`/`payments`) |
| Keycloak admin console | `admin` / `admin` |

## Troubleshooting

| Symptom | Fix |
|---|---|
| A service restarts with `applied migration not resolved locally` | The database volume comes from other code. `./scripts/down.sh -v` starts clean. |
| `up.sh` says the key is a placeholder or not a test key | Put a real `sk_test_...` key in `.env`. Live keys are always refused. |
| Webhooks answer `400` | The service and the sender must share `STRIPE_WEBHOOK_SECRET`. |
| Local: the order stays `PENDING_PAYMENT` after paying | Local mode has no real Stripe: use `demo.sh`, the web interface, or `./scripts/send-test-webhook.sh payment_intent.succeeded <order id>`. |
| The Payment Element does not show | `STRIPE_PUBLISHABLE_KEY` is empty or not a test key, or the stack was not started with `--stripe-test`. |
| A port is in use | Stop the local service that uses it. |
