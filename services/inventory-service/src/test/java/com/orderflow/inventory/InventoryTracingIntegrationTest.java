package com.orderflow.inventory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import com.orderflow.inventory.infrastructure.events.OrderEventListener;
import com.orderflow.inventory.infrastructure.events.EventEnvelope;
import com.orderflow.inventory.infrastructure.events.OrderPlacedPayload;
import com.orderflow.inventory.infrastructure.outbox.InventoryOutboxRelay;
import com.orderflow.inventory.infrastructure.outbox.InventoryOutboxWriter;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(properties = {
    "inventory.messaging.enabled=true",
    "management.tracing.sampling.probability=1.0"
})
@Import({PostgresTestConfiguration.class, KafkaTestConfiguration.class})
class InventoryTracingIntegrationTest {

    private static final String TRACE_ID = "0123456789abcdef0123456789abcdef";
    private static final String TRACEPARENT = "00-" + TRACE_ID + "-0123456789abcdef-01";
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final KafkaContainer kafka;
    private final InventoryOutboxRelay relay;
    private final InventoryOutboxWriter writer;
    private final PlatformTransactionManager transactions;
    private final Tracer tracer;
    private final ObservationRegistry observations;

    @Autowired
    InventoryTracingIntegrationTest(JdbcTemplate jdbc, ObjectMapper json, KafkaContainer kafka,
                                    InventoryOutboxRelay relay, InventoryOutboxWriter writer,
                                    PlatformTransactionManager transactions, Tracer tracer,
                                    ObservationRegistry observations) {
        this.jdbc = jdbc;
        this.json = json;
        this.kafka = kafka;
        this.relay = relay;
        this.writer = writer;
        this.transactions = transactions;
        this.tracer = tracer;
        this.observations = observations;
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 1000000})
    void continuesIncomingOrderTraceThroughStoredOutboxAndKafka(int quantity) throws Exception {
        UUID orderId = UUID.randomUUID();
        var logger = (Logger) LoggerFactory.getLogger(OrderEventListener.class);
        var captured = new ListAppender<ILoggingEvent>() {
            @Override
            protected void append(ILoggingEvent event) {
                event.prepareForDeferredProcessing();
                super.append(event);
            }
        };
        captured.start();
        logger.addAppender(captured);
        try {
            var event = new EventEnvelope<>(UUID.randomUUID(), "OrderPlaced", 1, Instant.now(),
                orderId.toString(), new OrderPlacedPayload(orderId, UUID.randomUUID(), "B2B",
                List.of(new OrderPlacedPayload.Line("CHAIR-001", quantity))));
            var record = new ProducerRecord<String, String>("orders.order-events.v1", orderId.toString(),
                json.writeValueAsString(event));
            record.headers().add("traceparent", TRACEPARENT.getBytes(StandardCharsets.UTF_8));
            record.headers().add("tracestate", "vendor=value".getBytes(StandardCharsets.UTF_8));
            try (var producer = new KafkaProducer<String, String>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class))) {
                producer.send(record).get(10, TimeUnit.SECONDS);
            }
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                var rows = jdbc.queryForList("select headers::text from inventory.outbox_event where aggregate_id = ?",
                    String.class, orderId.toString());
                assertThat(rows).hasSize(1);
                String headers = rows.getFirst();
                assertThat(headers).isNotNull();
                assertThat(json.readTree(headers).path("traceparent").asString()).contains(TRACE_ID);
                assertThat(json.readTree(headers).path("tracestate").asString()).isEqualTo("vendor=value");
            });
            assertThat(captured.list).anySatisfy(log -> {
                assertThat(log.getFormattedMessage()).contains("Processing OrderPlaced", event.eventId().toString(), orderId.toString());
                assertThat(log.getMDCPropertyMap()).containsEntry("traceId", TRACE_ID);
                assertThat(log.getMDCPropertyMap().get("spanId")).matches("[0-9a-f]{16}");
            });
            var outgoing = receive(orderId);
            assertThat(json.readTree(outgoing.value()).path("eventType").asString())
                .isEqualTo(quantity == 1 ? "StockReserved" : "StockReservationFailed");
            assertThat(traceparent(outgoing)).contains(TRACE_ID);
            assertThat(new String(outgoing.headers().lastHeader("tracestate").value(), StandardCharsets.UTF_8))
                .isEqualTo("vendor=value");
        } finally {
            logger.detachAppender(captured);
            captured.stop();
        }
    }

    @Test
    void restoresStoredTraceDespiteConflictingScheduledObservation() {
        UUID orderId = UUID.randomUUID();
        var scheduled = Observation.start("test.scheduled", observations);
        String scheduledTrace;
        try (var scope = scheduled.openScope()) {
            scheduledTrace = tracer.currentSpan().context().traceId();
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                writer.released(orderId);
                jdbc.update("update inventory.outbox_event set headers = ?::jsonb where aggregate_id = ?",
                    "{\"traceparent\":\"" + TRACEPARENT + "\",\"tracestate\":\"vendor=value\"}", orderId.toString());
                relay.publishBatch();
                assertThat(tracer.currentSpan().context().traceId()).isEqualTo(scheduledTrace);
            });
        } finally {
            scheduled.stop();
        }
        assertThat(traceparent(receive(orderId))).contains(TRACE_ID).doesNotContain(scheduledTrace);
    }

    @Test
    void startsSeparateRootTracesForAbsentAndInvalidStoredContext() {
        UUID absent = UUID.randomUUID();
        UUID invalid = UUID.randomUUID();
        var scheduled = Observation.start("test.scheduled", observations);
        String scheduledTrace;
        try (var scope = scheduled.openScope()) {
            scheduledTrace = tracer.currentSpan().context().traceId();
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                writer.released(absent);
                writer.released(invalid);
                jdbc.update("update inventory.outbox_event set headers = null where aggregate_id = ?", absent.toString());
                jdbc.update("update inventory.outbox_event set headers = '{\"traceparent\":\"invalid\"}'::jsonb where aggregate_id = ?",
                    invalid.toString());
                relay.publishBatch();
            });
        } finally {
            scheduled.stop();
        }
        String absentParent = traceparent(receive(absent));
        String invalidParent = traceparent(receive(invalid));
        assertThat(absentParent).doesNotContain(scheduledTrace);
        assertThat(invalidParent).doesNotContain(scheduledTrace);
        assertThat(absentParent.split("-")[1]).isNotEqualTo(invalidParent.split("-")[1]);
    }

    @Test
    void writesNoTraceContextWhenNoSpanIsActive() {
        UUID orderId = UUID.randomUUID();
        assertThat(tracer.currentSpan()).isNull();
        new TransactionTemplate(transactions).executeWithoutResult(status -> writer.released(orderId));
        assertThat(jdbc.queryForObject("select headers::text from inventory.outbox_event where aggregate_id = ?",
            String.class, orderId.toString())).isNull();
    }

    private String traceparent(ConsumerRecord<String, String> record) {
        var values = new ArrayList<String>();
        record.headers().headers("traceparent").forEach(header ->
            values.add(new String(header.value(), StandardCharsets.UTF_8)));
        assertThat(values).hasSize(1);
        assertThat(values.getFirst()).matches("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}");
        assertThat(Integer.parseInt(values.getFirst().split("-")[3], 16) & 1).isEqualTo(1);
        return values.getFirst();
    }

    private ConsumerRecord<String, String> receive(UUID orderId) {
        try (var consumer = new KafkaConsumer<String, String>(Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
            ConsumerConfig.GROUP_ID_CONFIG, "inventory-tracing-" + UUID.randomUUID(),
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
            consumer.subscribe(List.of("inventory.stock-events.v1"));
            Instant deadline = Instant.now().plusSeconds(15);
            while (Instant.now().isBefore(deadline)) {
                for (var record : consumer.poll(Duration.ofMillis(500))) {
                    if (record.key().equals(orderId.toString())) {
                        return record;
                    }
                }
            }
            throw new AssertionError("No stock event for " + orderId);
        }
    }
}
