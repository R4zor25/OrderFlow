package com.orderflow.inventory.config;

import com.orderflow.inventory.infrastructure.outbox.InventoryOutboxRelay;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.util.backoff.ExponentialBackOff;
import tools.jackson.core.JacksonException;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({InventoryOutboxRelayProperties.class, InventoryConsumerErrorProperties.class})
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
        NewTopic orderEventsDltTopic() {
            return TopicBuilder.name("orders.order-events.v1.DLT").partitions(3).replicas(1).build();
        }

        @Bean
        DefaultErrorHandler inventoryErrorHandler(KafkaTemplate<String, String> kafka,
                                                   InventoryConsumerErrorProperties properties) {
            var recoverer = new DeadLetterPublishingRecoverer(kafka,
                (record, exception) -> new TopicPartition(record.topic() + ".DLT", record.partition()));
            recoverer.setFailIfSendResultIsError(true);
            recoverer.setWaitForSendResultTimeout(properties.dltSendTimeout());

            var backOff = new ExponentialBackOff(properties.initialInterval().toMillis(), properties.multiplier());
            backOff.setMaxInterval(properties.maxInterval().toMillis());
            // No elapsed-time limit: a database outage pauses the partition instead of dead-lettering a saga step.
            var handler = new DefaultErrorHandler(recoverer, backOff);
            handler.addNotRetryableExceptions(IllegalArgumentException.class, JacksonException.class);
            return handler;
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
