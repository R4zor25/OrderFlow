package com.orderflow.notification.notifications;

import io.micronaut.context.annotation.ConfigurationProperties;

@ConfigurationProperties("notification")
public record NotificationSettings(Channel channel, String opsEmail) {

    private static final int MAX_EMAIL_LENGTH = 320;

    public NotificationSettings {
        if (channel == null) {
            throw new IllegalArgumentException("notification.channel is required");
        }
        if (opsEmail == null || opsEmail.isBlank() || opsEmail.length() > MAX_EMAIL_LENGTH) {
            throw new IllegalArgumentException("notification.ops-email must contain 1 to 320 characters");
        }
    }

    public enum Channel {
        SMTP, LOG
    }
}
