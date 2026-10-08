# ADR-0012: Stripe test-mode strategy (WireMock / stripe-mock / Stripe CLI) and live-mode guard

- Status: Accepted
- Date: 2026-10-08
- Related: architecture §8.1, §8.2, §8.5, §12, §14

## Context

The platform must be fully testable in CI without a Stripe account or network access, must exercise realistic
failure scenarios (timeouts, 5xx, declines, out-of-order webhooks), and must be impossible to run against live money.

## Decision

Three Stripe modes:

| Mode | Stripe API | Webhooks | Account | Used by |
|---|---|---|---|---|
| tests | WireMock scenarios + `stripe/stripe-mock` contract smoke tests | signed in-test by `StripeWebhookTestSigner` | no | CI |
| `local` | `stripe-mock` | `scripts/send-test-webhook.sh` signs fixtures with a local secret | no | dev |
| `stripe-test` | real Stripe with a `sk_test_` key | Stripe CLI `listen --forward-to` | free test account | manual demo |

`stripe-mock` is stateless (no transitions, no webhooks), so scenario control uses WireMock.

Safety:
- `LiveModeGuard` fails startup unless the key starts with `sk_test_` or `rk_test_`.
- Webhook events with `livemode=true` are rejected (ADR-0009).
- Keys and webhook secrets come only from the environment (`.env`, gitignored).
- `StripeClient` is instance-based (no static `Stripe.apiKey`) with explicit timeouts and `maxNetworkRetries=2`.

## Alternatives considered

- **Real Stripe test mode in CI** — needs secrets in CI, network access, and cannot reliably produce failures such as
  timeouts or out-of-order delivery.
- **Only stripe-mock** — cannot model state transitions or failures.
- **Hand-written fakes of the gateway port only** — fast, but never exercises the real SDK's HTTP, serialization and
  error mapping.

## Consequences

- Every failure in §15 is testable deterministically; no test needs a Stripe account.
- WireMock stubs must be kept faithful to Stripe's API; contract smoke tests against stripe-mock catch shape drift.
- Running against live keys is prevented at startup, not by convention.
