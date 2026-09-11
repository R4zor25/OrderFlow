package com.orderflow.order.config;

import java.time.Duration;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.DescribeClusterOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;

@Component("kafkaHealthIndicator")
@ConditionalOnProperty(prefix = "order.messaging", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(KafkaHealthProperties.class)
public class KafkaHealthIndicator implements HealthIndicator {

    private final KafkaAdmin kafkaAdmin;
    private final KafkaHealthProperties properties;

    public KafkaHealthIndicator(KafkaAdmin kafkaAdmin, KafkaHealthProperties properties) {
        this.kafkaAdmin = kafkaAdmin;
        this.properties = properties;
    }

    @Override
    public Health health() {
        Admin admin = null;
        try {
            int timeoutMillis = Math.toIntExact(properties.timeout().toMillis());
            var configuration = new HashMap<>(kafkaAdmin.getConfigurationProperties());
            configuration.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, timeoutMillis);
            configuration.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, timeoutMillis);
            admin = Admin.create(configuration);
            var nodes = admin.describeCluster(new DescribeClusterOptions().timeoutMs(timeoutMillis))
                .nodes().get(timeoutMillis, TimeUnit.MILLISECONDS);
            return nodes.isEmpty() ? Health.down().build() : Health.up().build();
        }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return Health.down().build();
        }
        catch (Exception exception) {
            return Health.down().build();
        }
        finally {
            if (admin != null) {
                admin.close(Duration.ZERO);
            }
        }
    }
}
