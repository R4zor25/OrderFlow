package com.orderflow.order.messaging;

import com.orderflow.order.orders.domain.InvalidOrderStateException;
import com.orderflow.order.orders.domain.OrderNotFoundException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerAwareRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;
import tools.jackson.core.JacksonException;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OrderConsumerErrorProperties.class)
public class OrderMessagingConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "order.messaging", name = "enabled", havingValue = "true")
    static class EnabledMessagingConfiguration {

        @Bean
        NewTopic stockEventsTopic() {
            return KafkaTopics.topic("inventory.stock-events.v1");
        }

        @Bean
        NewTopic stockEventsDltTopic() {
            return KafkaTopics.topic(KafkaTopics.deadLetter("inventory.stock-events.v1"));
        }

        @Bean
        DefaultErrorHandler orderConsumerErrorHandler(KafkaTemplate<String, String> kafka,
                                                       OrderConsumerErrorProperties properties,
                                                       MeterRegistry registry) {
            var recoverer = new DeadLetterPublishingRecoverer(kafka,
                (record, exception) -> new TopicPartition(KafkaTopics.deadLetter(record.topic()), record.partition()));
            recoverer.setFailIfSendResultIsError(true);
            recoverer.setWaitForSendResultTimeout(properties.dltSendTimeout());
            // Registered up front so the series exists before the first dead letter.
            Counter deadLetters = registry.counter("messaging.dead.letters", "topic",
                KafkaTopics.deadLetter("inventory.stock-events.v1"));
            // Consumer-aware, so the recoverer can still check that the DLT has the record's partition;
            // counted after a confirmed send, because a failed publish throws before the increment.
            ConsumerAwareRecordRecoverer counted = (record, consumer, exception) -> {
                recoverer.accept(record, consumer, exception);
                deadLetters.increment();
            };

            var handler = new DefaultErrorHandler(counted,
                new FixedBackOff(properties.retryInterval().toMillis(), properties.retryCount()));
            // Unknown orders and impossible transitions give the same result on every retry.
            handler.addNotRetryableExceptions(IllegalArgumentException.class, JacksonException.class,
                OrderNotFoundException.class, InvalidOrderStateException.class);
            return handler;
        }
    }
}
