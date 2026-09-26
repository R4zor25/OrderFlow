package com.orderflow.inventory.infrastructure.events;

import com.orderflow.inventory.application.OrderPlacedProcessor;
import com.orderflow.inventory.application.OrderCancelledProcessor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class OrderEventListenerTest {

    private final OrderPlacedProcessor placed = mock(OrderPlacedProcessor.class);
    private final OrderCancelledProcessor cancelled = mock(OrderCancelledProcessor.class);
    private final OrderEventListener listener = new OrderEventListener(
        JsonMapper.builder().findAndAddModules().build(), placed, cancelled);

    @Test
    void rejectsMessageWithoutEventType() {
        var record = new ConsumerRecord<>("orders.order-events.v1", 0, 0L,
            "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", "{\"payload\":{}}");

        assertThatThrownBy(() -> listener.onMessage(record))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void skipsUnknownEventType() {
        var record = new ConsumerRecord<>("orders.order-events.v1", 0, 0L,
            "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", "{\"eventType\":\"OrderShipped\",\"eventVersion\":1}");

        listener.onMessage(record);

        verifyNoInteractions(placed, cancelled);
    }

    @Test
    void skipsNewerOrderEventVersion() {
        var record = new ConsumerRecord<>("orders.order-events.v1", 0, 0L,
            "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", "{\"eventType\":\"OrderPlaced\",\"eventVersion\":2}");

        listener.onMessage(record);

        verifyNoInteractions(placed, cancelled);
    }

    @Test
    void leavesKnownOrderEventsForTheirOwnSteps() {
        for (String type : new String[]{"OrderConfirmed", "OrderRejected"}) {
            var record = new ConsumerRecord<>("orders.order-events.v1", 0, 0L,
                "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", "{\"eventType\":\"" + type + "\"}");

            listener.onMessage(record);
        }
    }
}
