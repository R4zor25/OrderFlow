package com.orderflow.inventory.infrastructure.outbox;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.orderflow.inventory.config.InventoryOutboxRelayProperties;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class InventoryOutboxRelay {

    private static final String TOPIC = "inventory.stock-events.v1";

    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final InventoryOutboxRelayProperties properties;
    private final ObjectMapper json;
    private final Clock clock;
    private final Tracer tracer;
    private final Propagator propagator;
    private final ObservationRegistry observations;
    private static final Logger log = LoggerFactory.getLogger(InventoryOutboxRelay.class);

    public InventoryOutboxRelay(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka,
                                InventoryOutboxRelayProperties properties,
                                ObjectMapper json, Clock clock, Tracer tracer,
                                Propagator propagator, ObservationRegistry observations) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.properties = properties;
        this.json = json;
        this.clock = clock;
        this.tracer = tracer;
        this.propagator = propagator;
        this.observations = observations;
    }

    @Transactional
    public int publishBatch() {
        var events = jdbc.query("""
            select event.id, event.aggregate_id, event.event_type,
                   event.payload::text, event.headers::text
            from inventory.outbox_event event
            where event.published_at is null
              and event.parked_at is null
              and not exists (
                  select 1 from inventory.outbox_event earlier
                  where earlier.aggregate_type = event.aggregate_type
                    and earlier.aggregate_id = event.aggregate_id
                    and earlier.delivery_order < event.delivery_order
                    and earlier.published_at is null
              )
            order by event.created_at, event.id
            limit 100
            for update of event skip locked
            """, (rs, rowNum) -> new PendingEvent(
            rs.getObject("id", UUID.class),
            rs.getString("aggregate_id"),
            rs.getString("event_type"),
            rs.getString("payload"),
            rs.getString("headers")));

        int published = 0;
        for (var event : events) {
            var record = new ProducerRecord<String, String>(TOPIC, event.aggregateId(), event.payload());
            record.headers().add(new RecordHeader("eventType", event.eventType().getBytes(StandardCharsets.UTF_8)));
            boolean failed = true;
            var previousObservationScope = observations.getCurrentObservationScope();
            // The saved event context takes precedence over the scheduled relay observation.
            observations.setCurrentObservationScope(null);
            try (var detached = tracer.withSpan(null)) {
                JsonNode headers = event.headers() == null ? null : json.readTree(event.headers());
                var span = propagator.extract(headers, (carrier, key) -> {
                    var value = carrier == null ? null : carrier.get(key);
                    return value != null && value.isString() ? value.asString() : null;
                }).name("inventory.outbox.publish").start();
                try (var scope = tracer.withSpan(span)) {
                    kafka.send(record).get(properties.ackTimeout().toMillis(), TimeUnit.MILLISECONDS);
                    log.info("Published outbox event eventId={} aggregateId={} eventType={}",
                        event.id(), event.aggregateId(), event.eventType());
                    failed = false;
                } catch (InterruptedException | ExecutionException | TimeoutException | RuntimeException exception) {
                    span.error(exception);
                    throw exception;
                } finally {
                    span.end();
                }
            } catch (InterruptedException | ExecutionException | TimeoutException | RuntimeException exception) {
                recordFailure(event, exception);
            } finally {
                observations.setCurrentObservationScope(previousObservationScope);
            }
            if (failed) {
                // A failed send usually means the broker is struggling; the rest of the batch waits for the next run.
                break;
            }

            int updated = jdbc.update("""
                update inventory.outbox_event set published_at = ?
                where id = ? and published_at is null
                """, Timestamp.from(clock.instant()), event.id());
            if (updated != 1) {
                throw new IllegalStateException("Outbox event was not available to mark published: " + event.id());
            }
            published++;
        }
        return published;
    }

    private void recordFailure(PendingEvent event, Throwable failure) {
        if (Thread.interrupted() || failure instanceof InterruptedException) {
            restoreInterruptAfterCompletion();
        }
        boolean permanent = OutboxPublishFailure.isPermanent(failure);
        int attempts = jdbc.queryForObject("""
            update inventory.outbox_event
            set attempts = attempts + 1, last_error = ?, parked_at = ?
            where id = ?
            returning attempts
            """, Integer.class, OutboxPublishFailure.describe(failure),
            permanent ? Timestamp.from(clock.instant()) : null, event.id());
        if (permanent) {
            log.error("Parked undeliverable outbox event eventId={} aggregateId={} eventType={} attempts={} cause={}",
                event.id(), event.aggregateId(), event.eventType(), attempts, OutboxPublishFailure.cause(failure).getClass().getSimpleName());
        } else {
            log.warn("Outbox event delivery failed, will retry eventId={} aggregateId={} eventType={} attempts={} cause={}",
                event.id(), event.aggregateId(), event.eventType(), attempts, OutboxPublishFailure.cause(failure).getClass().getSimpleName());
        }
    }

    private static void restoreInterruptAfterCompletion() {
        // On a virtual thread an interrupt closes the JDBC socket, which would roll back the acknowledged events.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                Thread.currentThread().interrupt();
            }
        });
    }

    private record PendingEvent(UUID id, String aggregateId, String eventType, String payload, String headers) {
    }
}
