package com.orderflow.inventory.reservation;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.orderflow.inventory.PostgresTestConfiguration;
import com.orderflow.inventory.reservation.application.ReservationService;
import com.orderflow.inventory.reservation.domain.ReservationLine;
import com.orderflow.inventory.reservation.domain.StockUnavailableException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class ReservationServiceIntegrationTest {

    private final ReservationService reservationService;
    private final JdbcTemplate jdbc;
    private final List<String> testSkus = new ArrayList<>();

    @Autowired
    ReservationServiceIntegrationTest(ReservationService reservationService, JdbcTemplate jdbc) {
        this.reservationService = reservationService;
        this.jdbc = jdbc;
    }

    @AfterEach
    void removeTestStock() {
        for (String sku : testSkus) {
            jdbc.update("delete from inventory.reservation where sku = ?", sku);
            jdbc.update("delete from inventory.stock_item where sku = ?", sku);
        }
    }

    @Test
    void reservesAvailableStockAndRecordsReservation() {
        String sku = insertStock(5);
        UUID orderId = UUID.randomUUID();

        reservationService.reserve(orderId, List.of(new ReservationLine(sku, 3)));

        assertThat(stockValue(sku, "reserved")).isEqualTo(3);
        assertThat(stockValue(sku, "version")).isEqualTo(1);
        assertThat(reservationQuantity(orderId, sku)).isEqualTo(3);
    }

    @Test
    void rejectsInsufficientStockWithoutChangingDatabase() {
        String sku = insertStock(2);
        UUID orderId = UUID.randomUUID();

        assertThatThrownBy(() -> reservationService.reserve(orderId, List.of(new ReservationLine(sku, 3))))
            .isInstanceOf(StockUnavailableException.class);

        assertThat(stockValue(sku, "reserved")).isZero();
        assertThat(stockValue(sku, "version")).isZero();
        assertThat(reservationCount(orderId)).isZero();
    }

    @Test
    void rollsBackEarlierSkuWhenLaterSkuIsUnavailable() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String firstSku = insertStock("TEST-A-" + suffix, 5);
        String secondSku = insertStock("TEST-B-" + suffix, 1);
        UUID orderId = UUID.randomUUID();
        List<ReservationLine> lines = List.of(
            new ReservationLine(secondSku, 2), new ReservationLine(firstSku, 3)
        );

        assertThatThrownBy(() -> reservationService.reserve(orderId, lines))
            .isInstanceOf(StockUnavailableException.class);

        assertThat(stockValue(firstSku, "reserved")).isZero();
        assertThat(stockValue(firstSku, "version")).isZero();
        assertThat(stockValue(secondSku, "reserved")).isZero();
        assertThat(reservationCount(orderId)).isZero();
    }

    @Test
    void combinesDuplicateSkuLinesIntoOneReservation() {
        String sku = insertStock(5);
        UUID orderId = UUID.randomUUID();

        reservationService.reserve(orderId, List.of(
            new ReservationLine(sku, 2), new ReservationLine(sku, 3)
        ));

        assertThat(stockValue(sku, "reserved")).isEqualTo(5);
        assertThat(stockValue(sku, "version")).isEqualTo(1);
        assertThat(reservationQuantity(orderId, sku)).isEqualTo(5);
        assertThat(reservationCount(orderId)).isEqualTo(1);
    }

    @Test
    void rejectsUnknownSkuWithoutWritingReservation() {
        UUID orderId = UUID.randomUUID();

        assertThatThrownBy(() -> reservationService.reserve(orderId, List.of(
            new ReservationLine("MISSING-" + UUID.randomUUID().toString().substring(0, 8), 1)
        ))).isInstanceOf(StockUnavailableException.class);

        assertThat(reservationCount(orderId)).isZero();
    }

    @Test
    void reservesExactlyAvailableStockUnderConcurrentRequests() throws Exception {
        String sku = insertStock(10);
        int attemptCount = 50;
        CountDownLatch ready = new CountDownLatch(attemptCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int attempt = 0; attempt < attemptCount; attempt++) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        reservationService.reserve(
                            UUID.randomUUID(), List.of(new ReservationLine(sku, 1))
                        );
                        return true;
                    } catch (StockUnavailableException expected) {
                        return false;
                    }
                }));
            }

            try {
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            } finally {
                start.countDown();
            }

            int successes = 0;
            for (Future<Boolean> result : results) {
                if (result.get(30, TimeUnit.SECONDS)) {
                    successes++;
                }
            }
            assertThat(successes).isEqualTo(10);
        }

        assertThat(stockValue(sku, "on_hand")).isEqualTo(10);
        assertThat(stockValue(sku, "reserved")).isEqualTo(10);
        assertThat(stockValue(sku, "version")).isEqualTo(10);
        assertThat(jdbc.queryForObject(
            "select count(*) from inventory.reservation where sku = ?", Integer.class, sku
        )).isEqualTo(10);
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

    private int reservationQuantity(UUID orderId, String sku) {
        return jdbc.queryForObject("""
            select quantity from inventory.reservation where order_id = ? and sku = ?
            """, Integer.class, orderId, sku);
    }

    private int reservationCount(UUID orderId) {
        return jdbc.queryForObject(
            "select count(*) from inventory.reservation where order_id = ?", Integer.class, orderId
        );
    }
}
