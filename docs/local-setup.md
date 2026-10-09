# Local Setup

Local infrastructure for development and the manual demo (architecture §8.1, §12, §16).
Everything lives in [`infra/docker-compose.yml`](../infra/docker-compose.yml); the services themselves run on the host
(`./mvnw spring-boot:run`) until the `apps` compose profile lands (T18).

## Prerequisites

- Docker Engine 24+ with Compose v2 (`docker compose version`)
- `bash`, `curl`, `jq`
- JDK 21 for the services (see [README](../README.md))

## Start and stop

```bash
cp .env.example .env              # optional: every variable has a dev default
./scripts/up.sh                   # default profile; waits until every container is healthy
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
| `stripe-test` | stripe-cli | real Stripe test mode: forwards webhooks to payment-service on the host |
| `observability` | otel-collector, jaeger, prometheus, grafana | tracing and metrics (configs are stubs until T16) |
| `apps` | order-service, payment-service | T18 |

## Ports

| Component | Host port | Notes |
|---|---|---|
| order-service | 8081 | runs on the host |
| payment-service | 8082 | runs on the host; webhook endpoint `/webhooks/stripe` |
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

## Kafka topics

Broker auto-creation is disabled (`auto.create.topics.enable=false`). `kafka-init` creates `order.events.v1` and
`payment.events.v1` with 3 partitions, replication factor 1 and 7-day retention (architecture §9.1), plus their
retry topics (`<topic>-retry-0 … -2`) and dead-letter topic (`<topic>-dlt`) with 14-day retention (§7.4). Operating
dead letters: [runbooks/dlq.md](runbooks/dlq.md). Browse them in kafka-ui at http://localhost:8085.

## Stripe modes

### `local` (default): stripe-mock

payment-service uses `STRIPE_API_BASE=http://localhost:12111` and any `sk_test_` key (it refuses to start without a test-mode key: `STRIPE_API_KEY` is mandatory, live keys are rejected with an explanatory message). stripe-mock is stateless and
sends no webhooks; signed test webhooks come from `scripts/send-test-webhook.sh` (added in a later task) using
`STRIPE_WEBHOOK_SECRET`.

### Switching to `stripe-test`: real Stripe test mode + Stripe CLI

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
- **`invalid_grant` from `token.sh`:** wrong password (`OPP_USER_PASSWORD`) or Keycloak still importing the realm.
