package com.orderflow.notification.delivery;

import com.orderflow.notification.messaging.NotificationEvent;
import com.orderflow.notification.notifications.NotificationDraft;
import com.orderflow.notification.notifications.NotificationSettings.Channel;
import com.orderflow.notification.notifications.NotificationSettings;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class NotificationComposerTest {
    private final NotificationComposer composer = new NotificationComposer(
            new NotificationSettings(Channel.SMTP, "ops@orderflow.test"));
    private final UUID orderId = UUID.randomUUID();
    private final UUID customerId = UUID.randomUUID();

    @Test
    void composesAllSupportedEventsWithTheirRecipientAndContent() {
        var confirmed = compose("OrderConfirmed", new NotificationEvent.OrderConfirmed(orderId, customerId,
                "buyer@orderflow.test", new BigDecimal("24.00"), "EUR"));
        assertThat(confirmed.recipient()).isEqualTo("buyer@orderflow.test");
        assertThat(confirmed.body()).contains(orderId.toString(), "24.00 EUR");
        assertThat(confirmed.channel()).isEqualTo(Channel.SMTP);
        var rejected = compose("OrderRejected", new NotificationEvent.OrderRejected(orderId, customerId,
                "buyer@orderflow.test", "INSUFFICIENT_STOCK"));
        assertThat(rejected.body()).contains("INSUFFICIENT_STOCK");
        assertThat(rejected.subject()).startsWith("Order rejected");
        var cancelled = compose("OrderCancelled", new NotificationEvent.OrderCancelled(orderId, customerId,
                "buyer@orderflow.test", "CONFIRMED"));
        assertThat(cancelled.subject()).startsWith("Order cancelled");
        assertThat(cancelled.orderId()).isEqualTo(orderId);
        var lowStock = compose("LowStock", new NotificationEvent.LowStock("CHAIR-001", 3, 5));
        assertThat(lowStock.orderId()).isNull();
        assertThat(lowStock.recipient()).isEqualTo("ops@orderflow.test");
        assertThat(lowStock.body()).contains("CHAIR-001", "3 units", "5");
    }

    private NotificationDraft compose(String type, NotificationEvent.Payload payload) {
        return composer.compose(new NotificationEvent(UUID.randomUUID(), type, Instant.now(),
                orderId.toString(), payload));
    }
}
