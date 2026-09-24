CREATE INDEX idx_outbox_event_published_at ON orders.outbox_event (published_at) WHERE published_at IS NOT NULL;
CREATE INDEX idx_processed_event_processed_at ON orders.processed_event (processed_at);
