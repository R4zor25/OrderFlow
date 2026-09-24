package com.orderflow.order.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
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
            return KafkaTopics.topic("inventory.stock-events.v1.DLT");
        }

        @Bean
        DefaultErrorHandler orderConsumerErrorHandler(KafkaTemplate<String, String> kafka,
                                                       OrderConsumerErrorProperties properties) {
            var recoverer = new DeadLetterPublishingRecoverer(kafka,
                (record, exception) -> new TopicPartition(record.topic() + ".DLT", record.partition()));
            recoverer.setFailIfSendResultIsError(true);
            recoverer.setWaitForSendResultTimeout(properties.dltSendTimeout());

            var handler = new DefaultErrorHandler(recoverer,
                new FixedBackOff(properties.retryInterval().toMillis(), properties.retryCount()));
            handler.addNotRetryableExceptions(IllegalArgumentException.class, JacksonException.class);
            return handler;
        }
    }
}
