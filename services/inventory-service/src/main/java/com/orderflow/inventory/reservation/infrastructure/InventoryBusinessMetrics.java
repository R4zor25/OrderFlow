package com.orderflow.inventory.reservation.infrastructure;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class InventoryBusinessMetrics {

    private final Counter insufficientStock;
    private final Counter invalidOrder;

    public InventoryBusinessMetrics(MeterRegistry registry) {
        this.insufficientStock = reservationFailed(registry, "insufficient_stock");
        this.invalidOrder = reservationFailed(registry, "invalid_order");
    }

    public void reservationFailedAfterCommit() {
        incrementAfterCommit(insufficientStock);
    }

    public void invalidOrderAfterCommit() {
        incrementAfterCommit(invalidOrder);
    }

    private static Counter reservationFailed(MeterRegistry registry, String reason) {
        return Counter.builder("inventory.reservation.failed")
            .description("Committed stock reservation failures")
            .tag("reason", reason)
            .register(registry);
    }

    private static void incrementAfterCommit(Counter counter) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Business metrics require an active transaction");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                counter.increment();
            }
        });
    }
}
