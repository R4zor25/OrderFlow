package com.orderflow.order.infrastructure.health;

import java.util.concurrent.TimeUnit;

import com.orderflow.order.config.KafkaHealthProperties;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.DescribeClusterOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("kafkaHealthIndicator")
@ConditionalOnProperty(prefix = "order.messaging", name = "enabled", havingValue = "true")
public class KafkaHealthIndicator implements HealthIndicator {

    private final Admin admin;
    private final KafkaHealthProperties properties;

    public KafkaHealthIndicator(Admin admin, KafkaHealthProperties properties) {
        this.admin = admin;
        this.properties = properties;
    }

    @Override
    public Health health() {
        int timeoutMillis = Math.toIntExact(properties.timeout().toMillis());
        try {
            var nodes = admin.describeCluster(new DescribeClusterOptions().timeoutMs(timeoutMillis))
                .nodes().get(timeoutMillis, TimeUnit.MILLISECONDS);
            return nodes.isEmpty() ? Health.down().build() : Health.up().build();
        }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return Health.down(exception).build();
        }
        catch (Exception exception) {
            return Health.down(exception).build();
        }
    }
}
