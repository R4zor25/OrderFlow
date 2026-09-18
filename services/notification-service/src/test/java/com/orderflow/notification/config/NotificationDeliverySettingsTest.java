package com.orderflow.notification.config;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationDeliverySettingsTest {

    private static NotificationDeliverySettings defaults() {
        return new NotificationDeliverySettings(true, Duration.ofSeconds(30), Duration.ofMinutes(2),
                Duration.ofSeconds(30), Duration.ofHours(1), 10, 20);
    }

    @Test
    void doublesBackoffFromTheInitialValue() {
        assertThat(defaults().backoffAfter(1)).isEqualTo(Duration.ofSeconds(30));
        assertThat(defaults().backoffAfter(2)).isEqualTo(Duration.ofSeconds(60));
        assertThat(defaults().backoffAfter(4)).isEqualTo(Duration.ofSeconds(240));
    }

    @Test
    void capsBackoffAtTheMaximum() {
        assertThat(defaults().backoffAfter(8)).isEqualTo(Duration.ofHours(1));
        assertThat(defaults().backoffAfter(60)).isEqualTo(Duration.ofHours(1));
    }

    @Test
    void rejectsNonPositiveLimits() {
        assertThatThrownBy(() -> new NotificationDeliverySettings(true, Duration.ofSeconds(30),
                Duration.ofMinutes(2), Duration.ofSeconds(30), Duration.ofHours(1), 0, 20))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NotificationDeliverySettings(true, Duration.ZERO,
                Duration.ofMinutes(2), Duration.ofSeconds(30), Duration.ofHours(1), 10, 20))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMaximumBackoffBelowTheInitialBackoff() {
        assertThatThrownBy(() -> new NotificationDeliverySettings(true, Duration.ofSeconds(30),
                Duration.ofMinutes(2), Duration.ofMinutes(5), Duration.ofMinutes(1), 10, 20))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
