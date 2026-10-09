# ADR-0009: Webhook ingestion: verify → persist → ack → process asynchronously

- Status: Accepted
- Implementation review: 2026-10-09 (v1.0.0; limitations are documented in architecture §17)
- Date: 2026-10-08
- Related: architecture §6.6, §8.3, §12, §15 (F09, F12, F13, F14)

## Context

Stripe webhooks are the primary source of truth for payment outcomes. Stripe expects a fast 2xx, retries on
failure, may deliver duplicates, and does not guarantee ordering. The endpoint is public, so forged or replayed
requests must be rejected, and live-mode events must never be processed by this test-only system.

## Decision

`POST /webhooks/stripe` handles only ingestion:

1. Read the raw body (≤ 256 KB) and verify `Stripe-Signature` (HMAC-SHA256, 300 s tolerance). Multiple secrets are
   accepted to support rotation. Invalid ⇒ 400 + `webhook.signature.failures`, nothing stored.
2. Reject `livemode=true` ⇒ 400 + ERROR log + metric.
3. `INSERT INTO stripe_webhook_event ... ON CONFLICT DO NOTHING` — the event id is the dedupe key.
4. Respond 200.

A `WebhookProcessor` claims `RECEIVED`/`FAILED` rows with `SKIP LOCKED` and dispatches by type: applied ⇒ aggregate,
history and outbox in one transaction; stale ⇒ `PROCESSED` + stale metric; unknown type ⇒ `IGNORED`; error ⇒
`FAILED` with backoff, eventually `DEAD` + ERROR log and metric, recoverable via runbook (alert rules are not shipped).

## Alternatives considered

- **Process synchronously in the request** — slow handlers cause Stripe retries and duplicate pressure; a crash
  mid-processing loses the event unless Stripe retries.
- **Publish raw webhooks to Kafka** — adds a hop with its own failure modes; the DB already provides dedupe and retry.
- **Stripe event polling instead of webhooks** — higher latency and rate-limit pressure; kept only as the
  reconciliation safety net (ADR-0010).

## Consequences

- Fast, deterministic responses to Stripe; duplicates are absorbed by the primary key (F09).
- Processing is decoupled and retryable; failures are visible as `FAILED`/`DEAD` rows (F14).
- Full payloads are stored in the DB but never logged; the retention target is 30 days, but cleanup is manual in v1.0.0 ([runbook](../runbooks/webhooks.md#9-housekeeping)).
