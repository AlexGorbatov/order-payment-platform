-- T13: webhook processing (architecture §6.6, §8.3).

-- The card network's reason of the latest failed attempt (last_payment_error.decline_code), next to last_error_code.
ALTER TABLE payment ADD COLUMN last_decline_code varchar(64);
