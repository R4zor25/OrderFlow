package com.orderflow.inventory.infrastructure.health;

import java.time.Duration;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;

import com.orderflow.inventory.config.KafkaHealthProperties;
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
@ConditionalOnProperty(prefix = "inventory.messaging", name = "enabled", havingValue = "true")
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
        var configuration = new HashMap<>(kafkaAdmin.getConfigurationProperties());
        int timeoutMillis = Math.toIntExact(properties.timeout().toMillis());
        configuration.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, timeoutMillis);
        configuration.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, timeoutMillis);
        Admin admin = null;
        try {
            admin = Admin.create(configuration);
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
        finally {
            if (admin != null) {
                admin.close(Duration.ZERO);
            }
        }
    }
}
