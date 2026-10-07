-- Retention deletes closed rows by age; the failed gauge counts FAILED rows on every scrape.
CREATE INDEX idx_notification_closed_created_at
    ON notifications.notification (created_at)
    WHERE status IN ('SENT', 'FAILED');

CREATE INDEX idx_notification_failed
    ON notifications.notification (status)
    WHERE status = 'FAILED';
