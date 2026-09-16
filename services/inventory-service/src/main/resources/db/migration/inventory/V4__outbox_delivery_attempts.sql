ALTER TABLE inventory.outbox_event
    ADD COLUMN attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    ADD COLUMN last_error varchar(1000),
    ADD COLUMN parked_at timestamptz;
