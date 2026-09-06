package com.orderflow.order.infrastructure.outbox;

import java.time.Instant;
import java.util.UUID;

import com.orderflow.order.domain.Order;
import com.orderflow.order.domain.OrderStatus;
import com.orderflow.order.infrastructure.events.EventEnvelope;
import com.orderflow.order.infrastructure.events.OrderCancelledPayload;
import com.orderflow.order.infrastructure.events.OrderConfirmedPayload;
import com.orderflow.order.infrastructure.events.OrderPlacedPayload;
import com.orderflow.order.infrastructure.events.OrderRejectedPayload;
import org.springframework.stereotype.Component;

@Component
public class OrderOutboxWriter {

    private final OutboxWriter outbox;

    public OrderOutboxWriter(OutboxWriter outbox) {
        this.outbox = outbox;
    }

    public void placed(Order order, Instant now) {
        var lines = order.getLines().stream()
            .map(line -> new OrderPlacedPayload.Line(line.getSku(), line.getQuantity()))
            .toList();
        write(order, "OrderPlaced", new OrderPlacedPayload(order.getId(), order.getCustomer().getId(),
            order.getCustomer().getType(), lines), now);
    }

    public void confirmed(Order order, Instant now) {
        write(order, "OrderConfirmed", new OrderConfirmedPayload(order.getId(), order.getCustomer().getId(),
            order.getCustomer().getEmail(), order.getTotalGross(), order.getCurrency()), now);
    }

    public void rejected(Order order, Instant now) {
        write(order, "OrderRejected", new OrderRejectedPayload(order.getId(), order.getCustomer().getId(),
            order.getCustomer().getEmail(), order.getRejectionReason()), now);
    }

    public void cancelled(Order order, OrderStatus previousStatus, Instant now) {
        write(order, "OrderCancelled", new OrderCancelledPayload(order.getId(), order.getCustomer().getId(),
            order.getCustomer().getEmail(), previousStatus), now);
    }

    private void write(Order order, String eventType, Object payload, Instant now) {
        var message = new EventEnvelope<>(UUID.randomUUID(), eventType, 1, now,
            order.getId().toString(), payload);
        outbox.write(new OutboxEvent("Order", order.getId(), message));
    }
}
