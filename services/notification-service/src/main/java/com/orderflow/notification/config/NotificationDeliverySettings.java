package com.orderflow.notification.config;

import java.time.Duration;

import io.micronaut.context.annotation.ConfigurationProperties;

@ConfigurationProperties("notification.delivery")
public record NotificationDeliverySettings(boolean retryEnabled, Duration retryInterval, Duration lease,
                                           Duration initialBackoff, Duration maxBackoff, int maxAttempts,
                                           int batchSize) {

    public NotificationDeliverySettings {
        if (!positive(retryInterval) || !positive(lease) || !positive(initialBackoff) || !positive(maxBackoff)
                || maxBackoff.compareTo(initialBackoff) < 0 || maxAttempts < 1 || batchSize < 1) {
            throw new IllegalArgumentException("Invalid notification.delivery settings");
        }
    }

    public Duration backoffAfter(int failedAttempts) {
        // The exponent is capped so the multiplication cannot overflow before the maximum applies.
        int exponent = Math.min(Math.max(failedAttempts, 1) - 1, 20);
        Duration backoff = initialBackoff.multipliedBy(1L << exponent);
        return backoff.compareTo(maxBackoff) > 0 ? maxBackoff : backoff;
    }

    private static boolean positive(Duration value) {
        return value != null && !value.isNegative() && !value.isZero();
    }
}
