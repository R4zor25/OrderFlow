package com.orderflow.order.orders.infrastructure;

import java.util.EnumMap;

import com.orderflow.order.customer.CustomerType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class OrderBusinessMetrics {

    private final EnumMap<CustomerType, Counter> placed = new EnumMap<>(CustomerType.class);
    private final EnumMap<CustomerType, Counter> rejected = new EnumMap<>(CustomerType.class);

    public OrderBusinessMetrics(MeterRegistry registry) {
        for (CustomerType type : CustomerType.values()) {
            placed.put(type, Counter.builder("orders.placed")
                .description("Committed new orders")
                .tag("customerType", type.name())
                .register(registry));
            rejected.put(type, Counter.builder("orders.rejected")
                .description("Committed order rejections from stock reservation failure")
                .tag("customerType", type.name())
                .tag("reason", "stock_reservation_failed")
                .register(registry));
        }
    }

    public void placedAfterCommit(CustomerType type) {
        incrementAfterCommit(placed.get(type));
    }

    public void rejectedAfterCommit(CustomerType type) {
        incrementAfterCommit(rejected.get(type));
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
