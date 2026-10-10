# Local Setup

Local infrastructure for development and the manual demo (architecture §8.1, §12, §16).
Everything lives in [`infra/docker-compose.yml`](../infra/docker-compose.yml). The services run on the host
(`java -jar` after building the reactor) or as containers (`./scripts/up.sh --apps`, images built from
[`infra/Dockerfile`](../infra/Dockerfile)); for a guided tour see [demo.md](demo.md).

## Prerequisites

- Docker Engine 24+ with Compose v2 (`docker compose version`)
- `bash`, `curl`, `jq`, `openssl`
- JDK 21 when running services on the host or tests; the `apps` image build supplies its own JDK

## Start and stop

```bash
cp .env.example .env              # optional: every variable has a dev default
./scripts/up.sh                   # default profile; waits until every container is healthy
./scripts/up.sh --apps            # + order-service, payment-service and the checkout page as containers
./scripts/up.sh --apps --stripe-test   # ... against real Stripe (test mode) with the Stripe CLI forwarding webhooks
./scripts/up.sh --observability   # + OTel collector, Jaeger, Prometheus, Grafana
./scripts/down.sh                 # stop, keep data
./scripts/down.sh -v              # stop and delete volumes (fresh databases and topics on next start)
```

The scripts pass the repo-root `.env` to Compose. Without the scripts:

```bash
docker compose -f infra/docker-compose.yml --env-file .env up -d --wait
docker compose -f infra/docker-compose.yml ps -a
```

`kafka-init` is a one-shot container: it shows `Exited (0)` once the topics exist. All other containers report
`(healthy)`.

## Compose profiles

| Profile | Containers | Purpose |
|---|---|---|
| default | postgres, kafka, kafka-init, kafka-ui, keycloak, stripe-mock | infrastructure for the `local` Spring profile |
| `apps` | order-service, payment-service, checkout | the services as containers (stripe-mock, `local` Spring profile) and the demo checkout page |
| `stripe-test` | stripe-cli | real Stripe test mode: forwards webhooks to payment-service on the host; with `apps` and [`docker-compose.stripe-test.yml`](../infra/docker-compose.stripe-test.yml) (what `up.sh --apps --stripe-test` does) it also switches payment-service to the real Stripe API |
| `observability` | otel-collector, jaeger, prometheus, grafana | tracing and metrics backends (skeleton configuration: no service targets, no dashboard; see architecture §13) |

## Ports

| Component | Host port | Notes |
|---|---|---|
| order-service | 8081 | on the host, or the `apps` container |
| payment-service | 8082 | on the host, or the `apps` container; webhook endpoint `/webhooks/stripe` |
| checkout page | 8090 | `apps`; nginx with a proxy to the two services |
| PostgreSQL | 5432 | |
| Kafka | 9092 | `localhost:9092` from the host; `kafka:19092` inside the compose network |
| kafka-ui | 8085 | http://localhost:8085 |
| Keycloak | 8180 | http://localhost:8180 (admin console `/admin`) |
| stripe-mock | 12111 | HTTP; any `sk_test_` key is accepted |
| OTel collector | 4317 / 4318 | OTLP gRPC / HTTP (`observability`) |
| Jaeger UI | 16686 | `observability` |
| Prometheus | 9090 | `observability` |
| Grafana | 3000 | `observability` |

## Accounts (dev only)

| What | Credentials |
|---|---|
| PostgreSQL superuser | `postgres` / `postgres` |
| `orders_db` owner | `orders` / `orders` — `jdbc:postgresql://localhost:5432/orders_db` |
| `payments_db` owner | `payments` / `payments` — `jdbc:postgresql://localhost:5432/payments_db` |
| Keycloak admin console | `admin` / `admin` (master realm) |
| Grafana | `admin` / `admin` |

Each service role owns only its database; `CONNECT` is revoked from `PUBLIC`, so `orders` cannot open `payments_db`
and vice versa. The init script runs only on an empty volume — after changing DB credentials run `./scripts/down.sh -v`.

### Keycloak realm `opp`

Imported from [`infra/keycloak/realm-opp.json`](../infra/keycloak/realm-opp.json) on every start (Keycloak runs
without a volume, so the file is the single source of truth — edit it and restart the container).

| User | Password | Realm role | `sub` |
|---|---|---|---|
| `customer1` | `password` | `customer` | `00000000-0000-4000-8000-000000000001` |
| `customer2` | `password` | `customer` | `00000000-0000-4000-8000-000000000002` |
| `admin1` | `password` | `admin` | `00000000-0000-4000-8000-0000000000a1` |
| `ops1` | `password` | `ops` | `00000000-0000-4000-8000-0000000000f1` |

| Client | Type | Flows |
|---|---|---|
| `opp-web` | public | Authorization Code + PKCE (S256); **password grant enabled only because this is the dev realm** |
| `opp-ops-cli` | confidential | client credentials only; service account has role `ops`; secret `KEYCLOAK_OPS_CLIENT_SECRET` (default `opp-ops-cli-dev-secret`) |

Both clients add `order-service` and `payment-service` to `aud`. The issuer is always
`http://localhost:8180/realms/opp` (`KC_HOSTNAME`), including for tokens requested from inside the compose network;
JWKS: `http://localhost:8180/realms/opp/protocol/openid-connect/certs`.

## Running services on the host

Compose does not export `.env` variables into your shell. After `./scripts/up.sh`, build the service jars and start each in a separate terminal:

```bash
./mvnw -DskipTests -DskipITs -Djacoco.skip=true package
SPRING_PROFILES_ACTIVE=local java -jar services/order-service/target/order-service-*.jar
STRIPE_API_KEY=sk_test_localdemo STRIPE_API_BASE=http://localhost:12111 STRIPE_WEBHOOK_SECRET=whsec_local_demo \
  SPRING_PROFILES_ACTIVE=local java -jar services/payment-service/target/payment-service-*.jar
```

Use the same signing secret for the webhook script (`STRIPE_WEBHOOK_SECRET=whsec_local_demo ./scripts/send-test-webhook.sh ...`).
With real Stripe, export the test key, signing secret and API base from your local environment before starting payment-service.
Maven coordinates remain `0.1.0-SNAPSHOT`; v1.0.0 identifies the documented platform milestone, not a Maven artifact release or Git tag.

## Getting a token

```bash
TOKEN=$(./scripts/token.sh customer1)      # password grant on opp-web (dev only)
OPS_TOKEN=$(./scripts/ops-token.sh)        # client_credentials on opp-ops-cli

./scripts/token.sh --decode customer1      # print the decoded payload instead
curl -H "Authorization: Bearer $TOKEN" http://localhost:8081/api/v1/products
```

Access tokens live 15 minutes. Example payload for `customer1`:

```json
{
  "iss": "http://localhost:8180/realms/opp",
  "aud": ["payment-service", "order-service"],
  "sub": "00000000-0000-4000-8000-000000000001",
  "azp": "opp-web",
  "realm_access": { "roles": ["customer"] },
  "scope": "openid profile email",
  "preferred_username": "customer1"
}
```

## Calling order-service

```bash
export TOKEN=$(./scripts/token.sh customer1)
curl -s -H "Authorization: Bearer $TOKEN" localhost:8081/api/v1/products | jq
curl -s -X POST localhost:8081/api/v1/orders -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: $(uuidgen)" -H 'Content-Type: application/json' \
  -d '{"items":[{"sku":"MUG-JAVA","quantity":2}]}' | jq
```

Every state-changing call needs an `Idempotency-Key`; prices are never sent. Run the service with
`SPRING_PROFILES_ACTIVE=local` to get the OpenAPI document (http://localhost:8081/v3/api-docs) and Swagger UI
(http://localhost:8081/swagger-ui.html, "Authorize" takes the token from `token.sh`); in any other profile both are
switched off. The service validates tokens against `KEYCLOAK_ISSUER_URI` (default `http://localhost:8180/realms/opp`)
and fetches keys from `KEYCLOAK_JWK_SET_URI` (default: the issuer's `/protocol/openid-connect/certs`). It publishes
order events and consumes payment events through `KAFKA_BOOTSTRAP_SERVERS` (default `localhost:9092`); an order that is
not paid within `order.payment-timeout` (3 minutes in the `local` profile, 30 by default) is cancelled. Pass
`X-Correlation-Id: <uuid>` to follow one flow through the logs and events; the response echoes it.

## Paying without a browser (test support)

With the `local` or `stripe-test` profile, payment-service exposes `POST /api/v1/test-support/...` (switch:
`platform.test-support.enabled`, off everywhere else). After placing an order, wait a few seconds for the
PaymentIntent, look at the payment and then pay as the customer would, choosing what the card does:

```bash
TOKEN=$(./scripts/token.sh customer1)
ORDER=<id from POST /api/v1/orders>
curl -s -H "Authorization: Bearer $TOKEN" localhost:8082/api/v1/payments/by-order/$ORDER | jq   # status, clientSecret
curl -s -X POST -H "Authorization: Bearer $TOKEN" \
  "localhost:8082/api/v1/test-support/payments/by-order/$ORDER/confirm?scenario=success" | jq
```

`scenario` is `success`, `decline`, `insufficient_funds`, `requires_3ds`, `dispute` or `refund_fail`. The call
changes nothing in the database: with `stripe-test` the outcome arrives as a webhook (stripe-mock sends none; use the
signed test webhooks instead). The client secret in the first response is a credential: do not paste it into
tickets or logs.

## Kafka topics

Broker auto-creation is disabled (`auto.create.topics.enable=false`). `kafka-init` creates `order.events.v1` and
`payment.events.v1` with 3 partitions, replication factor 1 and 7-day retention (architecture §9.1), plus their
retry topics (`<topic>-retry-0 … -2`) and dead-letter topic (`<topic>-dlt`) with 14-day retention (§7.4). Operating
dead letters: [runbooks/dlq.md](runbooks/dlq.md). Browse them in kafka-ui at http://localhost:8085.

## Stripe modes

### `local` (default): stripe-mock

payment-service uses `STRIPE_API_BASE=http://localhost:12111` and any `sk_test_` key of letters and digits (stripe-mock rejects underscores after the prefix; the service refuses to start without a test-mode key: `STRIPE_API_KEY` is mandatory, live keys are rejected with an explanatory message). The containers of the `apps` profile have their own settings (`stripe-mock:12111`, defaults that need no `.env`). stripe-mock is stateless and
sends no webhooks; signed test webhooks come from `scripts/send-test-webhook.sh`, which signs the synthetic fixtures of
`services/payment-service/src/test/resources/stripe/events` with `STRIPE_WEBHOOK_SECRET` (the same value
payment-service verifies with):

```bash
./scripts/send-test-webhook.sh --list                               # available events
./scripts/send-test-webhook.sh payment_intent.succeeded "$ORDER"    # the order becomes PAID a moment later
```

Operating webhooks (dead events, replays, secret rotation, Stripe Dashboard deliveries):
[runbooks/webhooks.md](runbooks/webhooks.md).

### Switching to `stripe-test`: real Stripe test mode + Stripe CLI

With the services as containers, [demo.md](demo.md#real-stripe-in-test-mode) is the short way (`./scripts/up.sh --apps
--stripe-test`). With the services on the host:

1. In `.env` set your **test-mode** keys (`sk_test_…`/`rk_test_…` — live keys are refused by `up.sh` and by
   `LiveModeGuard`), and point the API base at Stripe:

   ```dotenv
   STRIPE_API_KEY=sk_test_...
   STRIPE_PUBLISHABLE_KEY=pk_test_...
   STRIPE_API_BASE=https://api.stripe.com
   ```

2. Get the webhook signing secret of the CLI forwarder (stable for the key/device) and put it in `.env`:

   ```bash
   docker compose -f infra/docker-compose.yml --env-file .env --profile stripe-test \
     run --rm stripe-cli listen --print-secret
   # STRIPE_WEBHOOK_SECRET=whsec_...
   ```

3. Start the forwarder and payment-service with the `stripe-test` Spring profile:

   ```bash
   ./scripts/up.sh --stripe-test
   docker compose -f infra/docker-compose.yml logs -f stripe-cli   # shows forwarded events
   ```

The CLI forwards to `http://host.docker.internal:8082/webhooks/stripe`. On Linux that name is provided by
`extra_hosts: host-gateway`; payment-service must listen on all interfaces (Spring Boot's default), not only on
`127.0.0.1`. Trigger events with
`docker compose -f infra/docker-compose.yml --env-file .env --profile stripe-test run --rm stripe-cli trigger payment_intent.succeeded`
or via the demo flow.

## Troubleshooting

- **A container stays `starting`/`unhealthy`:** `docker compose -f infra/docker-compose.yml logs <service>`.
- **Port already in use:** stop the local PostgreSQL/Kafka or change the host port in the compose file.
- **Kafka fails after changing `CLUSTER_ID` or the listener setup:** `./scripts/down.sh -v`.
- **A service fails with `Detected applied migration not resolved locally`:** the database volume comes from other code
  (a branch with newer migrations); `./scripts/down.sh -v` starts clean.
- **`invalid_grant` from `token.sh`:** wrong password (`OPP_USER_PASSWORD`) or Keycloak still importing the realm.
