package com.orderflow.inventory.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("inventory.messaging.health")
public record KafkaHealthProperties(Duration timeout) {
}
