package com.orderflow.order.config;

import com.orderflow.order.application.OutboxRelay;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OutboxRelayProperties.class)
public class OutboxRelayConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "order.outbox.relay", name = "enabled", havingValue = "true")
    static class EnabledRelayConfiguration {

        @Bean
        NewTopic orderEventsTopic() {
            return KafkaTopics.topic("orders.order-events.v1");
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
