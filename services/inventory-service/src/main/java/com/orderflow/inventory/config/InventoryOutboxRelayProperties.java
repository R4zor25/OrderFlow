package com.orderflow.inventory.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "inventory.outbox.relay")
public record InventoryOutboxRelayProperties(Duration ackTimeout) {
}
