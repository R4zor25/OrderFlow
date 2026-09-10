package com.orderflow.inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.orderflow.inventory.application.OrderCancellationService;
import com.orderflow.inventory.infrastructure.outbox.InventoryOutboxWriter;
import com.orderflow.inventory.reservation.application.ReservationService;
import com.orderflow.inventory.reservation.domain.ReservationLine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class OrderCancellationIntegrationTest {

    @Autowired
    private OrderCancellationService cancellation;

    @Autowired
    private ReservationService reservations;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper json;

    @MockitoSpyBean
    private InventoryOutboxWriter outbox;

    private final List<String> skus = new ArrayList<>();
    private final List<UUID> orderIds = new ArrayList<>();
    private final List<UUID> eventIds = new ArrayList<>();

    @AfterEach
    void removeTestRows() {
        for (UUID orderId : orderIds) {
            jdbc.update("delete from inventory.outbox_event where aggregate_id = ?", orderId.toString());
            jdbc.update("delete from inventory.reservation where order_id = ?", orderId);
        }
        for (UUID eventId : eventIds) {
            jdbc.update("delete from inventory.processed_event where event_id = ?", eventId);
        }
        for (String sku : skus) {
            jdbc.update("delete from inventory.stock_item where sku = ?", sku);
        }
    }

    @Test
    void releasesEveryReservedSkuAndWritesOneStockReleasedEvent() {
        UUID orderId = orderId();
        UUID eventId = eventId();
        String first = stockSku(5);
        String second = stockSku(7);
        reservations.reserve(orderId, List.of(new ReservationLine(first, 2),
            new ReservationLine(second, 3)));

        cancellation.release(eventId, orderId);
        cancellation.release(eventId, orderId);

        assertThat(reserved(first)).isZero();
        assertThat(reserved(second)).isZero();
        assertThat(onHand(first)).isEqualTo(5);
        assertThat(onHand(second)).isEqualTo(7);
        assertThat(version(first)).isEqualTo(2);
        assertThat(version(second)).isEqualTo(2);
        assertThat(reservationStatus(orderId, first)).isEqualTo("RELEASED");
        assertThat(reservationStatus(orderId, second)).isEqualTo("RELEASED");
        assertThat(processedCount(eventId)).isEqualTo(1);
        assertThat(releasedEventCount(orderId)).isEqualTo(1);
        var message = json.readTree(jdbc.queryForObject("""
            select payload::text from inventory.outbox_event
             where aggregate_id = ? and event_type = 'StockReleased'
            """, String.class, orderId.toString()));
        assertThat(message.get("aggregateId").asText()).isEqualTo(orderId.toString());
        assertThat(message.get("payload").get("orderId").asText()).isEqualTo(orderId.toString());
    }

    @Test
    void recordsCancellationWithoutReservationsButEmitsNoReleaseEvent() {
        UUID orderId = orderId();
        UUID eventId = eventId();

        cancellation.release(eventId, orderId);

        assertThat(processedCount(eventId)).isEqualTo(1);
        assertThat(releasedEventCount(orderId)).isZero();
    }

    @Test
    void twoDifferentCancellationEventsDoNotReleaseSameStockTwice() {
        UUID orderId = orderId();
        UUID firstEvent = eventId();
        UUID secondEvent = eventId();
        String sku = stockSku(5);
        reservations.reserve(orderId, List.of(new ReservationLine(sku, 2)));

        cancellation.release(firstEvent, orderId);
        cancellation.release(secondEvent, orderId);

        assertThat(reserved(sku)).isZero();
        assertThat(version(sku)).isEqualTo(2);
        assertThat(processedCount(firstEvent)).isEqualTo(1);
        assertThat(processedCount(secondEvent)).isEqualTo(1);
        assertThat(releasedEventCount(orderId)).isEqualTo(1);
    }

    @Test
    void rollsBackStockReservationAndMarkerWhenOutboxWriteFails() {
        UUID orderId = orderId();
        UUID eventId = eventId();
        String sku = stockSku(5);
        reservations.reserve(orderId, List.of(new ReservationLine(sku, 2)));
        doThrow(new IllegalStateException("Outbox unavailable")).when(outbox).released(orderId);

        assertThatThrownBy(() -> cancellation.release(eventId, orderId))
            .isInstanceOf(IllegalStateException.class);

        assertThat(reserved(sku)).isEqualTo(2);
        assertThat(reservationStatus(orderId, sku)).isEqualTo("RESERVED");
        assertThat(processedCount(eventId)).isZero();
        assertThat(releasedEventCount(orderId)).isZero();
    }

    private UUID orderId() {
        UUID id = UUID.randomUUID();
        orderIds.add(id);
        return id;
    }

    private UUID eventId() {
        UUID id = UUID.randomUUID();
        eventIds.add(id);
        return id;
    }

    private String stockSku(int onHand) {
        String sku = "CANCEL-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("""
            insert into inventory.stock_item
                (sku, on_hand, reserved, low_stock_threshold, low_stock_notified, version, updated_at)
            values (?, ?, 0, 1, false, 0, now())
            """, sku, onHand);
        skus.add(sku);
        return sku;
    }

    private int reserved(String sku) {
        return jdbc.queryForObject("select reserved from inventory.stock_item where sku = ?",
            Integer.class, sku);
    }

    private int onHand(String sku) {
        return jdbc.queryForObject("select on_hand from inventory.stock_item where sku = ?",
            Integer.class, sku);
    }

    private int version(String sku) {
        return jdbc.queryForObject("select version from inventory.stock_item where sku = ?",
            Integer.class, sku);
    }

    private String reservationStatus(UUID orderId, String sku) {
        return jdbc.queryForObject("""
            select status from inventory.reservation where order_id = ? and sku = ?
            """, String.class, orderId, sku);
    }

    private int processedCount(UUID eventId) {
        return jdbc.queryForObject("""
            select count(*) from inventory.processed_event where event_id = ?
            """, Integer.class, eventId);
    }

    private int releasedEventCount(UUID orderId) {
        return jdbc.queryForObject("""
            select count(*) from inventory.outbox_event
             where aggregate_id = ? and event_type = 'StockReleased'
            """, Integer.class, orderId.toString());
    }
}
