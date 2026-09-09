package com.orderflow.inventory.application;

import java.util.UUID;

import com.orderflow.inventory.infrastructure.events.ProcessedEventRepository;
import com.orderflow.inventory.infrastructure.outbox.InventoryOutboxWriter;
import com.orderflow.inventory.reservation.infrastructure.ReservationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderCancellationService {

    private final ProcessedEventRepository processedEvents;
    private final ReservationRepository reservations;
    private final InventoryOutboxWriter outbox;

    public OrderCancellationService(ProcessedEventRepository processedEvents,
                                    ReservationRepository reservations, InventoryOutboxWriter outbox) {
        this.processedEvents = processedEvents;
        this.reservations = reservations;
        this.outbox = outbox;
    }

    @Transactional
    public void release(UUID eventId, UUID orderId) {
        if (!processedEvents.markIfNew(eventId)) {
            return;
        }
        if (reservations.release(orderId)) {
            outbox.released(orderId);
        }
    }
}
