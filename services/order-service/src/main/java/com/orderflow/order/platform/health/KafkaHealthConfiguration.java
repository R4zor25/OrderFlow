package com.orderflow.order.platform.health;

import java.util.HashMap;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaAdmin;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "order.messaging", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(KafkaHealthProperties.class)
public class KafkaHealthConfiguration {

    // One long-lived client: creating one per probe opened a connection and logged its whole config each time.
    @Bean(destroyMethod = "close")
    Admin kafkaHealthAdmin(KafkaAdmin kafkaAdmin, KafkaHealthProperties properties) {
        int timeoutMillis = Math.toIntExact(properties.timeout().toMillis());
        var configuration = new HashMap<>(kafkaAdmin.getConfigurationProperties());
        configuration.put(AdminClientConfig.CLIENT_ID_CONFIG, "order-service-health");
        configuration.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, timeoutMillis);
        configuration.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, timeoutMillis);
        return Admin.create(configuration);
    }
}
