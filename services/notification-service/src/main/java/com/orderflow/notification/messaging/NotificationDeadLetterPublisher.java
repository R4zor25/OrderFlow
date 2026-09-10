package com.orderflow.notification.messaging;

import io.micronaut.configuration.kafka.annotation.KafkaClient;
import io.micronaut.configuration.kafka.annotation.KafkaKey;
import io.micronaut.configuration.kafka.annotation.Topic;
import io.micronaut.context.annotation.Requires;

@KafkaClient(id = "notification-dlt")
@Requires(property = "notification.messaging.enabled", value = "true")
public interface NotificationDeadLetterPublisher {

    void publish(@Topic String topic, @KafkaKey String key, String json);
}
