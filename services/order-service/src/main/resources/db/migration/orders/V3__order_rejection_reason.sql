ALTER TABLE orders.orders
    ADD COLUMN rejection_reason varchar(500);

ALTER TABLE orders.orders
    ADD CONSTRAINT ck_orders_rejection_reason CHECK (
        (status = 'REJECTED' AND rejection_reason IS NOT NULL AND btrim(rejection_reason) <> '')
        OR (status <> 'REJECTED' AND rejection_reason IS NULL)
    );
