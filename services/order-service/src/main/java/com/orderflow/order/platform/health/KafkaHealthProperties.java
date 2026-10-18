package com.orderflow.order.platform.health;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("order.health.kafka")
public record KafkaHealthProperties(Duration timeout) {

    public KafkaHealthProperties {
        if (timeout == null || timeout.toMillis() < 1 || timeout.toMillis() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Kafka health timeout must be between 1ms and 2147483647ms");
        }
    }
}
