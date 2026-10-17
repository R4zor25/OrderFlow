package com.orderflow.order;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.orderflow.order.infrastructure.outbox.OutboxRelay;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.InterruptException;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class OutboxRelayParkingIntegrationTest {

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MeterRegistry meters;

    @MockitoBean
    private KafkaTemplate<String, String> kafka;

    private final Set<UUID> undeliverable = ConcurrentHashMap.newKeySet();

    @BeforeEach
    void broker() {
        jdbc.update("delete from orders.outbox_event");
        undeliverable.clear();
        when(kafka.send(any(ProducerRecord.class))).thenAnswer(invocation -> {
            ProducerRecord<String, String> record = invocation.getArgument(0);
            boolean tooLarge = undeliverable.stream().anyMatch(id -> record.value().contains(id.toString()));
            return tooLarge
                ? CompletableFuture.failedFuture(new RecordTooLargeException("record too large"))
                : CompletableFuture.completedFuture(null);
        });
    }

    @Test
    void keepsAcknowledgedEventsWhenALaterEventFails() {
        Instant now = Instant.now();
        UUID delivered = insert(UUID.randomUUID(), now);
        UUID tooLarge = insert(UUID.randomUUID(), now.plusSeconds(1));
        undeliverable.add(tooLarge);

        assertThat(relay.publishBatch()).isEqualTo(1);

        assertThat(column(delivered, "published_at")).isNotNull();
        assertThat(column(tooLarge, "parked_at")).isNotNull();
        assertThat(column(tooLarge, "published_at")).isNull();
        assertThat(column(tooLarge, "attempts")).isEqualTo(1);
        assertThat((String) column(tooLarge, "last_error")).startsWith("RecordTooLargeException");

        assertThat(relay.publishBatch()).isZero();
        verify(kafka, times(2)).send(any(ProducerRecord.class));
    }

    @Test
    void parkedEventBlocksOnlyItsOwnAggregate() {
        Instant now = Instant.now();
        UUID blockedOrder = UUID.randomUUID();
        UUID first = insert(blockedOrder, now);
        UUID second = insert(blockedOrder, now.plusSeconds(1));
        UUID otherOrderEvent = insert(UUID.randomUUID(), now.plusSeconds(2));
        undeliverable.add(first);

        relay.publishBatch();
        relay.publishBatch();

        assertThat(column(first, "parked_at")).isNotNull();
        assertThat(column(second, "published_at")).isNull();
        assertThat(column(second, "parked_at")).isNull();
        assertThat(column(otherOrderEvent, "published_at")).isNotNull();
    }

    @Test
    void parkedGaugeCountsUndeliverableEvents() {
        UUID tooLarge = insert(UUID.randomUUID(), Instant.now());
        undeliverable.add(tooLarge);

        relay.publishBatch();

        assertThat(meters.get("outbox.parked").gauge().value()).isEqualTo(1.0);
    }

    @Test
    void parkedGaugeLeavesOutEventsThatAreOnlyWaiting() {
        UUID orderId = UUID.randomUUID();
        Instant now = Instant.now();
        UUID tooLarge = insert(orderId, now);
        UUID waiting = insert(orderId, now.plusSeconds(1));
        undeliverable.add(tooLarge);

        relay.publishBatch();

        assertThat(column(waiting, "published_at")).isNull();
        assertThat(column(waiting, "parked_at")).isNull();
        assertThat(meters.get("outbox.parked").gauge().value()).isEqualTo(1.0);
    }

    @Test
    void rollsBackTheBatchWhenAnEventIsNoLongerUnpublished() {
        UUID event = insert(UUID.randomUUID(), Instant.now());
        when(kafka.send(any(ProducerRecord.class))).thenAnswer(invocation -> {
            // Same thread and transaction as the relay, so the row lock does not stop this update.
            jdbc.update("update orders.outbox_event set published_at = now() where id = ?", event);
            return CompletableFuture.completedFuture(null);
        });

        assertThatThrownBy(() -> relay.publishBatch())
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Outbox event was not available to mark published: " + event);

        assertThat(column(event, "published_at")).isNull();
    }

    @Test
    void synchronousSendFailureKeepsAcknowledgedEventsAndIsRetried() {
        Instant now = Instant.now();
        UUID delivered = insert(UUID.randomUUID(), now);
        UUID failing = insert(UUID.randomUUID(), now.plusSeconds(1));
        when(kafka.send(any(ProducerRecord.class)))
            .thenReturn(CompletableFuture.completedFuture(null))
            .thenThrow(new IllegalStateException("Cannot perform operation after producer has been closed"));

        assertThat(relay.publishBatch()).isEqualTo(1);

        assertThat(column(delivered, "published_at")).isNotNull();
        assertThat(column(failing, "attempts")).isEqualTo(1);
        assertThat(column(failing, "parked_at")).isNull();
    }

    @Test
    void interruptedSendKeepsAcknowledgedEventsAndRestoresTheInterruptAfterCommit() throws Exception {
        Instant now = Instant.now();
        UUID delivered = insert(UUID.randomUUID(), now);
        UUID failing = insert(UUID.randomUUID(), now.plusSeconds(1));
        when(kafka.send(any(ProducerRecord.class)))
            .thenReturn(CompletableFuture.completedFuture(null))
            .thenAnswer(invocation -> {
                throw new InterruptException("shutting down");
            });

        RelayRun run = publishOnVirtualThread();

        assertThat(run.published()).isEqualTo(1);
        assertThat(run.interrupted()).isTrue();
        assertThat(column(delivered, "published_at")).isNotNull();
        assertThat(column(failing, "attempts")).isEqualTo(1);
        assertThat(column(failing, "parked_at")).isNull();
    }

    @Test
    void interruptedAcknowledgementWaitKeepsAcknowledgedEventsAndRestoresTheInterruptAfterCommit() throws Exception {
        Instant now = Instant.now();
        UUID delivered = insert(UUID.randomUUID(), now);
        UUID failing = insert(UUID.randomUUID(), now.plusSeconds(1));
        @SuppressWarnings("unchecked")
        CompletableFuture<SendResult<String, String>> waiting = mock(CompletableFuture.class);
        when(waiting.get(anyLong(), any(TimeUnit.class))).thenThrow(new InterruptedException("shutting down"));
        when(kafka.send(any(ProducerRecord.class)))
            .thenReturn(CompletableFuture.completedFuture(null))
            .thenReturn(waiting);

        RelayRun run = publishOnVirtualThread();

        assertThat(run.published()).isEqualTo(1);
        assertThat(run.interrupted()).isTrue();
        assertThat(column(delivered, "published_at")).isNotNull();
        assertThat(column(failing, "attempts")).isEqualTo(1);
    }

    private RelayRun publishOnVirtualThread() throws InterruptedException {
        var run = new AtomicReference<RelayRun>();
        var failure = new AtomicReference<Throwable>();
        // Production schedules the relay on a virtual thread, where an interrupt closes the JDBC socket.
        Thread.ofVirtual().start(() -> {
            try {
                run.set(new RelayRun(relay.publishBatch(), Thread.interrupted()));
            } catch (Throwable exception) {
                failure.set(exception);
            }
        }).join();
        assertThat(failure.get()).isNull();
        return run.get();
    }

    private record RelayRun(int published, boolean interrupted) {
    }

    private UUID insert(UUID orderId, Instant createdAt) {
        UUID eventId = UUID.randomUUID();
        jdbc.update("""
            insert into orders.outbox_event
                (id, aggregate_type, aggregate_id, event_type, payload, created_at)
            values (?, 'Order', ?, 'OrderPlaced', ?::jsonb, ?)
            """, eventId, orderId, """
                {"eventId":"%s","eventType":"OrderPlaced","eventVersion":1,
                 "occurredAt":"2026-09-30T10:00:00Z","aggregateId":"%s","payload":{}}
                """.formatted(eventId, orderId), Timestamp.from(createdAt));
        return eventId;
    }

    private Object column(UUID eventId, String name) {
        return jdbc.queryForMap("select * from orders.outbox_event where id = ?", eventId).get(name);
    }
}
