package com.orderflow.inventory.config;

import com.orderflow.inventory.infrastructure.outbox.InventoryOutboxRelay;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(InventoryOutboxRelayProperties.class)
public class InventoryMessagingConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "inventory.messaging", name = "enabled", havingValue = "true")
    static class EnabledMessagingConfiguration {

        @Bean
        NewTopic orderEventsTopic() {
            return TopicBuilder.name("orders.order-events.v1").partitions(3).replicas(1).build();
        }

        @Bean
        NewTopic stockEventsTopic() {
            return TopicBuilder.name("inventory.stock-events.v1").partitions(3).replicas(1).build();
        }

        @Bean
        RelaySchedule relaySchedule(InventoryOutboxRelay relay) {
            return new RelaySchedule(relay);
        }
    }

    static class RelaySchedule {

        private final InventoryOutboxRelay relay;

        RelaySchedule(InventoryOutboxRelay relay) {
            this.relay = relay;
        }

        @Scheduled(fixedDelay = 500)
        void publish() {
            relay.publishBatch();
        }
    }
}
