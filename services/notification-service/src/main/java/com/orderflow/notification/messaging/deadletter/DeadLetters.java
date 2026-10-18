package com.orderflow.notification.messaging.deadletter;

import com.orderflow.notification.messaging.NotificationEventDecoder;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import org.apache.kafka.clients.consumer.ConsumerRecord;

import java.util.Map;

@Singleton
@Requires(property = "notification.messaging.enabled", value = "true")
public class DeadLetters {

    private final NotificationDeadLetterPublisher publisher;
    private final Map<String, Counter> counters;

    // Registered up front so the series exist before the first dead letter.
    public DeadLetters(NotificationDeadLetterPublisher publisher, MeterRegistry registry) {
        this.publisher = publisher;
        var orderDlt = DeadLetterHeaders.topic(NotificationEventDecoder.ORDER_TOPIC);
        var stockDlt = DeadLetterHeaders.topic(NotificationEventDecoder.STOCK_TOPIC);
        counters = Map.of(orderDlt, registry.counter("messaging.dead.letters", "topic", orderDlt),
                stockDlt, registry.counter("messaging.dead.letters", "topic", stockDlt));
    }

    // A failed publish throws before the counter, so only confirmed dead letters are counted.
    public void send(ConsumerRecord<?, ?> record, String key, String value, Throwable cause) {
        String deadLetterTopic = DeadLetterHeaders.topic(record.topic());
        publisher.publish(deadLetterTopic, key, value,
                DeadLetterHeaders.of(record.topic(), record.partition(), record.offset(), cause));
        var counter = counters.get(deadLetterTopic);
        if (counter != null) {
            counter.increment();
        }
    }
}
