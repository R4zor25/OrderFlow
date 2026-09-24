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
    void topicsAreCreatedWithExplicitSevenDayRetention() throws Exception {
        try (var admin = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
            ConfigEntry retention = retention(admin);
            assertThat(retention.value()).isEqualTo("604800000");
            assertThat(retention.source()).isEqualTo(ConfigEntry.ConfigSource.DYNAMIC_TOPIC_CONFIG);
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

    private static ConfigEntry retention(AdminClient admin) throws Exception {
        return admin.describeConfigs(List.of(RESOURCE)).all().get().get(RESOURCE).get(TopicConfig.RETENTION_MS_CONFIG);
    }
}
