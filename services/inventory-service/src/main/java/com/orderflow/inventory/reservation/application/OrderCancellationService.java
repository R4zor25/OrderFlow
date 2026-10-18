package com.orderflow.inventory.reservation.application;

import java.util.UUID;

import com.orderflow.inventory.messaging.ProcessedEventRepository;
import com.orderflow.inventory.outbox.InventoryOutboxWriter;
import com.orderflow.inventory.reservation.infrastructure.CancelledOrderRepository;
import com.orderflow.inventory.reservation.infrastructure.ReservationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderCancellationService {

    private final ProcessedEventRepository processedEvents;
    private final ReservationRepository reservations;
    private final InventoryOutboxWriter outbox;
    private final CancelledOrderRepository cancelledOrders;

    public OrderCancellationService(ProcessedEventRepository processedEvents,
                                    ReservationRepository reservations, InventoryOutboxWriter outbox,
                                    CancelledOrderRepository cancelledOrders) {
        this.processedEvents = processedEvents;
        this.reservations = reservations;
        this.outbox = outbox;
        this.cancelledOrders = cancelledOrders;
    }

    @Transactional
    public void release(UUID eventId, UUID orderId) {
        if (!processedEvents.markIfNew(eventId)) {
            return;
        }
        cancelledOrders.remember(orderId);
        if (reservations.release(orderId)) {
            outbox.released(orderId);
        }
    }
}
