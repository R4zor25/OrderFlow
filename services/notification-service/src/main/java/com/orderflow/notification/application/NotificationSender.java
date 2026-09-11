package com.orderflow.notification.application;

public interface NotificationSender {

    void send(NotificationDraft draft);
}
