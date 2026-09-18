ALTER TABLE orders.orders
    ADD COLUMN idempotency_key varchar(128),
    ADD COLUMN request_hash varchar(64) CHECK (char_length(request_hash) = 64);

CREATE UNIQUE INDEX ux_orders_customer_idempotency_key
    ON orders.orders (customer_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
