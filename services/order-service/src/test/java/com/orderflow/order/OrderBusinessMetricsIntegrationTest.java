package com.orderflow.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.orderflow.order.application.CancelOrderService;
import com.orderflow.order.application.PersistPlacedOrderService;
import com.orderflow.order.application.StockResultService;
import com.orderflow.order.domain.Order;
import com.orderflow.order.domain.OrderStatus;
import com.orderflow.order.infrastructure.CustomerRepository;
import com.orderflow.order.infrastructure.OrderRepository;
import com.orderflow.order.infrastructure.pricing.PricingGateway;
import com.orderflow.order.infrastructure.pricing.QuoteLineResponse;
import com.orderflow.order.infrastructure.pricing.QuoteResponse;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@AutoConfigureMetrics
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({PostgresTestConfiguration.class, RedisTestConfiguration.class})
@MockitoBean(types = PricingGateway.class)
class OrderBusinessMetricsIntegrationTest {

    private static final UUID B2B = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID B2C = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final Instant NOW = Instant.parse("2026-10-03T12:00:00Z");
    private final PersistPlacedOrderService placement;
    private final StockResultService results;
    private final CancelOrderService cancellation;
    private final CustomerRepository customers;
    private final OrderRepository orders;
    private final MeterRegistry registry;
    private final TransactionTemplate transactions;
    private final JdbcTemplate jdbc;
    private final Environment environment;
    private final PricingGateway pricing;

    @Autowired
    OrderBusinessMetricsIntegrationTest(PersistPlacedOrderService placement, StockResultService results,
            CancelOrderService cancellation, CustomerRepository customers, OrderRepository orders,
            MeterRegistry registry, PlatformTransactionManager transactionManager, JdbcTemplate jdbc,
            Environment environment, PricingGateway pricing) {
        this.placement = placement;
        this.results = results;
        this.cancellation = cancellation;
        this.customers = customers;
        this.orders = orders;
        this.registry = registry;
        this.transactions = new TransactionTemplate(transactionManager);
        this.jdbc = jdbc;
        this.environment = environment;
        this.pricing = pricing;
    }

    @Test
    void countsPlacedOrderOnlyAfterOuterTransactionCommits() {
        double before = placed("B2B");
        UUID id = transactions.execute(status -> {
            UUID orderId = place(B2B);
            assertThat(placed("B2B")).isEqualTo(before);
            return orderId;
        });
        assertThat(placed("B2B")).isEqualTo(before + 1);
        assertThat(orders.findById(id)).isPresent();
    }

    @Test
    void doesNotCountRolledBackPlacement() {
        double before = placed("B2B");
        UUID id = transactions.execute(status -> {
            UUID orderId = place(B2B);
            status.setRollbackOnly();
            return orderId;
        });
        assertThat(placed("B2B")).isEqualTo(before);
        assertThat(orders.findById(id)).isEmpty();
    }

    @Test
    void separatesCustomerTypesWithoutCreatingCustomerIdSeries() {
        double b2b = placed("B2B");
        double b2c = placed("B2C");
        transactions.executeWithoutResult(status -> place(B2C));
        assertThat(placed("B2C")).isEqualTo(b2c + 1);
        assertThat(placed("B2B")).isEqualTo(b2b);
        assertThat(registry.find("orders.placed").counters()).hasSize(2);
        assertThat(registry.find("orders.placed").counters())
            .allSatisfy(counter -> assertThat(counter.getId().getTags())
                .extracting(tag -> tag.getKey()).containsExactly("customerType"));
    }

    @Test
    void countsCommittedRejectionOnceDespiteDuplicateEventAndFreeFormReason() {
        UUID id = transactions.execute(status -> place(B2B));
        UUID eventId = UUID.randomUUID();
        double before = rejected();
        transactions.executeWithoutResult(status -> {
            results.failed(eventId, id, "Insufficient stock for arbitrary SKU");
            assertThat(rejected()).isEqualTo(before);
        });
        results.failed(eventId, id, "Another arbitrary reason");
        assertThat(rejected()).isEqualTo(before + 1);
        assertThat(orders.findById(id).orElseThrow().getStatus()).isEqualTo(OrderStatus.REJECTED);
        assertThat(registry.find("orders.rejected").counters()).hasSize(2);
    }

    @Test
    void doesNotCountRolledBackRejectionOrKeepItsProcessedMarker() {
        UUID id = transactions.execute(status -> place(B2B));
        UUID eventId = UUID.randomUUID();
        double before = rejected();
        transactions.executeWithoutResult(status -> {
            results.failed(eventId, id, "Insufficient stock");
            status.setRollbackOnly();
        });
        assertThat(rejected()).isEqualTo(before);
        assertThat(orders.findById(id).orElseThrow().getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(jdbc.queryForObject("select count(*) from orders.processed_event where event_id = ?",
            Integer.class, eventId)).isZero();
        results.failed(eventId, id, "Insufficient stock");
        assertThat(rejected()).isEqualTo(before + 1);
    }

    @Test
    void doesNotCountLateFailureForCancelledOrder() {
        UUID id = transactions.execute(status -> place(B2B));
        cancellation.cancel(id);
        double before = rejected();
        results.failed(UUID.randomUUID(), id, "Insufficient stock");
        assertThat(rejected()).isEqualTo(before);
        assertThat(orders.findById(id).orElseThrow().getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void doesNotCountHttpIdempotencyReplayAsAnotherOrder() {
        when(pricing.quote(any())).thenReturn(new QuoteResponse("B2B_STANDARD", "EUR", List.of(
            new QuoteLineResponse("CHAIR-001", 1, new BigDecimal("84.90"), new BigDecimal("84.90"),
                new BigDecimal("22.92"), null, "BASE")), new BigDecimal("84.90"),
            new BigDecimal("22.92"), new BigDecimal("107.82"), NOW));
        String key = UUID.randomUUID().toString();
        double before = placed("B2B");
        String first = postOrder(key);
        String replay = postOrder(key);
        assertThat(replay).isEqualTo(first);
        assertThat(placed("B2B")).isEqualTo(before + 1);
    }

    @Test
    void exposesBusinessCountersAndExistingOutboxGaugeThroughHttpPrometheusEndpoint() {
        String scrape = client().get().uri("/actuator/prometheus").retrieve().body(String.class);
        assertThat(scrape).contains("# TYPE orders_placed_total counter",
            "orders_placed_total{customerType=\"B2B\"}",
            "orders_placed_total{customerType=\"B2C\"}",
            "orders_rejected_total{customerType=\"B2B\",reason=\"stock_reservation_failed\"}",
            "# TYPE outbox_lag_seconds gauge");
        assertThat(scrape).doesNotContain("customerId=", "orderId=", "sku=");
        String placedLine = scrape.lines()
            .filter(line -> line.startsWith("orders_placed_total{customerType=\"B2B\"}"))
            .findFirst().orElseThrow();
        assertThat(Double.parseDouble(placedLine.substring(placedLine.indexOf('}') + 1).trim()))
            .isEqualTo(placed("B2B"));
    }

    private UUID place(UUID customerId) {
        return placement.persist(customers.findById(customerId).orElseThrow(),
            List.of(new Order.PricedLine("CHAIR-001", 1, new BigDecimal("84.90"))),
            new BigDecimal("22.92"), NOW).id();
    }

    private double placed(String type) {
        var counter = registry.find("orders.placed").tag("customerType", type).counter();
        return counter == null ? 0 : counter.count();
    }

    private double rejected() {
        var counter = registry.find("orders.rejected").tags("customerType", "B2B",
            "reason", "stock_reservation_failed").counter();
        return counter == null ? 0 : counter.count();
    }

    private RestClient client() {
        return RestClient.create("http://localhost:" + environment.getRequiredProperty("local.server.port"));
    }

    private String postOrder(String key) {
        var response = client().post().uri("/api/v1/orders").header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON).body("""
                {"customerId":"33333333-3333-4333-8333-333333333333",
                 "lines":[{"sku":"CHAIR-001","quantity":1}]}
                """).retrieve().toEntity(String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(202);
        return response.getBody();
    }
}
