CREATE TABLE catalog.product (
    id uuid PRIMARY KEY,
    sku varchar(32) NOT NULL UNIQUE,
    name varchar(255) NOT NULL,
    category varchar(64) NOT NULL,
    list_price_net numeric(12, 2) NOT NULL CHECK (list_price_net >= 0),
    vat_rate numeric(4, 2) NOT NULL CHECK (vat_rate >= 0 AND vat_rate <= 1),
    active boolean NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);

CREATE INDEX idx_product_category ON catalog.product (category);

CREATE TABLE catalog.customer_group (
    code varchar(32) PRIMARY KEY
);

CREATE TABLE catalog.price_list_entry (
    id uuid PRIMARY KEY,
    customer_group_code varchar(32) NOT NULL REFERENCES catalog.customer_group (code),
    sku varchar(32) NOT NULL REFERENCES catalog.product (sku),
    min_qty integer NOT NULL CHECK (min_qty > 0),
    unit_price_net numeric(12, 2) NOT NULL CHECK (unit_price_net >= 0),
    valid_from date NOT NULL,
    valid_to date,
    CONSTRAINT uq_price_list_entry_tier UNIQUE (customer_group_code, sku, min_qty, valid_from),
    CONSTRAINT ck_price_list_entry_dates CHECK (valid_to IS NULL OR valid_to >= valid_from)
);

CREATE INDEX idx_price_list_entry_sku ON catalog.price_list_entry (sku);
