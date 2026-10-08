-- HTTP idempotency (architecture §7.3, §10, ADR-0006).
--
-- One row per (principal, Idempotency-Key). The row is inserted as IN_PROGRESS in its own short transaction
-- before the request runs, so a concurrent request with the same key sees it at once, and is completed with the
-- stored response afterwards. A 5xx removes it, so the client may retry.
--
--   principal         = JWT subject of the caller, or 'anonymous' (a key is private to its caller)
--   request_hash      = SHA-256 (hex) of method, path and canonical body
--   response_headers  = replayable response headers: {"Content-Type": ["application/json"], ...}
--   response_body     = response bytes exactly as sent
--   created_at        = when the request was claimed; a very old IN_PROGRESS row is an abandoned claim
--   expires_at        = end of the idempotency window (24 h by default); after it the key may be reused
CREATE TABLE idempotency_record (
    principal        varchar(255) NOT NULL,
    idem_key         varchar(255) NOT NULL,
    request_hash     varchar(64)  NOT NULL,
    status           varchar(16)  NOT NULL CHECK (status IN ('IN_PROGRESS', 'COMPLETED')),
    response_status  integer,
    response_headers jsonb,
    response_body    bytea,
    created_at       timestamptz  NOT NULL DEFAULT now(),
    expires_at       timestamptz  NOT NULL,
    PRIMARY KEY (principal, idem_key)
);

-- Cleanup of expired records.
CREATE INDEX idempotency_record_expires_at_idx ON idempotency_record (expires_at);
