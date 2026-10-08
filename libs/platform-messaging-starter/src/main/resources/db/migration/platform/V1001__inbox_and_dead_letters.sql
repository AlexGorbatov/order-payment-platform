-- Idempotent consumer inbox and persisted dead letters (architecture §7.2, §7.4, §10; ADR-0005, ADR-0007).

-- One row per (consumer group, event) that was processed. Inserted with ON CONFLICT DO NOTHING in the same
-- transaction as the business change, so "row exists" means "the business change committed".
CREATE TABLE inbox_message (
    consumer_group varchar(128) NOT NULL,
    event_id       uuid         NOT NULL,
    received_at    timestamptz  NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer_group, event_id)
);

-- Retention cleanup (14 days, longer than the 7 days of topic retention).
CREATE INDEX inbox_message_received_at_idx ON inbox_message (received_at);

-- A record that ended up on a <topic>-dlt topic.
--
--   partition, "offset"          = coordinates of the record on the DLT topic (unique: the persister is idempotent)
--   original_topic               = the source topic (retry suffix stripped); where a replay publishes to
--   original_partition/offset    = coordinates of the failing record as reported by Spring Kafka (last hop)
--   payload                      = record value as received; bytea, because poison messages need not be valid text
--   headers                      = all record headers as strings (exception headers included)
--   status                       = NEW -> REPLAYED | RESOLVED; note holds the resolve comment
CREATE TABLE dead_letter_message (
    id                 uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    original_topic     varchar(249)  NOT NULL,
    dlt_topic          varchar(249)  NOT NULL,
    partition          integer       NOT NULL,
    "offset"           bigint        NOT NULL,
    original_partition integer,
    original_offset    bigint,
    message_key        varchar(255),
    payload            bytea         NOT NULL,
    headers            jsonb         NOT NULL DEFAULT '{}'::jsonb,
    exception_class    varchar(255),
    exception_message  text,
    status             varchar(16)   NOT NULL DEFAULT 'NEW'
                           CHECK (status IN ('NEW', 'REPLAYED', 'RESOLVED')),
    note               text,
    created_at         timestamptz   NOT NULL DEFAULT now(),
    updated_at         timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT dead_letter_message_dlt_record_uk UNIQUE (dlt_topic, partition, "offset")
);

CREATE INDEX dead_letter_message_status_idx ON dead_letter_message (status, created_at DESC);
CREATE INDEX dead_letter_message_topic_idx ON dead_letter_message (original_topic, created_at DESC);
