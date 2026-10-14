package com.orderflow.inventory.application;

import java.util.List;
import java.util.UUID;

import com.orderflow.inventory.infrastructure.events.CancelledOrderRepository;
import com.orderflow.inventory.infrastructure.events.ProcessedEventRepository;
import com.orderflow.inventory.infrastructure.metrics.InventoryBusinessMetrics;
import com.orderflow.inventory.infrastructure.outbox.InventoryOutboxWriter;
import com.orderflow.inventory.reservation.application.ReservationService;
import com.orderflow.inventory.reservation.domain.ReservationLine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReservationOutcomeService {

    private final ProcessedEventRepository processedEvents;
    private final ReservationService reservations;
    private final InventoryOutboxWriter outbox;
    private final InventoryBusinessMetrics metrics;
    private final CancelledOrderRepository cancelledOrders;
    private static final Logger log = LoggerFactory.getLogger(ReservationOutcomeService.class);

    public ReservationOutcomeService(ProcessedEventRepository processedEvents,
                                     ReservationService reservations, InventoryOutboxWriter outbox,
                                     InventoryBusinessMetrics metrics, CancelledOrderRepository cancelledOrders) {
        this.processedEvents = processedEvents;
        this.reservations = reservations;
        this.outbox = outbox;
        this.metrics = metrics;
        this.cancelledOrders = cancelledOrders;
    }

    @Transactional
    public void reserve(UUID eventId, UUID orderId, List<ReservationLine> lines) {
        if (!processedEvents.markIfNew(eventId) || cancelled(orderId)) {
            return;
        }
        outbox.reserved(orderId, reservations.reserve(orderId, lines));
    }

    @Transactional
    public void recordFailure(UUID eventId, UUID orderId, String unavailableSku) {
        if (!processedEvents.markIfNew(eventId) || cancelled(orderId)) {
            return;
        }
        outbox.failed(orderId, "Insufficient stock", List.of(unavailableSku));
        metrics.reservationFailedAfterCommit();
    }

    @Transactional
    public void rejectInvalid(UUID eventId, UUID orderId, List<String> invalidSkus) {
        if (!processedEvents.markIfNew(eventId) || cancelled(orderId)) {
            return;
        }
        outbox.failed(orderId, "Invalid order", invalidSkus);
        metrics.invalidOrderAfterCommit();
        log.warn("Rejected invalid OrderPlaced eventId={} orderId={}", eventId, orderId);
    }

    private boolean cancelled(UUID orderId) {
        if (!cancelledOrders.isCancelled(orderId)) {
            return false;
        }
        log.info("Skipping reservation for cancelled order orderId={}", orderId);
        return true;
    }
}
