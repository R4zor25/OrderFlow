package com.orderflow.inventory.infrastructure.events;

import com.orderflow.inventory.application.OrderPlacedProcessor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class OrderPlacedListenerTest {

    private final OrderPlacedListener listener = new OrderPlacedListener(
        JsonMapper.builder().findAndAddModules().build(), mock(OrderPlacedProcessor.class));

    @Test
    void rejectsMessageWithoutEventType() {
        var record = new ConsumerRecord<>("orders.order-events.v1", 0, 0L,
            "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", "{\"payload\":{}}");

        assertThatThrownBy(() -> listener.onMessage(record))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnknownEventType() {
        var record = new ConsumerRecord<>("orders.order-events.v1", 0, 0L,
            "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", "{\"eventType\":\"Unexpected\"}");

        assertThatThrownBy(() -> listener.onMessage(record))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void leavesKnownOrderEventsForTheirOwnSteps() {
        for (String type : new String[]{"OrderCancelled", "OrderConfirmed", "OrderRejected"}) {
            var record = new ConsumerRecord<>("orders.order-events.v1", 0, 0L,
                "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", "{\"eventType\":\"" + type + "\"}");

            listener.onMessage(record);
        }
    }
}
