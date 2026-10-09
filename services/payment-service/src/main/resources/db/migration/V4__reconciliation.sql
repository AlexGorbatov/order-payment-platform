-- T15: reconciliation with Stripe (architecture §8.4, ADR-0010).

-- When the reconciliation job last claimed the payment. Bookkeeping of the job, not state of the payment: it is not
-- mapped into the aggregate and does not touch the optimistic-lock version. It keeps a checked payment from being
-- checked again (by this or another instance) before the stale period has passed.
ALTER TABLE payment ADD COLUMN last_reconciled_at timestamptz;

-- Candidates: payments still waiting for Stripe's verdict, with a PaymentIntent, oldest change first.
CREATE INDEX payment_reconciliation_idx ON payment (updated_at)
    WHERE status IN ('REQUIRES_PAYMENT_METHOD', 'REQUIRES_ACTION', 'PROCESSING')
      AND stripe_payment_intent_id IS NOT NULL;
