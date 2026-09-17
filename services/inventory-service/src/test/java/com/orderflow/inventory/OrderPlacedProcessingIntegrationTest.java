package com.orderflow.inventory;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.orderflow.inventory.application.OrderCancellationService;
import com.orderflow.inventory.application.OrderPlacedProcessor;
import io.micrometer.core.instrument.MeterRegistry;
import com.orderflow.inventory.infrastructure.events.EventEnvelope;
import com.orderflow.inventory.infrastructure.events.OrderPlacedPayload;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class OrderPlacedProcessingIntegrationTest {

    private final OrderPlacedProcessor processor;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final OrderCancellationService cancellation;
    private final MeterRegistry meters;
    private final java.util.List<String> testSkus = new java.util.ArrayList<>();
    private final java.util.List<UUID> testOrderIds = new java.util.ArrayList<>();
    private final java.util.List<UUID> testEventIds = new java.util.ArrayList<>();

    @Autowired
    OrderPlacedProcessingIntegrationTest(OrderPlacedProcessor processor, JdbcTemplate jdbc,
                                         ObjectMapper json, OrderCancellationService cancellation,
                                         MeterRegistry meters) {
        this.processor = processor;
        this.jdbc = jdbc;
        this.json = json;
        this.cancellation = cancellation;
        this.meters = meters;
    }

    @AfterEach
    void removeTestRows() {
        for (UUID orderId : testOrderIds) {
            jdbc.update("delete from inventory.outbox_event where aggregate_id = ?", orderId.toString());
            jdbc.update("delete from inventory.cancelled_order where order_id = ?", orderId);
        }
        for (UUID eventId : testEventIds) {
            jdbc.update("delete from inventory.processed_event where event_id = ?", eventId);
        }
        for (String sku : testSkus) {
            jdbc.update("delete from inventory.reservation where sku = ?", sku);
            jdbc.update("delete from inventory.stock_item where sku = ?", sku);
        }
    }

    @Test
    void reservesAndEmitsOneResultWhenOrderPlacedIsDeliveredTwice() {
        String sku = insertStock(5);
        UUID orderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        var placed = orderPlaced(eventId, orderId, new OrderPlacedPayload.Line(sku, 2));

        processor.process(placed);
        processor.process(placed);

        assertThat(stockValue(sku, "reserved")).isEqualTo(2);
        assertThat(stockValue(sku, "version")).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
            select quantity from inventory.reservation where order_id = ? and sku = ?
            """, Integer.class, orderId, sku)).isEqualTo(2);
        assertThat(processedCount(eventId)).isEqualTo(1);
        assertThat(outboxCount(orderId)).isEqualTo(1);
        JsonNode result = resultPayload(orderId);
        assertThat(result.get("eventType").asText()).isEqualTo("StockReserved");
        assertThat(result.get("aggregateId").asText()).isEqualTo(orderId.toString());
        assertThat(result.get("payload").get("orderId").asText()).isEqualTo(orderId.toString());
        assertThat(result.get("payload").get("lines").get(0).get("sku").asText()).isEqualTo(sku);
        assertThat(result.get("payload").get("lines").get(0).get("quantity").asInt()).isEqualTo(2);
    }

    @Test
    void rollsBackEarlierStockAndRecordsOneFailureWhenLaterSkuIsUnavailable() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String firstSku = insertStock("TEST-A-" + suffix, 5);
        String secondSku = insertStock("TEST-B-" + suffix, 1);
        UUID orderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        var placed = orderPlaced(eventId, orderId,
            new OrderPlacedPayload.Line(secondSku, 2),
            new OrderPlacedPayload.Line(firstSku, 3));

        processor.process(placed);
        processor.process(placed);

        assertThat(stockValue(firstSku, "reserved")).isZero();
        assertThat(stockValue(firstSku, "version")).isZero();
        assertThat(stockValue(secondSku, "reserved")).isZero();
        assertThat(jdbc.queryForObject(
            "select count(*) from inventory.reservation where order_id = ?", Integer.class, orderId
        )).isZero();
        assertThat(processedCount(eventId)).isEqualTo(1);
        assertThat(outboxCount(orderId)).isEqualTo(1);
        JsonNode result = resultPayload(orderId);
        assertThat(result.get("eventType").asText()).isEqualTo("StockReservationFailed");
        assertThat(result.get("payload").get("reason").asText()).isEqualTo("Insufficient stock");
        assertThat(result.get("payload").get("unavailableSkus").get(0).asText()).isEqualTo(secondSku);
    }

    @Test
    void reportsCombinedQuantityForDuplicateSkuLines() {
        String sku = insertStock(5);
        UUID orderId = UUID.randomUUID();
        var placed = orderPlaced(UUID.randomUUID(), orderId,
            new OrderPlacedPayload.Line(sku, 2),
            new OrderPlacedPayload.Line(sku, 3));

        processor.process(placed);

        assertThat(stockValue(sku, "reserved")).isEqualTo(5);
        JsonNode lines = resultPayload(orderId).get("payload").get("lines");
        assertThat(lines.size()).isEqualTo(1);
        assertThat(lines.get(0).get("sku").asText()).isEqualTo(sku);
        assertThat(lines.get(0).get("quantity").asInt()).isEqualTo(5);
    }

    @Test
    void skipsReservationForAlreadyCancelledOrder() {
        String sku = insertStock(5);
        UUID orderId = UUID.randomUUID();
        UUID cancelEventId = UUID.randomUUID();
        UUID placeEventId = UUID.randomUUID();
        testOrderIds.add(orderId);
        testEventIds.add(cancelEventId);
        testEventIds.add(placeEventId);
        cancellation.release(cancelEventId, orderId);

        processor.process(orderPlaced(placeEventId, orderId, new OrderPlacedPayload.Line(sku, 2)));

        assertThat(jdbc.queryForObject("select count(*) from inventory.reservation where order_id = ?",
            Integer.class, orderId)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from inventory.outbox_event where aggregate_id = ?",
            Integer.class, orderId.toString())).isZero();
    }

    @Test
    void rejectsInvalidOrderOnceAndCountsIt() {
        UUID orderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        testOrderIds.add(orderId);
        testEventIds.add(eventId);
        var counter = meters.get("inventory.reservation.failed").tag("reason", "invalid_order").counter();
        double before = counter.count();
        var placed = orderPlaced(eventId, orderId, new OrderPlacedPayload.Line("CHAIR-001", 0));

        processor.process(placed);
        processor.process(placed);

        assertThat(jdbc.queryForObject("""
            select count(*) from inventory.outbox_event
            where aggregate_id = ? and event_type = 'StockReservationFailed'
              and payload -> 'payload' ->> 'reason' = 'Invalid order'
            """, Integer.class, orderId.toString())).isEqualTo(1);
        assertThat(counter.count()).isEqualTo(before + 1);
    }

    private EventEnvelope<OrderPlacedPayload> orderPlaced(UUID eventId, UUID orderId,
                                                           OrderPlacedPayload.Line... lines) {
        testEventIds.add(eventId);
        testOrderIds.add(orderId);
        return new EventEnvelope<>(eventId, "OrderPlaced", 1, Instant.parse("2026-10-01T10:15:31Z"),
            orderId.toString(), new OrderPlacedPayload(orderId, UUID.randomUUID(), "B2B", List.of(lines)));
    }

    private String insertStock(int onHand) {
        return insertStock("TEST-" + UUID.randomUUID().toString().substring(0, 8), onHand);
    }

    private String insertStock(String sku, int onHand) {
        jdbc.update("""
            insert into inventory.stock_item
                (sku, on_hand, reserved, low_stock_threshold, low_stock_notified, version, updated_at)
            values (?, ?, 0, 1, false, 0, now())
            """, sku, onHand);
        testSkus.add(sku);
        return sku;
    }

    private int stockValue(String sku, String column) {
        return jdbc.queryForObject(
            "select " + column + " from inventory.stock_item where sku = ?", Integer.class, sku
        );
    }

    private int processedCount(UUID eventId) {
        return jdbc.queryForObject(
            "select count(*) from inventory.processed_event where event_id = ?", Integer.class, eventId
        );
    }

    private int outboxCount(UUID orderId) {
        return jdbc.queryForObject(
            "select count(*) from inventory.outbox_event where aggregate_id = ?", Integer.class,
            orderId.toString()
        );
    }

    private JsonNode resultPayload(UUID orderId) {
        String payload = jdbc.queryForObject("""
            select payload::text from inventory.outbox_event where aggregate_id = ?
            """, String.class, orderId.toString());
        return json.readTree(payload);
    }
}
