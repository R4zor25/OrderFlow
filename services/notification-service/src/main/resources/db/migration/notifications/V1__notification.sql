CREATE TABLE notifications.notification (
    id uuid PRIMARY KEY,
    event_id uuid NOT NULL UNIQUE,
    order_id uuid,
    type varchar(64) NOT NULL,
    recipient varchar(320) NOT NULL,
    subject varchar(255) NOT NULL,
    body text NOT NULL,
    channel varchar(16) NOT NULL CHECK (channel IN ('SMTP', 'LOG')),
    created_at timestamptz NOT NULL
);

CREATE INDEX idx_notification_order_created_at
    ON notifications.notification (order_id, created_at DESC);
