-- orders_db (architecture §10): catalog, orders, order lines and the status history.
-- Platform tables (outbox, inbox, dead letters, idempotency) come from the starters, versions V1000+.

-- The server-side catalog: the only source of prices.
CREATE TABLE product (
    sku         varchar(64)  PRIMARY KEY,
    name        varchar(255) NOT NULL,
    price_minor bigint       NOT NULL CHECK (price_minor > 0),
    currency    varchar(3)   NOT NULL,
    active      boolean      NOT NULL DEFAULT true
);

CREATE TABLE orders (
    id            uuid         PRIMARY KEY,
    customer_id   varchar(255) NOT NULL,
    status        varchar(32)  NOT NULL
                      CHECK (status IN ('PENDING_PAYMENT', 'PAID', 'CANCELLED', 'REFUND_REQUESTED', 'REFUNDED', 'REFUND_FAILED')),
    currency      varchar(3)   NOT NULL,
    total_minor   bigint       NOT NULL CHECK (total_minor > 0),
    cancel_reason varchar(32)
                      CHECK (cancel_reason IN ('CUSTOMER', 'TIMEOUT', 'PAYMENT_INITIATION_FAILED', 'PAYMENT_CANCELED')),
    disputed      boolean      NOT NULL DEFAULT false,
    created_at    timestamptz  NOT NULL,
    updated_at    timestamptz  NOT NULL,
    version       bigint       NOT NULL
);

-- "my orders", newest first
CREATE INDEX orders_customer_idx ON orders (customer_id, created_at DESC);

CREATE TABLE order_item (
    id               bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id         uuid         NOT NULL REFERENCES orders (id),
    sku              varchar(64)  NOT NULL,
    name             varchar(255) NOT NULL,
    quantity         integer      NOT NULL CHECK (quantity BETWEEN 1 AND 10),
    unit_price_minor bigint       NOT NULL CHECK (unit_price_minor > 0),
    line_total_minor bigint       NOT NULL CHECK (line_total_minor > 0),
    UNIQUE (order_id, sku)
);

-- Append-only. from_status is null for the entry that creates the order and equals to_status for changes that do
-- not move the status (a dispute).
CREATE TABLE order_status_history (
    id              bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id        uuid         NOT NULL REFERENCES orders (id),
    from_status     varchar(32),
    to_status       varchar(32)  NOT NULL,
    reason          varchar(255),
    source          varchar(16)  NOT NULL CHECK (source IN ('API', 'EVENT', 'JOB')),
    source_event_id uuid,
    occurred_at     timestamptz  NOT NULL
);

CREATE INDEX order_status_history_order_idx ON order_status_history (order_id, id);
