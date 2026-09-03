CREATE TABLE orders.customer (
    id uuid PRIMARY KEY,
    name varchar(255) NOT NULL,
    email varchar(255) NOT NULL UNIQUE,
    type varchar(3) NOT NULL CHECK (type IN ('B2C', 'B2B')),
    customer_group_code varchar(32) NOT NULL,
    CONSTRAINT ck_customer_group_type CHECK (
        (type = 'B2C' AND customer_group_code = 'B2C') OR
        (type = 'B2B' AND customer_group_code IN ('B2B_STANDARD', 'B2B_GOLD'))
    )
);

CREATE TABLE orders.orders (
    id uuid PRIMARY KEY,
    customer_id uuid NOT NULL REFERENCES orders.customer (id),
    status varchar(20) NOT NULL CHECK (status IN ('PENDING', 'CONFIRMED', 'REJECTED', 'CANCELLED')),
    currency varchar(3) NOT NULL CHECK (currency = 'EUR'),
    total_net numeric(12, 2) NOT NULL CHECK (total_net >= 0),
    total_vat numeric(12, 2) NOT NULL CHECK (total_vat >= 0),
    total_gross numeric(12, 2) NOT NULL CHECK (total_gross = total_net + total_vat),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL CHECK (version >= 0)
);

CREATE INDEX idx_orders_customer_created ON orders.orders (customer_id, created_at DESC);
CREATE INDEX idx_orders_status_created ON orders.orders (status, created_at DESC);

CREATE TABLE orders.order_line (
    id uuid PRIMARY KEY,
    order_id uuid NOT NULL REFERENCES orders.orders (id),
    sku varchar(32) NOT NULL,
    quantity integer NOT NULL CHECK (quantity > 0),
    unit_price_net numeric(12, 2) NOT NULL CHECK (unit_price_net >= 0),
    line_total_net numeric(12, 2) NOT NULL CHECK (line_total_net = unit_price_net * quantity)
);

CREATE INDEX idx_order_line_order_id ON orders.order_line (order_id);

CREATE TABLE orders.outbox_event (
    id uuid PRIMARY KEY,
    aggregate_type varchar(64) NOT NULL,
    aggregate_id uuid NOT NULL,
    event_type varchar(128) NOT NULL,
    payload jsonb NOT NULL,
    headers jsonb,
    created_at timestamptz NOT NULL,
    published_at timestamptz
);

CREATE INDEX idx_outbox_event_unpublished ON orders.outbox_event (created_at, id)
    WHERE published_at IS NULL;

CREATE TABLE orders.processed_event (
    event_id uuid PRIMARY KEY,
    processed_at timestamptz NOT NULL
);
