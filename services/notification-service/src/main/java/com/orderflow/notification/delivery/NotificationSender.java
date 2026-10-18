package com.orderflow.notification.delivery;

import com.orderflow.notification.notifications.NotificationDraft;

public interface NotificationSender {

    void send(NotificationDraft draft);
}
