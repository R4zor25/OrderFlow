CREATE TABLE inventory.cancelled_order (
    order_id     uuid        PRIMARY KEY,
    cancelled_at timestamptz NOT NULL
);
