package com.orderflow.inventory.outbox;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "inventory.outbox.relay")
public record InventoryOutboxRelayProperties(Duration ackTimeout) {
}
