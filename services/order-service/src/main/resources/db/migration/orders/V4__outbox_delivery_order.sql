ALTER TABLE orders.outbox_event ADD COLUMN delivery_order bigint;

CREATE SEQUENCE orders.outbox_delivery_order_seq;

WITH numbered AS (
    SELECT id, row_number() OVER (ORDER BY created_at, id) AS position
    FROM orders.outbox_event
)
UPDATE orders.outbox_event event
SET delivery_order = numbered.position
FROM numbered
WHERE event.id = numbered.id;

SELECT setval(
    'orders.outbox_delivery_order_seq',
    COALESCE((SELECT max(delivery_order) FROM orders.outbox_event), 1),
    EXISTS (SELECT 1 FROM orders.outbox_event)
);

ALTER TABLE orders.outbox_event
    ALTER COLUMN delivery_order SET DEFAULT nextval('orders.outbox_delivery_order_seq'),
    ALTER COLUMN delivery_order SET NOT NULL;

CREATE INDEX idx_outbox_event_unpublished_aggregate_order
    ON orders.outbox_event (aggregate_type, aggregate_id, delivery_order)
    WHERE published_at IS NULL;
