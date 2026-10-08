-- Transactional outbox (architecture §7.1, §10, ADR-0004).
-- One row per event, written in the same transaction as the business change.
--
--   id            = envelope eventId (UUIDv7)
--   payload       = the complete event envelope as JSON; it is the Kafka record value
--   headers       = Kafka headers to send: eventType, eventVersion, correlationId, traceparent
--   created_at    = clock_timestamp() at insert (distinct per statement, so insertion order is preserved)
--   published_at  = set by OutboxRelay once Kafka acknowledged the record
CREATE TABLE outbox_event (
    id             uuid         PRIMARY KEY,
    aggregate_type varchar(64)  NOT NULL,
    aggregate_id   uuid         NOT NULL,
    partition_key  varchar(255) NOT NULL,
    topic          varchar(249) NOT NULL,
    event_type     varchar(128) NOT NULL,
    event_version  integer      NOT NULL,
    payload        jsonb        NOT NULL,
    headers        jsonb        NOT NULL DEFAULT '{}'::jsonb,
    created_at     timestamptz  NOT NULL DEFAULT clock_timestamp(),
    published_at   timestamptz,
    attempts       integer      NOT NULL DEFAULT 0,
    last_error     text
);

-- The relay's work queue: only unpublished rows, in publishing order. Stays tiny when the relay keeps up.
CREATE INDEX outbox_event_unpublished_idx
    ON outbox_event (created_at, id)
    WHERE published_at IS NULL;

-- Per-key ordering guard of the relay: "is there an older unpublished row for this partition key?"
CREATE INDEX outbox_event_unpublished_key_idx
    ON outbox_event (partition_key, created_at, id)
    WHERE published_at IS NULL;

-- Retention cleanup: published rows by age.
CREATE INDEX outbox_event_published_idx
    ON outbox_event (published_at)
    WHERE published_at IS NOT NULL;
