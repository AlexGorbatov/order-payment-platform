-- Which business flow a payment or refund belongs to, and which consumed event created it. The workers that act later
-- (PaymentInitiationWorker, RefundWorker, the webhook processor) publish their events with this correlation id and name
-- this event as the cause (architecture §9.2).
ALTER TABLE payment ADD COLUMN correlation_id uuid;
ALTER TABLE payment ADD COLUMN caused_by_event_id uuid;

ALTER TABLE refund ADD COLUMN correlation_id uuid;
ALTER TABLE refund ADD COLUMN caused_by_event_id uuid;
