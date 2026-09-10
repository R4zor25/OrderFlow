package com.orderflow.inventory.infrastructure.outbox;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.orderflow.inventory.config.InventoryOutboxRelayProperties;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class InventoryOutboxRelay {

    private static final String TOPIC = "inventory.stock-events.v1";

    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final InventoryOutboxRelayProperties properties;
    private final ObjectMapper json;
    private final Clock clock;

    public InventoryOutboxRelay(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka,
                                InventoryOutboxRelayProperties properties,
                                ObjectMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.properties = properties;
        this.json = json;
        this.clock = clock;
    }

    @Transactional
    public int publishBatch() {
        var events = jdbc.query("""
            select event.id, event.aggregate_id, event.event_type,
                   event.payload::text, event.headers::text
            from inventory.outbox_event event
            where event.published_at is null
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

        for (var event : events) {
            var record = new ProducerRecord<String, String>(TOPIC, event.aggregateId(), event.payload());
            record.headers().add(new RecordHeader("eventType", event.eventType().getBytes(StandardCharsets.UTF_8)));
            if (event.headers() != null) {
                var traceparent = json.readTree(event.headers()).path("traceparent").asString(null);
                if (traceparent != null) {
                    record.headers().add(new RecordHeader("traceparent", traceparent.getBytes(StandardCharsets.UTF_8)));
                }
            }

            try {
                kafka.send(record).get(properties.ackTimeout().toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while publishing outbox event " + event.id(), exception);
            } catch (ExecutionException | TimeoutException exception) {
                throw new IllegalStateException("Could not publish outbox event " + event.id(), exception);
            }

            int updated = jdbc.update("""
                update inventory.outbox_event set published_at = ?
                where id = ? and published_at is null
                """, Timestamp.from(clock.instant()), event.id());
            if (updated != 1) {
                throw new IllegalStateException("Outbox event was not available to mark published: " + event.id());
            }
        }
        return events.size();
    }

    private record PendingEvent(UUID id, String aggregateId, String eventType, String payload, String headers) {
    }
}
