package com.orderflow.order;

import java.util.List;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.config.TopicConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaAdmin;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {"order.messaging.enabled=true", "order.outbox.relay.enabled=true",
    "spring.kafka.listener.auto-startup=false"})
@Import({PostgresTestConfiguration.class, RedisTestConfiguration.class, KafkaTestConfiguration.class})
class TopicRetentionIntegrationTest {

    @Autowired
    private KafkaAdmin kafkaAdmin;

    @Test
    void everyDeclaredTopicHasExplicitRetentionAndDailySegments() throws Exception {
        try (var admin = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
            for (String topic : List.of("orders.order-events.v1", "inventory.stock-events.v1",
                "inventory.stock-events.v1.DLT")) {
                var resource = new ConfigResource(ConfigResource.Type.TOPIC, topic);
                var config = admin.describeConfigs(List.of(resource)).all().get().get(resource);
                assertExplicit(config.get(TopicConfig.RETENTION_MS_CONFIG), "604800000");
                assertExplicit(config.get(TopicConfig.SEGMENT_MS_CONFIG), "86400000");
            }
        }
    }

    private static void assertExplicit(ConfigEntry entry, String value) {
        assertThat(entry.value()).as(entry.name()).isEqualTo(value);
        assertThat(entry.source()).as(entry.name()).isEqualTo(ConfigEntry.ConfigSource.DYNAMIC_TOPIC_CONFIG);
    }
}
