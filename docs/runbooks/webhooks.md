# Runbook: Stripe webhooks

payment-service learns what happened to a payment from Stripe webhooks (architecture §6.6, §8.3,
[ADR-0009](../adr/0009-webhook-ingestion.md)). `POST /webhooks/stripe` verifies the signature, stores the event in
`stripe_webhook_event` and answers 200; the **WebhookProcessor** applies it later. An event the processor cannot apply
is retried with backoff (`FAILED`) and, after `payment.webhook-processor.retry-max-attempts` (default 8), becomes
**`DEAD`** and waits for an operator.

| | |
|---|---|
| Alerts | `webhook.dead` increases (page) · `webhook.livemode.rejected` > 0 (page) · `webhook.signature.failures` rate > 0 for 10 min · `webhook.processing.lag` p95 > 1 min · Stripe Dashboard reports failed deliveries |
| Who | an operator with database access to `payments_db` and access to the Stripe Dashboard (test mode) |
| Statuses | `RECEIVED` → `PROCESSED` \| `IGNORED`; on error `FAILED` → … → `DEAD` |
| Logs | every line of one event carries `stripeEventId`, and `paymentId` / `orderId` / `correlationId` once the payment is known; payloads and signatures are never logged |

Locally, open `payments_db` with:

```bash
docker compose -f infra/docker-compose.yml exec postgres psql -U payments -d payments_db
```

## 1. Find

```sql
-- what needs a human
SELECT event_id, type, attempts, left(last_error, 150) AS last_error, received_at, processed_at
FROM stripe_webhook_event WHERE status = 'DEAD' ORDER BY received_at;

-- what is still being retried, and when it is due next
SELECT event_id, type, attempts, next_attempt_at, left(last_error, 150) AS last_error
FROM stripe_webhook_event WHERE status = 'FAILED' ORDER BY next_attempt_at;

-- the backlog (lag): received but not processed yet
SELECT count(*), min(received_at) FROM stripe_webhook_event WHERE status IN ('RECEIVED', 'FAILED');
```

## 2. Understand

```sql
SELECT event_id, type, stripe_created_at, last_error,
       payload -> 'data' -> 'object' ->> 'id'             AS object_id,
       payload -> 'data' -> 'object' ->> 'status'         AS object_status,
       payload -> 'data' -> 'object' -> 'metadata'        AS metadata
FROM stripe_webhook_event WHERE event_id = 'evt_...';

-- the payment it is about, and its recent history
SELECT id, order_id, status, last_stripe_event_at, last_error_code, last_decline_code, disputed
FROM payment WHERE stripe_payment_intent_id = 'pi_...';
SELECT from_status, to_status, source, stripe_event_id, occurred_at
FROM payment_status_history WHERE payment_id = '<uuid>' ORDER BY id DESC LIMIT 10;
```

Typical causes of `last_error`:

| `last_error` | Meaning | Action |
|---|---|---|
| `... has no PaymentIntent yet ...` | the event arrived before the initiation worker committed the PaymentIntent | normally resolves on retry; if `DEAD`, check the initiation worker, then replay |
| `... not recorded as created at Stripe yet ...` | a refund event arrived before the refund worker recorded the Stripe refund id | same as above, for the refund worker |
| `StripeConfigurationException: ... requires_capture` | the account or a PaymentIntent uses manual capture, which the platform does not | fix the Stripe configuration; then resolve (do not replay) |
| `PaymentConcurrentlyModifiedException` | lost an optimistic-lock race (e.g. with reconciliation, F21) | resolves on retry |
| database / outbox errors | infrastructure | fix it, then replay |

Not an error, and not here: a report older than what is applied ends `PROCESSED` and counts in `webhook.stale.ignored`
(F10); an object the platform does not know ends `IGNORED` with a WARN line.

## 3. Replay a DEAD event

Fix the cause first. Then put the event back into the queue; the processor picks it up within a second and applies it
under the normal ordering rule, so replaying an event that has meanwhile become stale is harmless.

```sql
UPDATE stripe_webhook_event
SET status = 'FAILED', attempts = 0, next_attempt_at = now(), last_error = NULL, processed_at = NULL
WHERE event_id = 'evt_...' AND status = 'DEAD';
```

All dead events of a time window (after a fixed outage):

```sql
UPDATE stripe_webhook_event
SET status = 'FAILED', attempts = 0, next_attempt_at = now(), last_error = NULL, processed_at = NULL
WHERE status = 'DEAD' AND received_at BETWEEN '<from>' AND '<to>';
```

Check the result with the queries of §1 (status `PROCESSED`) and the payment row of §2.

Do **not** use "Resend" in the Stripe Dashboard for a stored event: the event id is already in `stripe_webhook_event`, so
the endpoint acknowledges the resend as a duplicate (F09) and does not process it again.

An event that must not be applied (for example a refund that failed *after* it had succeeded, logged as ERROR and left
`IGNORED`) needs a decision, not a replay: compare with the Stripe Dashboard and record the outcome in the incident.

## 4. Events Stripe could not deliver

Symptoms: Stripe shows failed deliveries, or payments stay in a non-terminal status. In the Stripe Dashboard (test mode)
open **Workbench → Webhooks → <endpoint> → Event deliveries**: every event with its delivery status, HTTP code and the
next automatic retry. In test mode Stripe retries a failed delivery three times over a few hours (live mode: up to
three days).

- **400** — signature or live-mode refusal; see §5 and §7. Fix, then resend.
- **413** — a body over 256 KB; not expected for the handled types, investigate before raising the limit.
- **5xx / timeout / connection refused** — payment-service was down or unreachable.

Resend an event that never arrived (it is not in `stripe_webhook_event`) from the event's page, **Resend** (up to 15
days), or with the Stripe CLI (up to 30 days):

```bash
stripe events resend evt_... --webhook-endpoint=we_...
```

The reconciliation job (architecture §8.4) also catches lost webhooks: every 5 minutes it asks Stripe about payments
that have not moved for 10 minutes and applies what Stripe says (F11; `reconciliation.drift{from,to}` counts every
correction). To run it at once: `POST /admin/reconciliation/run` with an `ops` token; `GET /admin/reconciliation/last`
shows what the latest run found. Resending is still useful for events that change more than a status (disputes, refund
outcomes).

Locally with the `stripe-test` profile the Stripe CLI forwards events; its terminal (`docker compose ... logs -f
stripe-cli`) shows every delivery and the status payment-service answered.

## 5. Signature failures (`webhook.signature.failures{reason}`)

| `reason` | Usual cause |
|---|---|
| `invalid_signature` | wrong `STRIPE_WEBHOOK_SECRET` (an endpoint's secret, not the API key; the CLI and every Dashboard endpoint have their own); clock skew over 5 minutes (check NTP); a proxy that changes the body; a replay attempt |
| `missing_signature` | something other than Stripe calls the endpoint |
| `not_configured` | `STRIPE_WEBHOOK_SECRET` is empty: every webhook is refused |
| `malformed_payload` | correctly signed but not an event — should not happen with Stripe |

A refused request stores nothing (F12); once fixed, resend the affected events (§4).

## 6. Rotate the signing secret

The endpoint accepts every secret listed in `STRIPE_WEBHOOK_SECRET` (comma-separated), so a rotation has no gap:

1. Stripe Dashboard → **Workbench → Webhooks → <endpoint> → ⋯ → Roll secret**. Choose an expiry for the old secret
   (up to 24 hours) long enough for a deployment. Until it expires Stripe signs every delivery with **both** secrets.
2. Set `STRIPE_WEBHOOK_SECRET=<new>,<old>` (order does not matter) in the environment of payment-service and redeploy or
   restart it.
3. Check that deliveries are still answered 200 (§4) and that `webhook.signature.failures` stays flat.
4. After the old secret has expired, set `STRIPE_WEBHOOK_SECRET=<new>` and redeploy.

If the old secret leaked, expire it immediately in step 1 and accept the few failed deliveries until step 2 is live;
resend them afterwards (§4). Never put a secret into a ticket, a log or the repository; `.env` is gitignored.

Locally, the Stripe CLI's secret is stable per device: `docker compose -f infra/docker-compose.yml --env-file .env
--profile stripe-test run --rm stripe-cli listen --print-secret`.

## 7. A live-mode event (`webhook.livemode.rejected`)

This platform is test mode only (§8.1). A live-mode event is refused with 400 and logged as ERROR with its event id and
type. It means a **live** Stripe endpoint points at this system. Treat it as an incident: find the endpoint in the live
Dashboard and remove or disable it. Nothing was stored or applied.

## 8. Try it locally without a Stripe account

With the `local` profile (stripe-mock creates PaymentIntents but sends no webhooks), send signed synthetic events:

```bash
./scripts/send-test-webhook.sh --list
./scripts/send-test-webhook.sh payment_intent.succeeded "$ORDER"
./scripts/send-test-webhook.sh payment_intent.payment_failed "$ORDER"
```

The script signs with the first secret of `STRIPE_WEBHOOK_SECRET` from `.env`, the same one payment-service reads.
