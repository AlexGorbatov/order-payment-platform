-- T09: the order-service side of the saga (architecture §6, §7).

-- The latest refund request of the order. Refund outcomes (PaymentRefunded / PaymentRefundFailed) carry the request
-- id; one for an older request can arrive late through the retry topics (ADR-0007) and must not change the order.
ALTER TABLE orders ADD COLUMN refund_request_id uuid;

-- PaymentTimeoutJob: the oldest orders still awaiting payment, claimed with FOR UPDATE SKIP LOCKED.
CREATE INDEX orders_pending_payment_idx ON orders (created_at) WHERE status = 'PENDING_PAYMENT';
