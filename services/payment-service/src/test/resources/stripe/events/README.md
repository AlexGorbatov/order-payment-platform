# Synthetic Stripe webhook events

Templates of Stripe snapshot events (`2026-09-30.endive`, the API version pinned by stripe-java 34) for the tests and for
`scripts/send-test-webhook.sh`. They are made up — no real account, customer or card — and keep only the fields
payment-service reads plus enough context to look like the real thing (docs.stripe.com/api/events/types).

Placeholders: `{{EVENT_ID}}`, `{{CREATED}}` (Unix seconds), `{{LIVEMODE}}`, `{{PAYMENT_INTENT_ID}}`, `{{PAYMENT_ID}}`,
`{{ORDER_ID}}`, `{{AMOUNT}}` (minor units), `{{CURRENCY}}` (lower case), `{{STRIPE_REFUND_ID}}`, `{{REFUND_ID}}`,
`{{SUFFIX}}` (makes ids of charges and disputes unique).
