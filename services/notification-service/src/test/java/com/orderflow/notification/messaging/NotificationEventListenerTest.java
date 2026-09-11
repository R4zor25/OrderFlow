package com.orderflow.notification.messaging;

import io.micronaut.json.JsonMapper;
import io.micronaut.configuration.kafka.exceptions.KafkaListenerException;
import io.micronaut.configuration.kafka.annotation.KafkaListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.CommitFailedException;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationEventListenerTest {

    private final RecordingPublisher publisher = new RecordingPublisher();
    private final NotificationEventListener listener =
            new NotificationEventListener(new NotificationEventDecoder(JsonMapper.createDefault()), publisher, null);

    @Test
    void ignoresUnknownEventWithoutDeadLettering() throws Exception {
        var json = Files.readString(Path.of("../../contracts/events/order-confirmed.json"))
                .replace("OrderConfirmed", "OrderShipped");
        listener.onEvent(new ConsumerRecord<>(NotificationEventDecoder.ORDER_TOPIC, 0, 0,
                "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", json));
        assertThat(publisher.sent).isEmpty();
    }

    @Test
    void invalidKnownEventGoesDirectlyToMatchingDeadLetterTopic() {
        listener.onEvent(new ConsumerRecord<>(NotificationEventDecoder.STOCK_TOPIC, 0, 0,
                "CHAIR-001", "{broken-json"));
        assertThat(publisher.sent).containsExactly(new Published(
                NotificationEventDecoder.STOCK_TOPIC + ".DLT", "CHAIR-001", "{broken-json"));
    }

    @Test
    void exhaustedListenerFailureGoesToDeadLetterTopic() {
        var failedRecord = new ConsumerRecord<>(NotificationEventDecoder.ORDER_TOPIC, 1, 12,
                "order-123", "original-json");
        var failure = new KafkaListenerException("processing failed", new IllegalStateException("temporary"),
                listener, null, failedRecord);
        new NotificationListenerExceptionHandler(publisher).handle(failure);
        assertThat(publisher.sent).containsExactly(new Published(
                NotificationEventDecoder.ORDER_TOPIC + ".DLT", "order-123", "original-json"));
    }

    @Test
    void offsetCommitFailureDoesNotDeadLetterProcessedEvent() {
        var record = new ConsumerRecord<>(NotificationEventDecoder.ORDER_TOPIC, 1, 12,
                "order-123", "original-json");
        var failure = new KafkaListenerException("commit failed", new CommitFailedException("rebalance"),
                listener, null, record);
        new NotificationListenerExceptionHandler(publisher).handle(failure);
        assertThat(publisher.sent).isEmpty();
    }

    @Test
    void exhaustedRetryKeepsSourceRecordUntilRecovery() {
        var strategy = NotificationEventListener.class.getAnnotation(KafkaListener.class).errorStrategy();
        assertThat(strategy.retryCount()).isEqualTo(3);
        assertThat(strategy.stopOnExhaustedRetry()).isTrue();
    }

    private record Published(String topic, String key, String json) {
    }

    private static final class RecordingPublisher implements NotificationDeadLetterPublisher {
        private final List<Published> sent = new ArrayList<>();

        @Override
        public void publish(String topic, String key, String json) {
            sent.add(new Published(topic, key, json));
        }
    }
}
