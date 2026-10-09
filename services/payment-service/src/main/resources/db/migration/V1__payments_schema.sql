-- payments_db (architecture §10): payments, refunds, the payment status history and the stored Stripe webhook events.
-- Platform tables (outbox, inbox, dead letters, idempotency) come from the starters, versions V1000+.
--
-- Constraints are named: the persistence adapter recognises a duplicate by the name of the violated constraint.

CREATE TABLE payment (
    id                       uuid          PRIMARY KEY,
    order_id                 uuid          NOT NULL,
    customer_id              varchar(255)  NOT NULL,
    amount_minor             bigint        NOT NULL CHECK (amount_minor > 0),
    currency                 varchar(3)    NOT NULL,
    status                   varchar(32)   NOT NULL
                                 CONSTRAINT payment_status_check CHECK (status IN (
                                     'CREATED', 'REQUIRES_PAYMENT_METHOD', 'REQUIRES_ACTION', 'PROCESSING',
                                     'SUCCEEDED', 'CANCELED', 'INITIATION_FAILED', 'REFUNDED')),
    stripe_payment_intent_id varchar(255),
    -- ordering watermark (§8.3): the observedAt of the latest applied Stripe report
    last_stripe_event_at     timestamptz,
    last_error_code          varchar(128),
    last_error_message       varchar(1024),
    cancel_requested         boolean       NOT NULL DEFAULT false,
    cancel_sent_at           timestamptz,
    disputed                 boolean       NOT NULL DEFAULT false,
    -- work queue (§7.6): external work is due when next_attempt_at <= now
    attempts                 integer       NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at          timestamptz,
    created_at               timestamptz   NOT NULL,
    updated_at               timestamptz   NOT NULL,
    version                  bigint        NOT NULL,
    -- one payment per order; one PaymentIntent per payment
    CONSTRAINT payment_order_id_key UNIQUE (order_id),
    CONSTRAINT payment_stripe_payment_intent_id_key UNIQUE (stripe_payment_intent_id),
    -- a payment that has moved on from CREATED has a PaymentIntent, unless it never got one
    CONSTRAINT payment_intent_present_check CHECK (
        stripe_payment_intent_id IS NOT NULL
        OR status IN ('CREATED', 'INITIATION_FAILED', 'CANCELED'))
);

-- workers claim due rows by status: the index holds only the rows that have work
CREATE INDEX payment_due_idx ON payment (status, next_attempt_at) WHERE next_attempt_at IS NOT NULL;

CREATE TABLE refund (
    id                uuid          PRIMARY KEY,
    payment_id        uuid          NOT NULL REFERENCES payment (id),
    refund_request_id uuid          NOT NULL,
    amount_minor      bigint        NOT NULL CHECK (amount_minor > 0),
    currency          varchar(3)    NOT NULL,
    reason            varchar(32)   NOT NULL,
    status            varchar(16)   NOT NULL
                          CONSTRAINT refund_status_check CHECK (status IN ('REQUESTED', 'PENDING', 'SUCCEEDED', 'FAILED')),
    stripe_refund_id  varchar(255),
    failure_reason    varchar(1024),
    attempts          integer       NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at   timestamptz,
    created_at        timestamptz   NOT NULL,
    updated_at        timestamptz   NOT NULL,
    version           bigint        NOT NULL,
    CONSTRAINT refund_refund_request_id_key UNIQUE (refund_request_id),
    CONSTRAINT refund_stripe_refund_id_key UNIQUE (stripe_refund_id)
);

-- Full refunds only: a payment can have at most one refund that has not failed, so the money cannot go back twice
-- even if two requests with different ids race.
CREATE UNIQUE INDEX refund_one_open_per_payment ON refund (payment_id) WHERE status <> 'FAILED';

CREATE INDEX refund_due_idx ON refund (status, next_attempt_at) WHERE next_attempt_at IS NOT NULL;

-- Append-only. from_status is null for the entry that creates the payment.
CREATE TABLE payment_status_history (
    id              bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    payment_id      uuid         NOT NULL REFERENCES payment (id),
    from_status     varchar(32),
    to_status       varchar(32)  NOT NULL,
    source          varchar(16)  NOT NULL CHECK (source IN ('STRIPE_API', 'WEBHOOK', 'RECONCILIATION', 'LOCAL')),
    stripe_event_id varchar(255),
    occurred_at     timestamptz  NOT NULL
);

CREATE INDEX payment_status_history_payment_idx ON payment_status_history (payment_id, id);

-- Webhooks are stored before they are processed (§6.6). The event id is the primary key: a redelivery is a no-op.
CREATE TABLE stripe_webhook_event (
    event_id          varchar(255) PRIMARY KEY,
    type              varchar(128) NOT NULL,
    api_version       varchar(32),
    -- test mode only (§8.1, LiveModeGuard): a live-mode event is rejected before it gets here
    livemode          boolean      NOT NULL CHECK (livemode = false),
    stripe_created_at timestamptz  NOT NULL,
    payload           jsonb        NOT NULL,
    status            varchar(16)  NOT NULL
                          CONSTRAINT stripe_webhook_event_status_check CHECK (status IN (
                              'RECEIVED', 'PROCESSED', 'IGNORED', 'STALE_IGNORED', 'FAILED', 'DEAD')),
    attempts          integer      NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at   timestamptz,
    last_error        varchar(1024),
    received_at       timestamptz  NOT NULL,
    processed_at      timestamptz
);

CREATE INDEX stripe_webhook_event_due_idx ON stripe_webhook_event (next_attempt_at) WHERE next_attempt_at IS NOT NULL;
-- retention job: events older than 30 days
CREATE INDEX stripe_webhook_event_received_idx ON stripe_webhook_event (received_at);
