#!/usr/bin/env bash
# DEV ONLY (profile `local`). Sends a signed, synthetic Stripe webhook for an order to payment-service, so the whole
# flow works without a Stripe account: stripe-mock creates PaymentIntents but sends no webhooks.
#
#   ./scripts/send-test-webhook.sh payment_intent.succeeded "$ORDER"
#   ./scripts/send-test-webhook.sh payment_intent.payment_failed "$ORDER"
#   ./scripts/send-test-webhook.sh --list
#
# It looks up the order's payment (GET /api/v1/payments/by-order/{orderId} with the owner's token), renders the fixture
# services/payment-service/src/test/resources/stripe/events/<fixture>.json with the payment's PaymentIntent id, payment
# id and amount, signs it like Stripe (t=<now>,v1=HMAC-SHA256(secret, t + "." + body)) with the first secret of
# STRIPE_WEBHOOK_SECRET, and POSTs it to /webhooks/stripe. The service applies it asynchronously (WebhookProcessor).
#
# Options:  --user <name>   owner of the order for the lookup token (default: customer1)
#           --dry-run       print the rendered event and the request instead of sending it
# Env:      STRIPE_WEBHOOK_SECRET (from .env), PAYMENT_SERVICE_URL (default: http://localhost:8082),
#           STRIPE_REFUND_ID / REFUND_ID (refund.failed only), OPP_USER_PASSWORD (see token.sh).
set -euo pipefail
# shellcheck source=lib/common.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"

FIXTURES="$REPO_ROOT/services/payment-service/src/test/resources/stripe/events"

usage() {
    echo "usage: $(basename "$0") [--user <name>] [--dry-run] <fixture> <orderId>   |   $(basename "$0") --list" >&2
    exit 2
}

list_fixtures() {
    for file in "$FIXTURES"/*.json; do basename "$file" .json; done
}

user="customer1"
dry_run=false
args=()
while [[ $# -gt 0 ]]; do
    case "$1" in
        --user) [[ $# -ge 2 ]] || usage; user="$2"; shift ;;
        --dry-run) dry_run=true ;;
        --list) list_fixtures; exit 0 ;;
        -h | --help) usage ;;
        -*) usage ;;
        *) args+=("$1") ;;
    esac
    shift
done
[[ ${#args[@]} -eq 2 ]] || usage
fixture="${args[0]%.json}"
order_id="${args[1]}"

require curl jq openssl
load_env

template="$FIXTURES/$fixture.json"
[[ -f "$template" ]] || die "no fixture '$fixture'; available: $(list_fixtures | tr '\n' ' ')"
[[ "$order_id" =~ ^[0-9a-fA-F-]{36}$ ]] || die "'$order_id' is not an order id (UUID)"

secret="${STRIPE_WEBHOOK_SECRET:-}"
secret="${secret%%,*}"
[[ "$secret" == whsec_* ]] || die "STRIPE_WEBHOOK_SECRET is not set (or not a whsec_ value) in .env: the service and this script must share it"

base_url="${PAYMENT_SERVICE_URL:-http://localhost:8082}"
token="$("$(dirname "${BASH_SOURCE[0]}")/token.sh" "$user")"
payment="$(curl -sS -f -H "Authorization: Bearer $token" "$base_url/api/v1/payments/by-order/$order_id")" \
    || die "no payment for order $order_id visible to $user (is payment-service running, has the PaymentIntent been created yet?)"

payment_intent="$(jq -r '.stripePaymentIntentId // empty' <<< "$payment")"
payment_id="$(jq -r '.paymentId' <<< "$payment")"
amount="$(jq -r '.amount.amountMinor' <<< "$payment")"
currency="$(jq -r '.amount.currency | ascii_downcase' <<< "$payment")"
[[ -n "$payment_intent" ]] || die "payment $payment_id has no PaymentIntent yet (status $(jq -r .status <<< "$payment")); try again in a moment"

suffix="$(openssl rand -hex 7)"
created="$(date +%s)"
body="$(sed \
    -e "s/{{EVENT_ID}}/evt_local_$suffix/g" \
    -e "s/{{CREATED}}/$created/g" \
    -e "s/{{LIVEMODE}}/false/g" \
    -e "s/{{PAYMENT_INTENT_ID}}/$payment_intent/g" \
    -e "s/{{PAYMENT_ID}}/$payment_id/g" \
    -e "s/{{ORDER_ID}}/$order_id/g" \
    -e "s/{{AMOUNT}}/$amount/g" \
    -e "s/{{CURRENCY}}/$currency/g" \
    -e "s/{{SUFFIX}}/$suffix/g" \
    -e "s/{{STRIPE_REFUND_ID}}/${STRIPE_REFUND_ID:-re_local_$suffix}/g" \
    -e "s/{{REFUND_ID}}/${REFUND_ID:-00000000-0000-0000-0000-000000000000}/g" \
    "$template")"
if grep -q '{{' <<< "$body"; then
    die "fixture $fixture has placeholders this script does not fill: $(grep -o '{{[A-Z_]*}}' <<< "$body" | sort -u | tr '\n' ' ')"
fi

timestamp="$(date +%s)"
signature="$(printf '%s' "$timestamp.$body" | openssl dgst -sha256 -hmac "$secret" | sed 's/^.*= //')"

if $dry_run; then
    printf '%s\n' "$body"
    echo "POST $base_url/webhooks/stripe  (Stripe-Signature: t=$timestamp,v1=<hmac>)" >&2
    exit 0
fi

status="$(printf '%s' "$body" | curl -sS -o /dev/stderr -w '%{http_code}' -X POST "$base_url/webhooks/stripe" \
    -H 'Content-Type: application/json; charset=utf-8' \
    -H "Stripe-Signature: t=$timestamp,v1=$signature" \
    --data-binary @-)"
echo >&2
[[ "$status" == 200 ]] || die "payment-service answered $status (wrong STRIPE_WEBHOOK_SECRET? see docs/runbooks/webhooks.md)"
echo "sent $fixture (evt_local_$suffix) for order $order_id, PaymentIntent $payment_intent: HTTP $status"
