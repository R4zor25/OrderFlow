package com.orderflow.notification.messaging;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.inject.Singleton;

import java.util.Map;

@Singleton
public class DeadLetterMetrics {

    private final Map<String, Counter> counters;

    // Registered up front so the series exist before the first dead letter.
    public DeadLetterMetrics(MeterRegistry registry) {
        var orderDlt = DeadLetterHeaders.topic(NotificationEventDecoder.ORDER_TOPIC);
        var stockDlt = DeadLetterHeaders.topic(NotificationEventDecoder.STOCK_TOPIC);
        counters = Map.of(orderDlt, registry.counter("messaging.dead.letters", "topic", orderDlt),
                stockDlt, registry.counter("messaging.dead.letters", "topic", stockDlt));
    }

    void published(String deadLetterTopic) {
        var counter = counters.get(deadLetterTopic);
        if (counter != null) {
            counter.increment();
        }
    }
}
