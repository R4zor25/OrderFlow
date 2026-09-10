ALTER TABLE inventory.outbox_event
    ALTER COLUMN aggregate_id TYPE varchar(64)
    USING aggregate_id::text;
