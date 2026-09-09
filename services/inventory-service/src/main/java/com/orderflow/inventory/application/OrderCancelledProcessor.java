package com.orderflow.inventory.application;

import java.util.Objects;

import com.orderflow.inventory.infrastructure.events.EventEnvelope;
import com.orderflow.inventory.infrastructure.events.OrderCancelledPayload;
import org.springframework.stereotype.Service;

@Service
public class OrderCancelledProcessor {

    private final OrderCancellationService cancellation;

    public OrderCancelledProcessor(OrderCancellationService cancellation) {
        this.cancellation = cancellation;
    }

    public void process(EventEnvelope<OrderCancelledPayload> event) {
        Objects.requireNonNull(event, "event");
        var payload = event.payload();
        if (!"OrderCancelled".equals(event.eventType()) || event.eventVersion() != 1
            || payload.orderId() == null || !event.aggregateId().equals(payload.orderId().toString())
            || payload.customerId() == null || payload.customerEmail() == null
            || payload.customerEmail().isBlank()
            || !("PENDING".equals(payload.previousStatus())
                || "CONFIRMED".equals(payload.previousStatus()))) {
            throw new IllegalArgumentException("Invalid OrderCancelled event");
        }
        cancellation.release(event.eventId(), payload.orderId());
    }
}
