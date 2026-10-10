#!/usr/bin/env bash
# Starts the local infrastructure (and optionally the services) and waits until every container is healthy.
#
#   ./scripts/up.sh                       # postgres, kafka, kafka-ui, keycloak, stripe-mock
#   ./scripts/up.sh --apps                # + order-service, payment-service and the web interface as containers, against stripe-mock
#   ./scripts/up.sh --apps --stripe-test  # ... against the REAL Stripe API (test mode) + stripe-cli forwarding webhooks
#   ./scripts/up.sh --stripe-test         # + stripe-cli only, for services running on the host
#   ./scripts/up.sh --observability       # + otel-collector, jaeger, prometheus, grafana
#
# --stripe-test needs a TEST-MODE key in .env (STRIPE_API_KEY=sk_test_...). The webhook signing secret of the Stripe CLI
# is fetched from the CLI itself unless .env has a real one (docs/demo.md).
set -euo pipefail
# shellcheck source=lib/common.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"

usage() {
    echo "usage: $(basename "$0") [--apps] [--stripe-test] [--observability]" >&2
    exit 2
}

apps=false
stripe_test=false
profiles=()
while [[ $# -gt 0 ]]; do
    case "$1" in
        --apps) apps=true; profiles+=(--profile apps) ;;
        --observability) profiles+=(--profile observability) ;;
        --stripe-test) stripe_test=true; profiles+=(--profile stripe-test) ;;
        *) usage ;;
    esac
    shift
done

require docker
docker compose version > /dev/null 2>&1 || die "Docker Compose v2 is required"
load_env

if $stripe_test; then
    key="${STRIPE_API_KEY:-}"
    [[ "$key" == sk_test_* || "$key" == rk_test_* ]] \
        || die "stripe-test needs a TEST-MODE key: set STRIPE_API_KEY=sk_test_... (or rk_test_...) in .env"
    [[ "$key" != *replace_me* && "$key" != sk_test_localdemo ]] \
        || die "STRIPE_API_KEY in .env is still a placeholder: put your own test key there (dashboard.stripe.com/test/apikeys)"

    # The secret belongs to the Stripe CLI (stable for one key and device). .env may hold it; if not, ask the CLI.
    secret="${STRIPE_WEBHOOK_SECRET:-}"
    if [[ "$secret" != whsec_* || "$secret" == *replace_me* || "$secret" == whsec_local_demo ]]; then
        echo "Asking the Stripe CLI for its webhook signing secret ..."
        secret="$(compose --profile stripe-test run --rm --no-deps -T stripe-cli listen --print-secret 2> /dev/null \
            | tr -d '\r' | grep -o 'whsec_[A-Za-z0-9]*' | head -n 1 || true)"
        [[ -n "$secret" ]] || die "could not get the webhook secret from the Stripe CLI: is STRIPE_API_KEY valid? (docs/demo.md)"
        export STRIPE_WEBHOOK_SECRET="$secret"
        echo "  got it (used for this start only; put it in .env as STRIPE_WEBHOOK_SECRET to keep it)"
    fi

    # With the services in containers, payment-service must also switch to the real Stripe API.
    if $apps; then
        export COMPOSE_OVERLAYS="docker-compose.stripe-test.yml"
    fi
fi

build=()
if $apps; then
    build=(--build)
fi

compose ${profiles[@]+"${profiles[@]}"} up -d --remove-orphans ${build[@]+"${build[@]}"} --wait --wait-timeout 600
compose ${profiles[@]+"${profiles[@]}"} ps -a

cat << 'MSG'

Infrastructure is up:
    PostgreSQL   localhost:5432   orders_db (orders/orders), payments_db (payments/payments)
    Kafka        localhost:9092   kafka-ui http://localhost:8085
    Keycloak     http://localhost:8180   realm opp, admin console admin/admin
    stripe-mock  http://localhost:12111
Token: ./scripts/token.sh customer1   |   ./scripts/ops-token.sh
MSG

if $apps; then
    cat << 'MSG'
Services:
    order-service    http://localhost:8081   Swagger UI /swagger-ui.html
    payment-service  http://localhost:8082   webhooks POST /webhooks/stripe
    web interface    http://localhost:8090   customer1, admin1, ops1 (password: password)
Try it: open the web interface, or ./scripts/demo.sh success
MSG
fi
