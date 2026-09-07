CREATE TABLE inventory.stock_item (
    sku varchar(32) PRIMARY KEY,
    on_hand integer NOT NULL CHECK (on_hand >= 0),
    reserved integer NOT NULL CHECK (reserved >= 0 AND reserved <= on_hand),
    low_stock_threshold integer NOT NULL CHECK (low_stock_threshold >= 0),
    low_stock_notified boolean NOT NULL,
    version bigint NOT NULL CHECK (version >= 0),
    updated_at timestamptz NOT NULL
);

CREATE TABLE inventory.reservation (
    id uuid PRIMARY KEY,
    order_id uuid NOT NULL,
    sku varchar(32) NOT NULL REFERENCES inventory.stock_item (sku),
    quantity integer NOT NULL CHECK (quantity > 0),
    status varchar(20) NOT NULL CHECK (status IN ('RESERVED', 'RELEASED')),
    created_at timestamptz NOT NULL,
    CONSTRAINT uq_reservation_order_sku UNIQUE (order_id, sku)
);

CREATE INDEX idx_reservation_order_id ON inventory.reservation (order_id);

CREATE SEQUENCE inventory.outbox_delivery_order_seq;

CREATE TABLE inventory.outbox_event (
    id uuid PRIMARY KEY,
    aggregate_type varchar(64) NOT NULL,
    aggregate_id uuid NOT NULL,
    event_type varchar(128) NOT NULL,
    payload jsonb NOT NULL,
    headers jsonb,
    created_at timestamptz NOT NULL,
    delivery_order bigint NOT NULL DEFAULT nextval('inventory.outbox_delivery_order_seq'),
    published_at timestamptz
);

CREATE INDEX idx_outbox_event_unpublished
    ON inventory.outbox_event (created_at, id)
    WHERE published_at IS NULL;

CREATE INDEX idx_outbox_event_unpublished_aggregate_order
    ON inventory.outbox_event (aggregate_type, aggregate_id, delivery_order)
    WHERE published_at IS NULL;

CREATE TABLE inventory.processed_event (
    event_id uuid PRIMARY KEY,
    processed_at timestamptz NOT NULL
);

CREATE TABLE inventory.shedlock (
    name varchar(64) PRIMARY KEY,
    lock_until timestamptz NOT NULL,
    locked_at timestamptz NOT NULL,
    locked_by varchar(255) NOT NULL
);
