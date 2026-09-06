package com.orderflow.order.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "order.outbox.relay")
public record OutboxRelayProperties(boolean enabled, Duration ackTimeout) {
}
