#!/usr/bin/env bash
# Starts the local infrastructure and waits until every container is healthy.
#
#   ./scripts/up.sh                       # postgres, kafka, kafka-ui, keycloak, stripe-mock
#   ./scripts/up.sh --observability       # + otel-collector, jaeger, prometheus, grafana
#   ./scripts/up.sh --stripe-test         # + stripe-cli (needs STRIPE_API_KEY=sk_test_... in .env)
set -euo pipefail
# shellcheck source=lib/common.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"

usage() {
    echo "usage: $(basename "$0") [--observability] [--stripe-test]" >&2
    exit 2
}

profiles=()
while [[ $# -gt 0 ]]; do
    case "$1" in
        --observability) profiles+=(--profile observability) ;;
        --stripe-test) profiles+=(--profile stripe-test) ;;
        *) usage ;;
    esac
    shift
done

require docker
docker compose version > /dev/null 2>&1 || die "Docker Compose v2 is required"
load_env

if [[ " ${profiles[*]-} " == *" stripe-test "* ]]; then
    key="${STRIPE_API_KEY:-}"
    [[ "$key" == sk_test_* || "$key" == rk_test_* ]] \
        || die "stripe-test needs a TEST-MODE key: set STRIPE_API_KEY=sk_test_... (or rk_test_...) in .env"
fi

compose ${profiles[@]+"${profiles[@]}"} up -d --wait --wait-timeout 300
compose ${profiles[@]+"${profiles[@]}"} ps -a

cat << 'MSG'

Infrastructure is up:
    PostgreSQL   localhost:5432   orders_db (orders/orders), payments_db (payments/payments)
    Kafka        localhost:9092   kafka-ui http://localhost:8085
    Keycloak     http://localhost:8180   realm opp, admin console admin/admin
    stripe-mock  http://localhost:12111
Token: ./scripts/token.sh customer1   |   ./scripts/ops-token.sh
MSG
