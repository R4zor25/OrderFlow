package com.orderflow.inventory.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "inventory.messaging.error")
public record InventoryConsumerErrorProperties(Duration initialInterval, double multiplier, Duration maxInterval,
                                               Duration dltSendTimeout) {

    public InventoryConsumerErrorProperties {
        if (initialInterval == null || initialInterval.isNegative() || initialInterval.isZero()
            || multiplier < 1.0 || maxInterval == null || maxInterval.compareTo(initialInterval) < 0
            || dltSendTimeout == null || dltSendTimeout.isNegative() || dltSendTimeout.isZero()) {
            throw new IllegalArgumentException("Invalid inventory consumer error settings");
        }
    }
}
