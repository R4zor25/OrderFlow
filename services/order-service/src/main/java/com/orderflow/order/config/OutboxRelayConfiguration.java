package com.orderflow.order.config;

import com.orderflow.order.application.OutboxRelay;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OutboxRelayProperties.class)
public class OutboxRelayConfiguration {

    // Seven days; processed_event and cancelled_order are kept twice as long, so a replay never outlives deduplication.
    private static final String TOPIC_RETENTION_MS = "604800000";

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "order.outbox.relay", name = "enabled", havingValue = "true")
    static class EnabledRelayConfiguration {

        @Bean
        NewTopic orderEventsTopic() {
            return TopicBuilder.name("orders.order-events.v1").partitions(3).replicas(1)
                .config(TopicConfig.RETENTION_MS_CONFIG, TOPIC_RETENTION_MS).build();
        }

        @Bean
        RelaySchedule relaySchedule(OutboxRelay relay) {
            return new RelaySchedule(relay);
        }
    }

    static class RelaySchedule {

        private final OutboxRelay relay;

        RelaySchedule(OutboxRelay relay) {
            this.relay = relay;
        }

        @Scheduled(fixedDelay = 500)
        void publish() {
            relay.publishBatch();
        }
    }
}
