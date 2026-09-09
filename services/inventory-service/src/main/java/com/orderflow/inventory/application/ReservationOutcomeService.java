package com.orderflow.inventory.application;

import java.util.List;
import java.util.UUID;

import com.orderflow.inventory.infrastructure.events.ProcessedEventRepository;
import com.orderflow.inventory.infrastructure.outbox.InventoryOutboxWriter;
import com.orderflow.inventory.reservation.application.ReservationService;
import com.orderflow.inventory.reservation.domain.ReservationLine;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReservationOutcomeService {

    private final ProcessedEventRepository processedEvents;
    private final ReservationService reservations;
    private final InventoryOutboxWriter outbox;

    public ReservationOutcomeService(ProcessedEventRepository processedEvents,
                                     ReservationService reservations, InventoryOutboxWriter outbox) {
        this.processedEvents = processedEvents;
        this.reservations = reservations;
        this.outbox = outbox;
    }

    @Transactional
    public void reserve(UUID eventId, UUID orderId, List<ReservationLine> lines) {
        if (!processedEvents.markIfNew(eventId)) {
            return;
        }
        reservations.reserve(orderId, lines);
        outbox.reserved(orderId, lines);
    }

    @Transactional
    public void recordFailure(UUID eventId, UUID orderId, String unavailableSku) {
        if (!processedEvents.markIfNew(eventId)) {
            return;
        }
        outbox.failed(orderId, unavailableSku);
    }
}
