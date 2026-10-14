package com.orderflow.inventory.application;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

import com.orderflow.inventory.infrastructure.events.EventEnvelope;
import com.orderflow.inventory.infrastructure.events.OrderPlacedPayload;
import com.orderflow.inventory.reservation.domain.ReservationLine;
import com.orderflow.inventory.reservation.domain.StockUnavailableException;
import org.springframework.stereotype.Service;

@Service
public class OrderPlacedProcessor {

    private static final int MAX_SKU_LENGTH = 32;

    private final ReservationOutcomeService outcomes;

    public OrderPlacedProcessor(ReservationOutcomeService outcomes) {
        this.outcomes = outcomes;
    }

    public void process(EventEnvelope<OrderPlacedPayload> event) {
        var payload = event.payload();
        var invalidSkus = invalidSkus(payload);
        if (invalidSkus.isPresent()) {
            // An identifiable order gets a business answer; a dead letter would leave it pending.
            outcomes.rejectInvalid(event.eventId(), payload.orderId(), invalidSkus.get());
            return;
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

    private static Optional<List<String>> invalidSkus(OrderPlacedPayload payload) {
        if (payload.customerId() == null || payload.customerType() == null
            || payload.lines() == null || payload.lines().isEmpty()) {
            return Optional.of(List.of());
        }
        var invalid = new LinkedHashSet<String>();
        var totals = new HashMap<String, Integer>();
        boolean anyInvalid = false;
        for (var line : payload.lines()) {
            if (line == null || line.sku() == null || line.sku().isBlank()
                || line.sku().length() > MAX_SKU_LENGTH || line.quantity() <= 0) {
                anyInvalid = true;
                if (line != null && line.sku() != null && !line.sku().isBlank()) {
                    invalid.add(line.sku().substring(0, Math.min(line.sku().length(), MAX_SKU_LENGTH)));
                }
                continue;
            }
            try {
                totals.merge(line.sku(), line.quantity(), Math::addExact);
            } catch (ArithmeticException overflow) {
                anyInvalid = true;
                invalid.add(line.sku());
            }
        }
        return anyInvalid ? Optional.of(List.copyOf(invalid)) : Optional.empty();
    }
}
