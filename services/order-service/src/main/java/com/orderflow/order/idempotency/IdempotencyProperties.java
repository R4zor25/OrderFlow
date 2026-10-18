package com.orderflow.order.idempotency;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "order.idempotency")
public record IdempotencyProperties(Duration ttl, Duration inProgressLease) {

    public IdempotencyProperties {
        if (ttl == null || ttl.compareTo(Duration.ofSeconds(1)) < 0) {
            throw new IllegalArgumentException("Idempotency TTL must be at least one second");
        }
        if (inProgressLease == null || inProgressLease.compareTo(Duration.ofSeconds(1)) < 0
            || inProgressLease.compareTo(ttl) > 0) {
            throw new IllegalArgumentException("Idempotency in-progress lease must be between one second and the TTL");
        }
    }
}
