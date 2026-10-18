package com.orderflow.notification.messaging;

import com.orderflow.notification.messaging.deadletter.DeadLetters;
import io.micronaut.configuration.kafka.exceptions.DefaultKafkaListenerExceptionHandler;
import io.micronaut.configuration.kafka.exceptions.KafkaListenerException;
import io.micronaut.configuration.kafka.exceptions.KafkaListenerExceptionHandler;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import org.apache.kafka.clients.consumer.CommitFailedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
@Replaces(DefaultKafkaListenerExceptionHandler.class)
@Requires(property = "notification.messaging.enabled", value = "true")
public final class NotificationListenerExceptionHandler implements KafkaListenerExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(NotificationListenerExceptionHandler.class);

    private final DeadLetters deadLetters;

    public NotificationListenerExceptionHandler(DeadLetters deadLetters) {
        this.deadLetters = deadLetters;
    }

    @Override
    public void handle(KafkaListenerException exception) {
        if (exception.getCause() instanceof CommitFailedException) {
            log.error("Notification Kafka offset commit failed; event may be redelivered");
            return;
        }
        var failedRecord = exception.getConsumerRecord();
        if (failedRecord.isEmpty() || !(exception.getKafkaListener() instanceof NotificationEventListener)) {
            log.error("Notification Kafka listener failed without a recoverable record; cause={}",
                    causeName(exception));
            return;
        }
        var record = failedRecord.orElseThrow();
        if (!(record.key() instanceof String key) || !(record.value() instanceof String json)) {
            log.error("Notification Kafka record has an unexpected key or value type at topic={}, offset={}",
                    record.topic(), record.offset());
            return;
        }
        try {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            deadLetters.send(record, key, json, cause);
        } catch (RuntimeException publishFailure) {
            // The framework commits the record after this handler returns, so the log is its only trace.
            log.error("Notification Kafka record skipped without a DLT copy at topic={}, partition={}, offset={}, "
                    + "cause={}", record.topic(), record.partition(), record.offset(),
                    publishFailure.getClass().getSimpleName());
            return;
        }
        log.error("Notification Kafka record moved to DLT after retries at topic={}, partition={}, offset={}, cause={}",
                record.topic(), record.partition(), record.offset(), causeName(exception));
    }

    private static String causeName(KafkaListenerException exception) {
        return exception.getCause() == null ? "unknown" : exception.getCause().getClass().getSimpleName();
    }
}
