package com.orderflow.notification.messaging;

import com.orderflow.notification.application.NotificationProcessor;
import io.micronaut.configuration.kafka.annotation.ErrorStrategy;
import io.micronaut.configuration.kafka.annotation.ErrorStrategyValue;
import io.micronaut.configuration.kafka.annotation.KafkaListener;
import io.micronaut.configuration.kafka.annotation.OffsetStrategy;
import io.micronaut.configuration.kafka.annotation.Topic;
import io.micronaut.context.annotation.Requires;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;

import java.nio.charset.StandardCharsets;
import java.util.stream.StreamSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@KafkaListener(groupId = "notification-service", offsetStrategy = OffsetStrategy.SYNC_PER_RECORD,
        errorStrategy = @ErrorStrategy(value = ErrorStrategyValue.RETRY_EXPONENTIALLY_ON_ERROR,
                retryDelay = "${notification.messaging.retry-delay:1s}",
                retryCountValue = "${notification.messaging.retry-count:6}"))
@Requires(property = "notification.messaging.enabled", value = "true")
public final class NotificationEventListener {

    private static final Logger log = LoggerFactory.getLogger(NotificationEventListener.class);

    private final NotificationEventDecoder decoder;
    private final NotificationDeadLetterPublisher deadLetterPublisher;
    private final NotificationProcessor processor;
    private final OpenTelemetry openTelemetry;

    private static final TextMapGetter<ConsumerRecord<String, String>> HEADERS = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(ConsumerRecord<String, String> record) {
            return StreamSupport.stream(record.headers().spliterator(), false)
                    .map(header -> header.key()).toList();
        }

        @Override
        public String get(ConsumerRecord<String, String> record, String key) {
            var header = record.headers().lastHeader(key);
            return header == null || header.value() == null ? null
                    : new String(header.value(), StandardCharsets.UTF_8);
        }
    };

    public NotificationEventListener(NotificationEventDecoder decoder,
                                     NotificationDeadLetterPublisher deadLetterPublisher,
                                     NotificationProcessor processor, OpenTelemetry openTelemetry) {
        this.decoder = decoder;
        this.deadLetterPublisher = deadLetterPublisher;
        this.processor = processor;
        this.openTelemetry = openTelemetry;
    }

    @Topic({NotificationEventDecoder.ORDER_TOPIC, NotificationEventDecoder.STOCK_TOPIC})
    public void onEvent(ConsumerRecord<String, String> record) {
        var parent = openTelemetry.getPropagators().getTextMapPropagator()
                .extract(Context.root(), record, HEADERS);
        var span = openTelemetry.getTracer("com.orderflow.notification")
                .spanBuilder(record.topic() + " process")
                .setParent(parent)
                .setSpanKind(SpanKind.CONSUMER)
                .setAttribute("messaging.system", "kafka")
                .setAttribute("messaging.destination.name", record.topic())
                .setAttribute("messaging.consumer.group.name", "notification-service")
                .startSpan();
        // Micronaut's Kafka poll instrumentation ends before the listener runs.
        try (var scope = span.makeCurrent()) {
            processRecord(record);
        } catch (RuntimeException failure) {
            span.setStatus(StatusCode.ERROR);
            throw failure;
        } finally {
            span.end();
        }
    }

    private void processRecord(ConsumerRecord<String, String> record) {
        try {
            var decoded = decoder.decode(record.topic(), record.key(), record.value());
            if (decoded.isEmpty()) {
                log.warn("Skipping unsupported event type or version at topic={}, partition={}, offset={}",
                        record.topic(), record.partition(), record.offset());
                return;
            }
            NotificationEvent event = decoded.orElseThrow();
            processor.process(event);
        } catch (InvalidNotificationEventException invalid) {
            deadLetterPublisher.publish(DeadLetterHeaders.topic(record.topic()), record.key(), record.value(),
                    DeadLetterHeaders.of(record.topic(), record.partition(), record.offset(), invalid));
            log.warn("Invalid notification event moved to DLT at topic={}, partition={}, offset={}",
                    record.topic(), record.partition(), record.offset());
        }
    }
}
