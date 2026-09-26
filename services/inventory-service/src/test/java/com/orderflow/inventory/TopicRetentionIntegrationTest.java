package com.orderflow.inventory;

import java.util.List;
import java.util.Map;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AlterConfigOp;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.config.TopicConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaAdmin;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {"inventory.messaging.enabled=true", "spring.kafka.listener.auto-startup=false"})
@Import({PostgresTestConfiguration.class, KafkaTestConfiguration.class})
class TopicRetentionIntegrationTest {

    private static final ConfigResource RESOURCE =
        new ConfigResource(ConfigResource.Type.TOPIC, "inventory.stock-events.v1");

    @Autowired
    private KafkaAdmin kafkaAdmin;

    @Test
    void everyDeclaredTopicHasExplicitRetentionAndDailySegments() throws Exception {
        try (var admin = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
            for (String topic : List.of("orders.order-events.v1", "inventory.stock-events.v1",
                "orders.order-events.v1.inventory-service.DLT")) {
                var resource = new ConfigResource(ConfigResource.Type.TOPIC, topic);
                var config = admin.describeConfigs(List.of(resource)).all().get().get(resource);
                assertExplicit(config.get(TopicConfig.RETENTION_MS_CONFIG), "604800000");
                assertExplicit(config.get(TopicConfig.SEGMENT_MS_CONFIG), "86400000");
            }
        }
    }

    @Test
    void driftedTopicRetentionIsCorrectedOnStartup() throws Exception {
        try (var admin = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
            admin.incrementalAlterConfigs(Map.of(RESOURCE, List.of(new AlterConfigOp(
                new ConfigEntry(TopicConfig.RETENTION_MS_CONFIG, "3600000"), AlterConfigOp.OpType.SET)))).all().get();
            assertThat(retention(admin).value()).isEqualTo("3600000");

            kafkaAdmin.initialize();

            assertThat(retention(admin).value()).isEqualTo("604800000");
        }
    }

    private static void assertExplicit(ConfigEntry entry, String value) {
        assertThat(entry.value()).as(entry.name()).isEqualTo(value);
        assertThat(entry.source()).as(entry.name()).isEqualTo(ConfigEntry.ConfigSource.DYNAMIC_TOPIC_CONFIG);
    }

    private static ConfigEntry retention(AdminClient admin) throws Exception {
        return admin.describeConfigs(List.of(RESOURCE)).all().get().get(RESOURCE).get(TopicConfig.RETENTION_MS_CONFIG);
    }
}
