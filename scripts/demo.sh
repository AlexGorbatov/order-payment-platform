#!/usr/bin/env bash
# Runs one demo scenario against the running platform and prints a timeline of what happens: when the order and the
# payment change status, what the customer does, what Stripe answers.
#
#   ./scripts/demo.sh success                # a customer orders and pays
#   ./scripts/demo.sh decline-then-success   # the first card is declined, the second one pays
#   ./scripts/demo.sh 3ds                    # the card asks for 3-D Secure
#   ./scripts/demo.sh timeout-late-payment   # nobody pays in time, the order is cancelled, then the money arrives anyway
#   ./scripts/demo.sh refund                 # a paid order is refunded by an admin
#   ./scripts/demo.sh dispute                # a paid order is disputed by the cardholder
#   ./scripts/demo.sh --list
#
# Two modes, detected from what is running (override with --mode local|stripe-test or DEMO_MODE):
#   local        stripe-mock answers the API calls but sends no webhooks, so the script plays Stripe's part and sends the
#                signed webhooks itself (scripts/send-test-webhook.sh). Start: ./scripts/up.sh --apps
#   stripe-test  real Stripe in test mode; the webhooks arrive through the Stripe CLI. Start:
#                ./scripts/up.sh --apps --stripe-test   (docs/demo.md)
#
# The platform may also run on the host (./mvnw spring-boot:run): it is found at ORDER_SERVICE_URL (default
# http://localhost:8081) and PAYMENT_SERVICE_URL (default http://localhost:8082).
#
# Needs: bash, curl, jq (and openssl in local mode). Exit status 0 only if the scenario ends in the expected state.
set -euo pipefail
# shellcheck source=lib/common.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ORDER_URL="${ORDER_SERVICE_URL:-http://localhost:8081}"
PAYMENT_URL="${PAYMENT_SERVICE_URL:-http://localhost:8082}"
export PAYMENT_SERVICE_URL="$PAYMENT_URL"
CUSTOMER="customer1"
ITEMS='{"items":[{"sku":"BOOK-CLEAN-CODE","quantity":1},{"sku":"MUG-JAVA","quantity":2}]}'

SCENARIOS="success decline-then-success 3ds timeout-late-payment refund dispute"

usage() {
    cat >&2 << 'MSG'
usage: demo.sh [--mode local|stripe-test] <scenario>   |   demo.sh --list

scenarios:
MSG
    list_scenarios >&2
    exit 2
}

list_scenarios() {
    cat << 'MSG'
  success                order -> PaymentIntent -> card accepted -> webhook -> order PAID
  decline-then-success   declined card keeps the order open; a second card on the same PaymentIntent pays
  3ds                    the card needs 3-D Secure: the order waits, then it is paid
  timeout-late-payment   unpaid order is cancelled after the payment window; a late payment is refunded automatically
  refund                 an admin refunds a paid order: REFUND_REQUESTED -> REFUNDED
  dispute                the cardholder disputes a paid order: it stays PAID and is flagged
MSG
}

# ------------------------------------------------------------------------------------------------ output

if [[ -t 1 ]]; then
    DIM=$'\033[2m'; BOLD=$'\033[1m'; GREEN=$'\033[32m'; RED=$'\033[31m'; CYAN=$'\033[36m'; YELLOW=$'\033[33m'; RESET=$'\033[0m'
else
    DIM=""; BOLD=""; GREEN=""; RED=""; CYAN=""; YELLOW=""; RESET=""
fi
START_SECONDS=$SECONDS

# event <who> <text>: one line of the timeline: wall-clock time, seconds since the start, who, what.
event() {
    local who="$1" color="$RESET"
    shift
    case "$who" in
        customer) color="$CYAN" ;;
        stripe) color="$YELLOW" ;;
        order | payment) color="$BOLD" ;;
    esac
    printf '%s%s +%3ss%s  %s%-9s%s %s\n' "$DIM" "$(date +%H:%M:%S)" "$((SECONDS - START_SECONDS))" "$RESET" \
        "$color" "$who" "$RESET" "$*"
}

step() {
    printf '\n%s== %s%s\n' "$BOLD" "$*" "$RESET"
}

fail() {
    event script "${RED}FAILED${RESET}: $*"
    print_history
    exit 1
}

# ------------------------------------------------------------------------------------------------ HTTP

TMP_BODY="$(mktemp)"
trap 'rm -f "$TMP_BODY"' EXIT
BODY=""
HTTP_STATUS=""

# http <method> <url> <token> [json body] [idempotency key]: sets BODY and HTTP_STATUS
http() {
    local method="$1" url="$2" token="$3" data="${4:-}" idem="${5:-}"
    local args=(-sS -o "$TMP_BODY" -w '%{http_code}' -X "$method" -H "Authorization: Bearer $token")
    [[ -n "$idem" ]] && args+=(-H "Idempotency-Key: $idem")
    [[ -n "$data" ]] && args+=(-H 'Content-Type: application/json' -d "$data")
    HTTP_STATUS="$(curl "${args[@]}" "$url")" || die "cannot reach $url (is the platform up? ./scripts/up.sh --apps)"
    BODY="$(cat "$TMP_BODY")"
}

new_uuid() {
    if command -v uuidgen > /dev/null 2>&1; then uuidgen | tr '[:upper:]' '[:lower:]'; else cat /proc/sys/kernel/random/uuid; fi
}

# ------------------------------------------------------------------------------------------------ mode

detect_mode() {
    if [[ -n "${DEMO_MODE:-}" ]]; then
        echo "$DEMO_MODE"
    elif command -v docker > /dev/null 2>&1 \
        && [[ -n "$(compose --profile stripe-test ps --status running -q stripe-cli 2> /dev/null || true)" ]]; then
        echo "stripe-test"
    else
        echo "local"
    fi
}

# ------------------------------------------------------------------------------------------------ observing

ORDER_ID=""
CUSTOMER_TOKEN=""
ADMIN_TOKEN=""
ORDER_STATUS=""
ORDER_REASON=""
ORDER_DISPUTED="false"
PAYMENT_STATUS=""
PAYMENT_ERROR=""
PAYMENT_INTENT=""
SEEN_ORDER=""
SEEN_PAYMENT=""
SEEN_ERROR=""
SEEN_DISPUTED="false"
SEEN_INTENT=""

# Reads the order and its payment (as the admin: no client secret is fetched from Stripe for that) and prints what changed.
observe() {
    http GET "$ORDER_URL/api/v1/orders/$ORDER_ID" "$ADMIN_TOKEN"
    if [[ "$HTTP_STATUS" == 200 ]]; then
        ORDER_STATUS="$(jq -r '.status' <<< "$BODY")"
        ORDER_REASON="$(jq -r '.history[-1].reason // empty' <<< "$BODY")"
        ORDER_DISPUTED="$(jq -r '.disputed' <<< "$BODY")"
    fi
    http GET "$PAYMENT_URL/api/v1/payments/by-order/$ORDER_ID" "$ADMIN_TOKEN"
    if [[ "$HTTP_STATUS" == 200 ]]; then
        PAYMENT_STATUS="$(jq -r '.status' <<< "$BODY")"
        PAYMENT_ERROR="$(jq -r '.lastErrorCode // empty' <<< "$BODY")"
        PAYMENT_INTENT="$(jq -r '.stripePaymentIntentId // empty' <<< "$BODY")"
    fi

    if [[ "$ORDER_STATUS" != "$SEEN_ORDER" ]]; then
        local detail=""
        [[ "$ORDER_STATUS" == CANCELLED || "$ORDER_STATUS" == REFUND_REQUESTED ]] && [[ -n "$ORDER_REASON" ]] \
            && detail=" ($ORDER_REASON)"
        event order "${SEEN_ORDER:+$SEEN_ORDER -> }$ORDER_STATUS$detail"
        SEEN_ORDER="$ORDER_STATUS"
    fi
    if [[ "$ORDER_DISPUTED" == true && "$SEEN_DISPUTED" != true ]]; then
        event order "flagged as disputed"
        SEEN_DISPUTED=true
    fi
    if [[ -n "$PAYMENT_INTENT" && "$PAYMENT_INTENT" != "$SEEN_INTENT" ]]; then
        event payment "PaymentIntent $PAYMENT_INTENT created at Stripe"
        SEEN_INTENT="$PAYMENT_INTENT"
    fi
    if [[ -n "$PAYMENT_STATUS" && "$PAYMENT_STATUS" != "$SEEN_PAYMENT" ]]; then
        event payment "${SEEN_PAYMENT:+$SEEN_PAYMENT -> }$PAYMENT_STATUS"
        SEEN_PAYMENT="$PAYMENT_STATUS"
    fi
    if [[ -n "$PAYMENT_ERROR" && "$PAYMENT_ERROR" != "$SEEN_ERROR" ]]; then
        event payment "last attempt failed: $PAYMENT_ERROR"
        SEEN_ERROR="$PAYMENT_ERROR"
    fi
}

order_is() { [[ "$ORDER_STATUS" == "$1" ]]; }
payment_is() { [[ "$PAYMENT_STATUS" == "$1" ]]; }
payment_failed_with() { [[ "$PAYMENT_ERROR" == "$1" ]]; }
order_is_disputed() { [[ "$ORDER_DISPUTED" == true ]]; }

# wait_for <seconds> <what> <condition command...>: observes every half second until the condition holds.
wait_for() {
    local timeout="$1" what="$2" started=$SECONDS
    shift 2
    while true; do
        observe
        if "$@"; then
            return 0
        fi
        if ((SECONDS - started >= timeout)); then
            fail "gave up after ${timeout}s waiting for: $what"
        fi
        sleep 0.5
    done
}

# Lets the script's own state catch up with an action before it looks again.
settle() {
    sleep "$1"
    observe
}

# ------------------------------------------------------------------------------------------------ actions

place_order() {
    step "The customer places an order"
    local key
    key="$(new_uuid)"
    http POST "$ORDER_URL/api/v1/orders" "$CUSTOMER_TOKEN" "$ITEMS" "$key"
    [[ "$HTTP_STATUS" == 201 ]] || fail "POST /api/v1/orders answered $HTTP_STATUS: $BODY"
    ORDER_ID="$(jq -r '.id' <<< "$BODY")"
    event customer "POST /api/v1/orders (Idempotency-Key $key) -> 201, order $ORDER_ID, total $(jq -r '"\(.total.amountMinor / 100) \(.total.currency)"' <<< "$BODY")"
    observe
}

# Waits for PaymentInitiated: payment-service has a PaymentIntent and Stripe waits for a card.
await_payment_intent() {
    step "The platform creates the PaymentIntent (OrderCreated -> payment-service -> Stripe -> PaymentInitiated)"
    wait_for 60 "a PaymentIntent for the order" payment_is REQUIRES_PAYMENT_METHOD
}

# pay <scenario> <card description>: the customer's browser would confirm the PaymentIntent with Stripe.js; the
# test-support endpoint does it with a Stripe test payment method.
pay() {
    local scenario="$1" card="$2"
    event customer "pays with $card (POST /api/v1/test-support/.../confirm?scenario=$scenario)"
    http POST "$PAYMENT_URL/api/v1/test-support/payments/by-order/$ORDER_ID/confirm?scenario=$scenario" "$CUSTOMER_TOKEN"
    if [[ "$HTTP_STATUS" != 202 ]]; then
        if [[ "$HTTP_STATUS" == 409 ]]; then
            fail "Stripe's PaymentIntent is no longer payable ($(jq -r '.detail // .' <<< "$BODY")). In timeout-late-payment the cancellation can win the race; run the scenario again."
        fi
        fail "confirm answered $HTTP_STATUS: $BODY"
    fi
    if [[ "$(jq -r '.accepted' <<< "$BODY")" == true ]]; then
        if [[ "$MODE" == local ]]; then
            # stripe-mock keeps no state: whatever it says about the PaymentIntent, the outcome is the webhook's.
            event stripe "stripe-mock took the confirmation; it keeps no state, so the outcome follows as a webhook"
        else
            event stripe "took the confirmation, PaymentIntent is $(jq -r '.stripeStatus' <<< "$BODY"); the outcome follows as a webhook"
        fi
    else
        event stripe "refused the card: $(jq -r '"\(.errorCode) / \(.declineCode)"' <<< "$BODY")"
    fi
}

# stripe_sends <fixture> <what happened>: in local mode the script plays Stripe and sends the signed webhook; with the
# real Stripe it only says that one is on its way (the Stripe CLI forwards it).
stripe_sends() {
    local fixture="$1" what="$2"
    if [[ "$MODE" == local ]]; then
        local out
        out="$("$SCRIPT_DIR/send-test-webhook.sh" "$fixture" "$ORDER_ID" 2>&1)" \
            || fail "could not send the $fixture webhook: $out"
        event stripe "webhook $fixture ($what), simulated and signed by send-test-webhook.sh -> HTTP 200"
    else
        event stripe "webhook $fixture ($what) is on its way through the Stripe CLI"
    fi
}

admin_refund() {
    event admin "POST /api/v1/orders/$ORDER_ID/refund"
    http POST "$ORDER_URL/api/v1/orders/$ORDER_ID/refund" "$ADMIN_TOKEN" "" "$(new_uuid)"
    [[ "$HTTP_STATUS" == 202 ]] || fail "refund answered $HTTP_STATUS: $BODY"
    event admin "-> 202, the money moves asynchronously"
}

print_history() {
    [[ -n "$ORDER_ID" ]] || return 0
    http GET "$ORDER_URL/api/v1/orders/$ORDER_ID" "$ADMIN_TOKEN" 2> /dev/null || return 0
    [[ "$HTTP_STATUS" == 200 ]] || return 0
    printf '\n%sStatus history of the order (order-service, UTC)%s\n' "$BOLD" "$RESET"
    jq -r '.history[] | "  \(.occurredAt[11:23])  \((.from // "-") + " -> " + .to)\(if .reason then "  [" + .reason + "]" else "" end)"' <<< "$BODY"
    printf '\n  order %s  payment %s%s\n' "$ORDER_ID" "${PAYMENT_STATUS:--}" "${PAYMENT_INTENT:+  ($PAYMENT_INTENT)}"
}

finish() {
    local expectation="$1"
    print_history
    printf '\n%s%s%s\n' "$GREEN" "OK: $expectation" "$RESET"
    printf '%sKafka events: http://localhost:8085  |  order JSON: GET %s/api/v1/orders/%s%s\n' \
        "$DIM" "$ORDER_URL" "$ORDER_ID" "$RESET"
}

# ------------------------------------------------------------------------------------------------ scenarios

scenario_success() {
    place_order
    await_payment_intent
    step "The customer pays"
    pay success "a card that works (pm_card_visa)"
    stripe_sends payment_intent.succeeded "payment succeeded"
    wait_for 60 "order PAID" order_is PAID
    finish "the order is PAID and its payment is $PAYMENT_STATUS"
}

scenario_decline_then_success() {
    place_order
    await_payment_intent
    step "The first card is declined"
    pay decline "a card that is declined (pm_card_chargeDeclined)"
    stripe_sends payment_intent.payment_failed "payment attempt failed"
    wait_for 60 "the failed attempt to be recorded" payment_failed_with card_declined
    settle 1
    order_is PENDING_PAYMENT || fail "the order must stay open after a declined card, it is $ORDER_STATUS"
    event script "order stays PENDING_PAYMENT: the customer may try again on the same PaymentIntent"
    step "The second card works"
    pay success "another card (pm_card_visa)"
    stripe_sends payment_intent.succeeded "payment succeeded"
    wait_for 60 "order PAID" order_is PAID
    finish "declined once, paid with the second card: order PAID"
}

scenario_3ds() {
    place_order
    await_payment_intent
    step "The card asks for 3-D Secure"
    pay requires_3ds "a card that requires authentication (pm_card_authenticationRequired)"
    stripe_sends payment_intent.requires_action "the customer must authenticate"
    wait_for 60 "payment REQUIRES_ACTION" payment_is REQUIRES_ACTION
    order_is PENDING_PAYMENT || fail "the order must wait while the customer authenticates, it is $ORDER_STATUS"
    step "The customer completes the authentication"
    if [[ "$MODE" == local ]]; then
        stripe_sends payment_intent.succeeded "authentication passed, payment succeeded"
    else
        event script "the challenge needs a browser (Stripe.js); here the customer pays with another card instead"
        pay success "another card (pm_card_visa)"
    fi
    wait_for 60 "order PAID" order_is PAID
    finish "the order waited for 3-D Secure and is PAID"
}

scenario_timeout_late_payment() {
    place_order
    await_payment_intent
    step "Nobody pays: the payment window of the demo is 1 minute"
    wait_for 150 "the order to be cancelled by the payment timeout" order_is CANCELLED
    [[ "$ORDER_REASON" == TIMEOUT ]] || event script "cancelled with reason $ORDER_REASON"
    step "The money arrives anyway (the cancellation of the PaymentIntent has not reached Stripe yet)"
    if [[ "$MODE" == local ]]; then
        stripe_sends payment_intent.succeeded "late payment"
    else
        pay success "a card that works (pm_card_visa)"
    fi
    wait_for 60 "the automatic refund to be requested" order_is REFUND_REQUESTED
    step "order-service asked for a refund (LATE_PAYMENT_AFTER_CANCEL); payment-service sends it to Stripe"
    if [[ "$MODE" == local ]]; then
        settle 4
        stripe_sends charge.refunded "the refund went through"
    fi
    wait_for 60 "order REFUNDED" order_is REFUNDED
    finish "the customer was not charged for a cancelled order: order REFUNDED"
}

scenario_refund() {
    place_order
    await_payment_intent
    step "The customer pays"
    pay success "a card that works (pm_card_visa)"
    stripe_sends payment_intent.succeeded "payment succeeded"
    wait_for 60 "order PAID" order_is PAID
    step "An admin refunds the order"
    admin_refund
    wait_for 30 "order REFUND_REQUESTED" order_is REFUND_REQUESTED
    if [[ "$MODE" == local ]]; then
        settle 4
        stripe_sends charge.refunded "the refund went through"
    fi
    wait_for 60 "order REFUNDED" order_is REFUNDED
    finish "order REFUNDED, payment $PAYMENT_STATUS"
}

scenario_dispute() {
    place_order
    await_payment_intent
    step "The customer pays with a card whose owner will dispute the charge"
    pay dispute "a card that leads to a dispute (pm_card_createDispute)"
    stripe_sends payment_intent.succeeded "payment succeeded"
    wait_for 60 "order PAID" order_is PAID
    stripe_sends charge.dispute.created "the cardholder disputes the charge"
    wait_for 120 "the order to be flagged as disputed" order_is_disputed
    finish "order PAID and flagged as disputed (a dispute changes no status)"
}

# ------------------------------------------------------------------------------------------------ main

MODE_OVERRIDE=""
scenario=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        --list) list_scenarios; exit 0 ;;
        --mode) [[ $# -ge 2 ]] || usage; MODE_OVERRIDE="$2"; shift ;;
        -h | --help) usage ;;
        -*) usage ;;
        *) [[ -z "$scenario" ]] || usage; scenario="$1" ;;
    esac
    shift
done
[[ -n "$scenario" ]] || usage
case " $SCENARIOS " in
    *" $scenario "*) ;;
    *) echo "unknown scenario '$scenario'" >&2; usage ;;
esac

require curl jq
load_env
export STRIPE_WEBHOOK_SECRET="${STRIPE_WEBHOOK_SECRET:-whsec_local_demo}"
[[ -z "$MODE_OVERRIDE" ]] || DEMO_MODE="$MODE_OVERRIDE"
MODE="$(detect_mode)"
[[ "$MODE" == local || "$MODE" == stripe-test ]] || die "--mode must be local or stripe-test"
[[ "$MODE" == stripe-test ]] || require openssl

printf '%sScenario %s%s%s, mode %s%s%s' "$BOLD" "$CYAN" "$scenario" "$RESET$BOLD" "$CYAN" "$MODE" "$RESET"
if [[ "$MODE" == local ]]; then
    printf ' (stripe-mock; the script sends the webhooks Stripe would send)\n'
else
    printf ' (real Stripe test mode; webhooks come through the Stripe CLI)\n'
fi

CUSTOMER_TOKEN="$("$SCRIPT_DIR/token.sh" "$CUSTOMER")"
ADMIN_TOKEN="$("$SCRIPT_DIR/token.sh" admin1)"

case "$scenario" in
    success) scenario_success ;;
    decline-then-success) scenario_decline_then_success ;;
    3ds) scenario_3ds ;;
    timeout-late-payment) scenario_timeout_late_payment ;;
    refund) scenario_refund ;;
    dispute) scenario_dispute ;;
esac
