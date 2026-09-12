package com.orderflow.inventory.infrastructure.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class InventoryBusinessMetrics {

    private final Counter reservationFailed;

    public InventoryBusinessMetrics(MeterRegistry registry) {
        this.reservationFailed = Counter.builder("inventory.reservation.failed")
            .description("Committed stock reservation failures")
            .tag("reason", "insufficient_stock")
            .register(registry);
    }

    public void reservationFailedAfterCommit() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Business metrics require an active transaction");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                reservationFailed.increment();
            }
        });
    }
}
