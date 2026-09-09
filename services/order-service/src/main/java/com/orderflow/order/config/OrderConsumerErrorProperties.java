package com.orderflow.order.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "order.messaging.error")
public record OrderConsumerErrorProperties(Duration retryInterval, long retryCount,
                                           Duration dltSendTimeout) {

    public OrderConsumerErrorProperties {
        if (retryInterval == null || retryInterval.isNegative() || retryCount < 0
            || dltSendTimeout == null || dltSendTimeout.isNegative() || dltSendTimeout.isZero()) {
            throw new IllegalArgumentException("Invalid order consumer error settings");
        }
    }
}
