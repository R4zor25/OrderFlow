package com.orderflow.inventory.application;

import java.time.Instant;
import java.util.UUID;

import com.orderflow.inventory.infrastructure.events.EventEnvelope;
import com.orderflow.inventory.infrastructure.events.OrderCancelledPayload;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class OrderCancelledProcessorTest {

    private final OrderCancellationService cancellation = mock(OrderCancellationService.class);
    private final OrderCancelledProcessor processor = new OrderCancelledProcessor(cancellation);

    @Test
    void releasesWithOnlyTheOrderId() {
        UUID eventId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();

        processor.process(new EventEnvelope<>(eventId, "OrderCancelled", 1, Instant.now(), orderId.toString(),
            new OrderCancelledPayload(orderId, null, null, null)));

        verify(cancellation).release(eventId, orderId);
    }

    @Test
    void rejectsCancellationWhoseOrderDoesNotMatchTheAggregate() {
        UUID orderId = UUID.randomUUID();

        assertThatThrownBy(() -> processor.process(new EventEnvelope<>(UUID.randomUUID(), "OrderCancelled", 1,
            Instant.now(), UUID.randomUUID().toString(), new OrderCancelledPayload(orderId, null, null, null))))
            .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(cancellation);
    }
}
