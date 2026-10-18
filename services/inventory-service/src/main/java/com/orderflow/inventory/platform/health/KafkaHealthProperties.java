package com.orderflow.inventory.platform.health;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("inventory.messaging.health")
public record KafkaHealthProperties(Duration timeout) {
}
