package com.orderflow.notification.messaging;

import com.orderflow.notification.application.NotificationProcessor;
import io.micronaut.configuration.kafka.annotation.ErrorStrategy;
import io.micronaut.configuration.kafka.annotation.ErrorStrategyValue;
import io.micronaut.configuration.kafka.annotation.KafkaListener;
import io.micronaut.configuration.kafka.annotation.OffsetStrategy;
import io.micronaut.configuration.kafka.annotation.Topic;
import io.micronaut.context.annotation.Requires;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@KafkaListener(groupId = "notification-service", offsetStrategy = OffsetStrategy.SYNC_PER_RECORD,
        errorStrategy = @ErrorStrategy(value = ErrorStrategyValue.RETRY_ON_ERROR,
                retryCount = 3, retryDelay = "1s", stopOnExhaustedRetry = true))
@Requires(property = "notification.messaging.enabled", value = "true")
public final class NotificationEventListener {

    private static final Logger log = LoggerFactory.getLogger(NotificationEventListener.class);

    private final NotificationEventDecoder decoder;
    private final NotificationDeadLetterPublisher deadLetterPublisher;
    private final NotificationProcessor processor;

    public NotificationEventListener(NotificationEventDecoder decoder,
                                     NotificationDeadLetterPublisher deadLetterPublisher,
                                     NotificationProcessor processor) {
        this.decoder = decoder;
        this.deadLetterPublisher = deadLetterPublisher;
        this.processor = processor;
    }

    @Topic({NotificationEventDecoder.ORDER_TOPIC, NotificationEventDecoder.STOCK_TOPIC})
    public void onEvent(ConsumerRecord<String, String> record) {
        try {
            var decoded = decoder.decode(record.topic(), record.key(), record.value());
            if (decoded.isEmpty()) {
                log.info("Skipping unknown event type at topic={}, partition={}, offset={}",
                        record.topic(), record.partition(), record.offset());
                return;
            }
            NotificationEvent event = decoded.orElseThrow();
            processor.process(event);
        } catch (InvalidNotificationEventException invalid) {
            deadLetterPublisher.publish(record.topic() + ".DLT", record.key(), record.value());
            log.warn("Invalid notification event moved to DLT at topic={}, partition={}, offset={}",
                    record.topic(), record.partition(), record.offset());
        }
    }
}
