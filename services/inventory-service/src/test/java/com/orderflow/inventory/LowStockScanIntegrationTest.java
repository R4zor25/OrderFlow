package com.orderflow.inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.orderflow.inventory.lowstock.LowStockScanService;
import com.orderflow.inventory.outbox.InventoryOutboxWriter;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class LowStockScanIntegrationTest {

    @Autowired
    private LowStockScanService scan;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper json;

    @MockitoSpyBean
    private InventoryOutboxWriter outbox;

    private final List<String> skus = new ArrayList<>();

    @AfterEach
    void removeTestRows() {
        for (String sku : skus) {
            jdbc.update("delete from inventory.outbox_event where aggregate_id = ?", sku);
            jdbc.update("delete from inventory.stock_item where sku = ?", sku);
        }
    }

    @Test
    void alertsOnceAtExactThreshold() {
        String sku = insertStock(8, 3, 5);

        scan.scan();
        scan.scan();

        assertThat(outboxCount(sku)).isEqualTo(1);
        assertThat(notified(sku)).isTrue();
        var event = event(sku);
        assertThat(event.get("eventType").asText()).isEqualTo("LowStock");
        assertThat(event.get("aggregateId").asText()).isEqualTo(sku);
        assertThat(event.get("payload").get("sku").asText()).isEqualTo(sku);
        assertThat(event.get("payload").get("available").asInt()).isEqualTo(5);
        assertThat(event.get("payload").get("threshold").asInt()).isEqualTo(5);
    }

    @Test
    void reservedQuantityDeterminesAvailableStock() {
        String sku = insertStock(12, 7, 5);

        scan.scan();

        assertThat(outboxCount(sku)).isEqualTo(1);
        assertThat(event(sku).get("payload").get("available").asInt()).isEqualTo(5);
    }

    @Test
    void emitsOneEventForEachLowSku() {
        String first = insertStock(3, 0, 5);
        String second = insertStock(7, 4, 5);

        scan.scan();

        assertThat(outboxCount(first)).isEqualTo(1);
        assertThat(outboxCount(second)).isEqualTo(1);
        assertThat(event(first).get("aggregateId").asText()).isEqualTo(first);
        assertThat(event(second).get("aggregateId").asText()).isEqualTo(second);
    }

    @Test
    void recoveryRearmsTheNextThresholdCrossing() {
        String sku = insertStock(5, 0, 5);
        scan.scan();

        jdbc.update("update inventory.stock_item set on_hand = 8 where sku = ?", sku);
        scan.scan();

        assertThat(notified(sku)).isFalse();
        assertThat(outboxCount(sku)).isEqualTo(1);

        jdbc.update("update inventory.stock_item set on_hand = 5 where sku = ?", sku);
        scan.scan();

        assertThat(notified(sku)).isTrue();
        assertThat(outboxCount(sku)).isEqualTo(2);
    }

    @Test
    void outboxFailureRollsBackNotifiedMarker() {
        String sku = insertStock(3, 0, 5);
        doThrow(new IllegalStateException("Outbox unavailable"))
            .when(outbox).lowStock(eq(sku), anyInt(), anyInt());

        assertThatThrownBy(() -> scan.scan()).isInstanceOf(IllegalStateException.class);

        assertThat(notified(sku)).isFalse();
        assertThat(outboxCount(sku)).isZero();
    }

    @Test
    void concurrentScansEmitOnlyOneEventForTheSameSku() throws Exception {
        String sku = insertStock(3, 0, 5);
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> {
                start.await();
                return scan.scan();
            });
            var second = pool.submit(() -> {
                start.await();
                return scan.scan();
            });
            start.countDown();
            first.get(20, TimeUnit.SECONDS);
            second.get(20, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(outboxCount(sku)).isEqualTo(1);
    }

    private String insertStock(int onHand, int reserved, int threshold) {
        String sku = "LOW-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("""
            insert into inventory.stock_item
                (sku, on_hand, reserved, low_stock_threshold, low_stock_notified, version, updated_at)
            values (?, ?, ?, ?, false, 0, now())
            """, sku, onHand, reserved, threshold);
        skus.add(sku);
        return sku;
    }

    private int outboxCount(String sku) {
        return jdbc.queryForObject("""
            select count(*) from inventory.outbox_event
            where aggregate_type = 'StockItem' and aggregate_id = ? and event_type = 'LowStock'
            """, Integer.class, sku);
    }

    private boolean notified(String sku) {
        return jdbc.queryForObject("""
            select low_stock_notified from inventory.stock_item where sku = ?
            """, Boolean.class, sku);
    }

    private tools.jackson.databind.JsonNode event(String sku) {
        String payload = jdbc.queryForObject("""
            select payload::text from inventory.outbox_event
            where aggregate_type = 'StockItem' and aggregate_id = ? and event_type = 'LowStock'
            """, String.class, sku);
        return json.readTree(payload);
    }
}
