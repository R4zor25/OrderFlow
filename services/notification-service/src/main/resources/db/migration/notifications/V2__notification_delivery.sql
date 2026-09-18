ALTER TABLE notifications.notification
    ADD COLUMN status varchar(16) NOT NULL DEFAULT 'SENT' CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    ADD COLUMN attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    ADD COLUMN next_attempt_at timestamptz,
    ADD COLUMN sent_at timestamptz,
    ADD COLUMN last_error varchar(1000);

ALTER TABLE notifications.notification ALTER COLUMN status DROP DEFAULT;

CREATE INDEX idx_notification_pending_due
    ON notifications.notification (next_attempt_at)
    WHERE status = 'PENDING';
