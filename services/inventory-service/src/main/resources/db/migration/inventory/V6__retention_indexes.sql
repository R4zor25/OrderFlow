CREATE INDEX idx_outbox_event_published_at ON inventory.outbox_event (published_at) WHERE published_at IS NOT NULL;
CREATE INDEX idx_processed_event_processed_at ON inventory.processed_event (processed_at);
CREATE INDEX idx_cancelled_order_cancelled_at ON inventory.cancelled_order (cancelled_at);
