package com.orderflow.notification.notifications;

import com.orderflow.notification.notifications.NotificationSettings.Channel;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationSettingsTest {

    @Test
    void acceptsValidOpsEmail() {
        assertThat(new NotificationSettings(Channel.LOG, "ops@orderflow.test").opsEmail())
                .isEqualTo("ops@orderflow.test");
    }

    @Test
    void acceptsOpsEmailOfExactly320Characters() {
        var opsEmail = "a".repeat(307) + "@example.test";
        assertThat(new NotificationSettings(Channel.LOG, opsEmail).opsEmail()).hasSize(320);
    }

    @Test
    void rejectsBlankOpsEmail() {
        assertThatThrownBy(() -> new NotificationSettings(Channel.LOG, " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("notification.ops-email must contain 1 to 320 characters");
    }

    @Test
    void rejectsOpsEmailLongerThan320Characters() {
        assertThatThrownBy(() -> new NotificationSettings(Channel.LOG, "a".repeat(308) + "@example.test"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMissingChannel() {
        assertThatThrownBy(() -> new NotificationSettings(null, "ops@orderflow.test"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
