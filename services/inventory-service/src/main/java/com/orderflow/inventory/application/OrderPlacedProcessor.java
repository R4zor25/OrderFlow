package com.orderflow.inventory.application;

import java.util.Objects;

import com.orderflow.inventory.infrastructure.events.EventEnvelope;
import com.orderflow.inventory.infrastructure.events.OrderPlacedPayload;
import com.orderflow.inventory.reservation.domain.ReservationLine;
import com.orderflow.inventory.reservation.domain.StockUnavailableException;
import org.springframework.stereotype.Service;

@Service
public class OrderPlacedProcessor {

    private final ReservationOutcomeService outcomes;

    public OrderPlacedProcessor(ReservationOutcomeService outcomes) {
        this.outcomes = outcomes;
    }

    public void process(EventEnvelope<OrderPlacedPayload> event) {
        Objects.requireNonNull(event, "event");
        var payload = event.payload();
        if (!"OrderPlaced".equals(event.eventType()) || event.eventVersion() != 1
            || payload.orderId() == null || !event.aggregateId().equals(payload.orderId().toString())
            || payload.customerId() == null || payload.customerType() == null
            || payload.lines() == null || payload.lines().isEmpty()) {
            throw new IllegalArgumentException("Invalid OrderPlaced event");
        }
        var lines = payload.lines().stream()
            .map(line -> new ReservationLine(line.sku(), line.quantity()))
            .toList();

        try {
            outcomes.reserve(event.eventId(), payload.orderId(), lines);
        } catch (StockUnavailableException unavailable) {
            outcomes.recordFailure(event.eventId(), payload.orderId(), unavailable.sku());
        }
    }
}
