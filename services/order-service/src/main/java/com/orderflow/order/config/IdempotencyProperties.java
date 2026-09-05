package com.orderflow.order.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "order.idempotency")
public record IdempotencyProperties(Duration ttl) {

    public IdempotencyProperties {
        if (ttl == null || ttl.compareTo(Duration.ofSeconds(1)) < 0) {
            throw new IllegalArgumentException("Idempotency TTL must be at least one second");
        }
    }
}
