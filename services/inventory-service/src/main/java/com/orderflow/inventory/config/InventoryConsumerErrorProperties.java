package com.orderflow.inventory.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "inventory.messaging.error")
public record InventoryConsumerErrorProperties(Duration retryInterval, long retryCount,
                                               Duration dltSendTimeout) {

    public InventoryConsumerErrorProperties {
        if (retryInterval == null || retryInterval.isNegative() || retryCount < 0
            || dltSendTimeout == null || dltSendTimeout.isNegative() || dltSendTimeout.isZero()) {
            throw new IllegalArgumentException("Invalid inventory consumer error settings");
        }
    }
}
