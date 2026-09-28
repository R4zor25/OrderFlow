package com.orderflow.inventory.config;

import com.orderflow.inventory.infrastructure.outbox.InventoryOutboxRelay;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerAwareRecordRecoverer;
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
            return KafkaTopics.topic("orders.order-events.v1");
        }

        @Bean
        NewTopic stockEventsTopic() {
            return KafkaTopics.topic("inventory.stock-events.v1");
        }

        @Bean
        NewTopic orderEventsDltTopic() {
            return KafkaTopics.topic(KafkaTopics.deadLetter("orders.order-events.v1"));
        }

        @Bean
        DefaultErrorHandler inventoryErrorHandler(KafkaTemplate<String, String> kafka,
                                                   InventoryConsumerErrorProperties properties,
                                                   MeterRegistry registry) {
            var recoverer = new DeadLetterPublishingRecoverer(kafka,
                (record, exception) -> new TopicPartition(KafkaTopics.deadLetter(record.topic()), record.partition()));
            recoverer.setFailIfSendResultIsError(true);
            recoverer.setWaitForSendResultTimeout(properties.dltSendTimeout());
            // Registered up front so the series exists before the first dead letter.
            Counter deadLetters = registry.counter("messaging.dead.letters", "topic",
                KafkaTopics.deadLetter("orders.order-events.v1"));
            // Consumer-aware, so the recoverer can still check that the DLT has the record's partition;
            // counted after a confirmed send, because a failed publish throws before the increment.
            ConsumerAwareRecordRecoverer counted = (record, consumer, exception) -> {
                recoverer.accept(record, consumer, exception);
                deadLetters.increment();
            };

            var backOff = new ExponentialBackOff(properties.initialInterval().toMillis(), properties.multiplier());
            backOff.setMaxInterval(properties.maxInterval().toMillis());
            // No elapsed-time limit: a database outage pauses the partition instead of dead-lettering a saga step.
            var handler = new DefaultErrorHandler(counted, backOff);
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
