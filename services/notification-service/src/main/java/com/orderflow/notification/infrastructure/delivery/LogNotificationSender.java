package com.orderflow.notification.infrastructure.delivery;

import com.orderflow.notification.application.NotificationDraft;
import com.orderflow.notification.application.NotificationSender;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
@Requires(property = "notification.channel", value = "LOG")
public final class LogNotificationSender implements NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(LogNotificationSender.class);

    @Override
    public void send(NotificationDraft draft) {
        log.info("Notification recorded through LOG channel: eventId={}, type={}, orderId={}",
                draft.eventId(), draft.type(), draft.orderId());
    }
}
