package com.orderflow.order;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.orderflow.order.application.OutboxRelay;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.TopicBuilder;
import org.testcontainers.kafka.KafkaContainer;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import({PostgresTestConfiguration.class, KafkaTestConfiguration.class,
    OutboxTraceIsolationIntegrationTest.TopicConfiguration.class})
class OutboxTraceIsolationIntegrationTest {
    private final OutboxRelay relay;
    private final JdbcTemplate jdbc;
    private final Tracer tracer;
    private final ObservationRegistry observations;
    private final KafkaContainer kafka;

    @Autowired
    OutboxTraceIsolationIntegrationTest(OutboxRelay relay, JdbcTemplate jdbc, Tracer tracer,
                                       ObservationRegistry observations, KafkaContainer kafka) {
        this.relay = relay;
        this.jdbc = jdbc;
        this.tracer = tracer;
        this.observations = observations;
        this.kafka = kafka;
    }

    @Test
    void isolatesSavedTraceAndStartsNewTracesForLegacyAndInvalidRows() {
        UUID saved = insert("{\"traceparent\":\"00-33333333333333333333333333333333-4444444444444444-01\"}");
        UUID legacy = insert(null);
        UUID invalid = insert("{\"traceparent\":\"invalid\"}");
        var scheduler = Observation.start("test.scheduler", observations);
        String unrelated;
        try (var scope = scheduler.openScope()) {
            unrelated = tracer.currentSpan().context().traceId();
            assertThat(relay.publishBatch()).isEqualTo(3);
            assertThat(observations.getCurrentObservation()).isSameAs(scheduler);
            assertThat(tracer.currentSpan().context().traceId()).isEqualTo(unrelated);
        } finally {
            scheduler.stop();
        }
        assertThat(tracer.currentSpan()).isNull();

        var found = new HashMap<UUID, String>();
        try (var consumer = new KafkaConsumer<String, String>(Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
            ConsumerConfig.GROUP_ID_CONFIG, "trace-test-" + UUID.randomUUID(),
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
            consumer.subscribe(List.of("orders.order-events.v1"));
            Instant deadline = Instant.now().plusSeconds(15);
            while (found.size() < 3 && Instant.now().isBefore(deadline)) {
                for (var record : consumer.poll(Duration.ofMillis(500))) {
                    UUID id = UUID.fromString(record.key());
                    if (List.of(saved, legacy, invalid).contains(id)) {
                        assertThat(record.headers().headers("traceparent")).hasSize(1);
                        String context = new String(record.headers().lastHeader("traceparent").value(), StandardCharsets.UTF_8);
                        assertThat(context).matches("00-[a-f0-9]{32}-[a-f0-9]{16}-[a-f0-9]{2}");
                        found.put(id, context.substring(3, 35));
                    }
                }
            }
        }
        assertThat(found).hasSize(3);
        assertThat(found.get(saved)).isEqualTo("33333333333333333333333333333333");
        assertThat(found.get(legacy)).isNotEqualTo(unrelated).isNotEqualTo(found.get(saved));
        assertThat(found.get(invalid)).isNotEqualTo(unrelated).isNotEqualTo(found.get(saved))
            .isNotEqualTo(found.get(legacy));
    }

    private UUID insert(String headers) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            insert into orders.outbox_event
            (id, aggregate_type, aggregate_id, event_type, payload, headers, created_at)
            values (?, 'Order', ?, 'OrderPlaced', '{}'::jsonb, ?::jsonb, ?)
            """, id, id, headers, Timestamp.from(Instant.now()));
        return id;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TopicConfiguration {
        @Bean NewTopic testOrderTopic() {
            return TopicBuilder.name("orders.order-events.v1").partitions(3).replicas(1).build();
        }
    }
}
