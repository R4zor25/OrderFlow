INSERT INTO inventory.stock_item
    (sku, on_hand, reserved, low_stock_threshold, low_stock_notified, version, updated_at)
VALUES
    ('CHAIR-001', 100, 0, 10, false, 0, now()),
    ('CHAIR-002', 100, 0, 10, false, 0, now()),
    ('CHAIR-003', 5, 0, 10, false, 0, now()),
    ('CHAIR-004', 100, 0, 10, false, 0, now()),
    ('CHAIR-005', 100, 0, 10, false, 0, now()),
    ('DESK-001', 100, 0, 10, false, 0, now()),
    ('DESK-002', 100, 0, 10, false, 0, now()),
    ('DESK-003', 100, 0, 10, false, 0, now()),
    ('LAMP-001', 100, 0, 10, false, 0, now()),
    ('LAMP-002', 100, 0, 10, false, 0, now()),
    ('LAMP-003', 100, 0, 10, false, 0, now()),
    ('LAMP-004', 100, 0, 10, false, 0, now()),
    ('LAMP-005', 100, 0, 10, false, 0, now()),
    ('LAMP-006', 8, 0, 10, false, 0, now()),
    ('NOTEBOOK-001', 100, 0, 10, false, 0, now()),
    ('PEN-001', 100, 0, 10, false, 0, now()),
    ('MONITOR-001', 100, 0, 10, false, 0, now()),
    ('KEYBOARD-001', 100, 0, 10, false, 0, now()),
    ('MOUSE-001', 100, 0, 10, false, 0, now()),
    ('STAND-001', 100, 0, 10, false, 0, now());
