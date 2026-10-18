package com.orderflow.order.orders.infrastructure;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "order.saga")
public record OrderSagaProperties(Duration pendingTimeout) {

    public OrderSagaProperties {
        if (pendingTimeout == null || pendingTimeout.isNegative() || pendingTimeout.isZero()) {
            throw new IllegalArgumentException("order.saga.pending-timeout must be positive");
        }
    }
}
