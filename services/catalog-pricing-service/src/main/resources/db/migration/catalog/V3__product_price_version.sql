-- Increases with every price-list replacement under the product row lock; the cache keeps only the newest version.
ALTER TABLE catalog.product ADD COLUMN price_version bigint NOT NULL DEFAULT 0;
