package com.orderflow.notification.delivery;

import com.orderflow.notification.notifications.NotificationDraft;
import io.micronaut.context.annotation.Requires;
import io.micronaut.email.BodyType;
import io.micronaut.email.Email;
import io.micronaut.email.EmailSender;
import jakarta.inject.Singleton;

@Singleton
@Requires(property = "notification.channel", value = "SMTP")
public final class SmtpNotificationSender implements NotificationSender {

    private final EmailSender<?, ?> emailSender;

    public SmtpNotificationSender(EmailSender<?, ?> emailSender) {
        this.emailSender = emailSender;
    }

    @Override
    public void send(NotificationDraft notification) {
        emailSender.send(Email.builder()
                .to(notification.recipient())
                .subject(notification.subject())
                .body(notification.body(), BodyType.TEXT));
    }
}
