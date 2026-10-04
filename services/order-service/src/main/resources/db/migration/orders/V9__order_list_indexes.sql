-- Every order-list query filters on a prefix of one index and reads it in sort order, so no Sort step is needed.
DROP INDEX orders.idx_orders_customer_created;
DROP INDEX orders.idx_orders_status_created;
CREATE INDEX idx_orders_created ON orders.orders (created_at DESC, id DESC);
CREATE INDEX idx_orders_customer_created ON orders.orders (customer_id, created_at DESC, id DESC);
CREATE INDEX idx_orders_status_created ON orders.orders (status, created_at DESC, id DESC);
CREATE INDEX idx_orders_customer_status_created ON orders.orders (customer_id, status, created_at DESC, id DESC);
