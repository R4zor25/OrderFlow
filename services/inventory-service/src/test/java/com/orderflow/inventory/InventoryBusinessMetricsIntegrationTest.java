package com.orderflow.inventory;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.orderflow.inventory.application.OrderPlacedProcessor;
import com.orderflow.inventory.application.ReservationOutcomeService;
import com.orderflow.inventory.infrastructure.events.EventEnvelope;
import com.orderflow.inventory.infrastructure.events.OrderPlacedPayload;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

@AutoConfigureMetrics
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestConfiguration.class)
class InventoryBusinessMetricsIntegrationTest {

    private final ReservationOutcomeService outcomes;
    private final OrderPlacedProcessor processor;
    private final MeterRegistry registry;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Environment environment;

    @Autowired
    InventoryBusinessMetricsIntegrationTest(ReservationOutcomeService outcomes, OrderPlacedProcessor processor,
            MeterRegistry registry, JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
            Environment environment) {
        this.outcomes = outcomes;
        this.processor = processor;
        this.registry = registry;
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.environment = environment;
    }

    @Test
    void countsFailureOnlyAfterItsOutboxAndProcessedMarkerCommit() {
        double before = failures();
        UUID eventId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        transactions.executeWithoutResult(status -> {
            outcomes.recordFailure(eventId, orderId, "MISSING-" + UUID.randomUUID().toString().substring(0, 8));
            assertThat(failures()).isEqualTo(before);
        });
        assertThat(failures()).isEqualTo(before + 1);
        assertThat(outboxCount(orderId)).isEqualTo(1);
    }

    @Test
    void doesNotCountRolledBackFailureAndCountsItsSuccessfulRetryOnce() {
        UUID eventId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        double before = failures();
        transactions.executeWithoutResult(status -> {
            outcomes.recordFailure(eventId, orderId, "MISSING");
            status.setRollbackOnly();
        });
        assertThat(failures()).isEqualTo(before);
        assertThat(outboxCount(orderId)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from inventory.processed_event where event_id = ?",
            Integer.class, eventId)).isZero();
        outcomes.recordFailure(eventId, orderId, "MISSING");
        outcomes.recordFailure(eventId, orderId, "DIFFERENT-SKU");
        assertThat(failures()).isEqualTo(before + 1);
        assertThat(outboxCount(orderId)).isEqualTo(1);
    }

    @Test
    void countsOneDurableFailureForDuplicateOrderPlacedWithUnavailableStock() {
        UUID eventId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        var event = new EventEnvelope<>(eventId, "OrderPlaced", 1, Instant.now(), orderId.toString(),
            new OrderPlacedPayload(orderId, UUID.randomUUID(), "B2B",
                List.of(new OrderPlacedPayload.Line("MISSING-" + UUID.randomUUID().toString().substring(0, 8), 1))));
        double before = failures();
        processor.process(event);
        processor.process(event);
        assertThat(failures()).isEqualTo(before + 1);
        assertThat(outboxCount(orderId)).isEqualTo(1);
        assertThat(registry.find("inventory.reservation.failed").counters()).hasSize(1);
    }

    @Test
    void successfulReservationDoesNotIncrementFailureCounter() {
        String sku = "METRIC-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("""
            insert into inventory.stock_item
                (sku, on_hand, reserved, low_stock_threshold, low_stock_notified, version, updated_at)
            values (?, 5, 0, 1, false, 0, now())
            """, sku);
        UUID orderId = UUID.randomUUID();
        var event = new EventEnvelope<>(UUID.randomUUID(), "OrderPlaced", 1, Instant.now(), orderId.toString(),
            new OrderPlacedPayload(orderId, UUID.randomUUID(), "B2B",
                List.of(new OrderPlacedPayload.Line(sku, 1))));
        double before = failures();
        processor.process(event);
        assertThat(failures()).isEqualTo(before);
        assertThat(jdbc.queryForObject("select reserved from inventory.stock_item where sku = ?",
            Integer.class, sku)).isEqualTo(1);
    }

    @Test
    void exposesBoundedFailureCounterThroughHttpPrometheusEndpoint() {
        String scrape = RestClient.create("http://localhost:" +
                environment.getRequiredProperty("local.server.port"))
            .get().uri("/actuator/prometheus").retrieve().body(String.class);
        assertThat(scrape).contains("# TYPE inventory_reservation_failed_total counter",
            "inventory_reservation_failed_total{reason=\"insufficient_stock\"}");
        assertThat(scrape).doesNotContain("orderId=", "customerId=", "sku=");
        String failureLine = scrape.lines()
            .filter(line -> line.startsWith("inventory_reservation_failed_total{reason=\"insufficient_stock\"}"))
            .findFirst().orElseThrow();
        assertThat(Double.parseDouble(failureLine.substring(failureLine.indexOf('}') + 1).trim()))
            .isEqualTo(failures());
    }

    private int outboxCount(UUID orderId) {
        return jdbc.queryForObject("select count(*) from inventory.outbox_event where aggregate_id = ?",
            Integer.class, orderId.toString());
    }

    private double failures() {
        var counter = registry.find("inventory.reservation.failed").tag("reason", "insufficient_stock").counter();
        return counter == null ? 0 : counter.count();
    }
}
