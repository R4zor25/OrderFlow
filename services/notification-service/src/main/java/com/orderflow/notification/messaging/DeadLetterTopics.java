package com.orderflow.notification.messaging;

import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;

import java.util.Map;

@Factory
@Requires(property = "notification.messaging.enabled", value = "true")
public class DeadLetterTopics {

    @Bean
    NewTopic orderEventsDeadLetter() {
        return deadLetter(NotificationEventDecoder.ORDER_TOPIC);
    }

    @Bean
    NewTopic stockEventsDeadLetter() {
        return deadLetter(NotificationEventDecoder.STOCK_TOPIC);
    }

    // Same partitions, retention and segments as the Spring-declared topics.
    private static NewTopic deadLetter(String sourceTopic) {
        return new NewTopic(DeadLetterHeaders.topic(sourceTopic), 3, (short) 1)
                .configs(Map.of(TopicConfig.RETENTION_MS_CONFIG, "604800000",
                        TopicConfig.SEGMENT_MS_CONFIG, "86400000"));
    }
}
