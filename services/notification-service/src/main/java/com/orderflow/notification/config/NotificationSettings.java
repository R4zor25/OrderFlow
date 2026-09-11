package com.orderflow.notification.config;

import io.micronaut.context.annotation.ConfigurationProperties;

@ConfigurationProperties("notification")
public record NotificationSettings(Channel channel, String opsEmail) {

    public enum Channel {
        SMTP, LOG
    }
}
