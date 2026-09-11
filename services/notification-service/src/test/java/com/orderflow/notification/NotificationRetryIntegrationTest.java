package com.orderflow.notification;

import com.orderflow.notification.application.NotificationDraft;
import com.orderflow.notification.application.NotificationStore;
import com.orderflow.notification.infrastructure.persistence.NotificationRepository;
import com.orderflow.notification.messaging.NotificationEventDecoder;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Singleton;
import jakarta.transaction.Transactional;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@MicronautTest(transactional = false)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NotificationRetryIntegrationTest implements TestPropertyProvider {
    private static final KafkaContainer kafka = new KafkaContainer("apache/kafka:4.3.1");
    private static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.11-bookworm");
    private static final UUID transientId = UUID.randomUUID();
    private static final UUID permanentId = UUID.randomUUID();
    private static final AtomicInteger transientAttempts = new AtomicInteger();
    private static final AtomicInteger permanentAttempts = new AtomicInteger();

    static {
        kafka.start();
        postgres.start();
        try (var admin = AdminClient.create(Map.of("bootstrap.servers", kafka.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(NotificationEventDecoder.ORDER_TOPIC, 3, (short) 1),
                    new NewTopic(NotificationEventDecoder.STOCK_TOPIC, 3, (short) 1),
                    new NewTopic(NotificationEventDecoder.ORDER_TOPIC + ".DLT", 3, (short) 1))).all().get();
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    @Override
    public Map<String, String> getProperties() {
        return Map.of("kafka.bootstrap.servers", kafka.getBootstrapServers(),
                "notification.messaging.enabled", "true", "notification.test.failing-store", "true",
                "datasources.default.url", postgres.getJdbcUrl(),
                "datasources.default.username", postgres.getUsername(),
                "datasources.default.password", postgres.getPassword());
    }

    @Test
    void retriesTransientFailureAndPausesUncommittedRecordAfterExhaustion() throws Exception {
        var key = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
        var sample = Files.readString(Path.of("../../contracts/events/order-confirmed.json"));
        var transientJson = sample.replace("cccccccc-cccc-4ccc-8ccc-cccccccccccc", transientId.toString());
        var permanentJson = sample.replace("cccccccc-cccc-4ccc-8ccc-cccccccccccc", permanentId.toString());
        try (var producer = new KafkaProducer<String, String>(Map.of("bootstrap.servers", kafka.getBootstrapServers(),
                "key.serializer", StringSerializer.class, "value.serializer", StringSerializer.class))) {
            producer.send(new ProducerRecord<>(NotificationEventDecoder.ORDER_TOPIC, 0, key, transientJson)).get();
            producer.send(new ProducerRecord<>(NotificationEventDecoder.ORDER_TOPIC, 1, key, permanentJson)).get();
            producer.send(new ProducerRecord<>(NotificationEventDecoder.ORDER_TOPIC, 1, key, transientJson)).get();
        }
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(transientAttempts).hasValue(3);
            assertThat(permanentAttempts).hasValue(4);
            try (var admin = AdminClient.create(Map.of("bootstrap.servers", kafka.getBootstrapServers()))) {
                var offsets = admin.listConsumerGroupOffsets("notification-service").partitionsToOffsetAndMetadata().get();
                assertThat(offsets.get(new TopicPartition(NotificationEventDecoder.ORDER_TOPIC, 0)).offset()).isEqualTo(1);
                var failed = offsets.get(new TopicPartition(NotificationEventDecoder.ORDER_TOPIC, 1));
                assertThat(failed == null ? 0 : failed.offset()).isZero();
            }
        });
        try (var consumer = new KafkaConsumer<String, String>(Map.of("bootstrap.servers", kafka.getBootstrapServers(),
                "group.id", "retry-dlt-" + UUID.randomUUID(), "key.deserializer", StringDeserializer.class,
                "value.deserializer", StringDeserializer.class, "auto.offset.reset", "earliest"))) {
            consumer.subscribe(List.of(NotificationEventDecoder.ORDER_TOPIC + ".DLT"));
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                var records = consumer.poll(Duration.ofMillis(250));
                assertThat(records.count()).isEqualTo(1);
                assertThat(records.iterator().next().value()).isEqualTo(permanentJson);
            });
        }
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(transientAttempts).hasValue(3);
            assertThat(permanentAttempts).hasValue(4);
        });
    }

    @Singleton
    @Replaces(NotificationStore.class)
    @Requires(property = "notification.test.failing-store", value = "true")
    static class FailingStore extends NotificationStore {
        FailingStore(NotificationRepository repository, Clock clock) {
            super(repository, clock);
        }

        @Override
        @Transactional
        public boolean insertIfAbsent(NotificationDraft draft) {
            if (draft.eventId().equals(permanentId)) {
                permanentAttempts.incrementAndGet();
                throw new IllegalStateException("Simulated permanent database failure");
            }
            if (draft.eventId().equals(transientId) && transientAttempts.incrementAndGet() <= 2) {
                throw new IllegalStateException("Simulated transient database failure");
            }
            return super.insertIfAbsent(draft);
        }
    }
}
