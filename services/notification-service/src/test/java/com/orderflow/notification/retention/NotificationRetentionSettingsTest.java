package com.orderflow.notification.retention;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationRetentionSettingsTest {

    @Test
    void acceptsAPositivePeriodAndBatch() {
        assertThatCode(() -> new NotificationRetentionSettings(true, Duration.ofDays(30), 1000))
            .doesNotThrowAnyException();
    }

    @Test
    void rejectsAPeriodThatWouldDeleteEverything() {
        for (Duration invalid : new Duration[] {null, Duration.ZERO, Duration.ofDays(-1)}) {
            assertThatThrownBy(() -> new NotificationRetentionSettings(true, invalid, 1000))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void rejectsAnEmptyBatch() {
        assertThatThrownBy(() -> new NotificationRetentionSettings(true, Duration.ofDays(30), 0))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
