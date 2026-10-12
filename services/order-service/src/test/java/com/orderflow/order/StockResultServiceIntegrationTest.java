package com.orderflow.order;

import com.orderflow.order.application.OrderAccess;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.orderflow.order.application.CancelOrderService;
import com.orderflow.order.application.StockResultService;
import com.orderflow.order.domain.InvalidOrderStateException;
import com.orderflow.order.domain.Order;
import com.orderflow.order.domain.OrderStatus;
import com.orderflow.order.infrastructure.CustomerRepository;
import com.orderflow.order.infrastructure.OrderRepository;
import com.orderflow.order.infrastructure.events.EventEnvelope;
import com.orderflow.order.infrastructure.events.OrderConfirmedPayload;
import com.orderflow.order.infrastructure.events.OrderRejectedPayload;
import com.orderflow.order.infrastructure.outbox.OutboxWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class StockResultServiceIntegrationTest {

    private static final Instant CREATED = Instant.parse("2026-10-01T10:15:30Z");
    private static final Instant DECIDED = Instant.parse("2026-10-01T10:15:45Z");
    private static final UUID CUSTOMER_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");

    @Autowired
    private StockResultService results;

    @Autowired
    private CancelOrderService cancellation;

    @Autowired
    private CustomerRepository customers;

    @Autowired
    private OrderRepository orders;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private TransactionTemplate transactions;

    @MockitoBean
    private Clock clock;

    @MockitoSpyBean
    private OutboxWriter outbox;

    @BeforeEach
    void fixedTime() {
        when(clock.instant()).thenReturn(DECIDED);
    }

    @Test
    void confirmsOnceAndWritesOrderConfirmedAfterDuplicateStockReserved() {
        UUID orderId = createOrder();
        UUID eventId = UUID.randomUUID();

        results.reserved(eventId, orderId);
        results.reserved(eventId, orderId);

        var order = orders.findById(orderId).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.getUpdatedAt()).isEqualTo(DECIDED);
        assertThat(order.getVersion()).isEqualTo(1L);
        assertThat(processedCount(eventId)).isEqualTo(1);
        assertThat(outboxCount(orderId, "OrderConfirmed")).isEqualTo(1);
        EventEnvelope<OrderConfirmedPayload> message = readEvent(orderId, new TypeReference<>() {});
        assertThat(message.payload().orderId()).isEqualTo(orderId);
        assertThat(message.payload().customerEmail()).isEqualTo("standard@example.test");
        assertThat(message.occurredAt()).isEqualTo(DECIDED);
    }

    @Test
    void rejectsOnceAndWritesOrderRejectedAfterDuplicateStockReservationFailed() {
        UUID orderId = createOrder();
        UUID eventId = UUID.randomUUID();

        results.failed(eventId, orderId, "Insufficient stock");
        results.failed(eventId, orderId, "Insufficient stock");

        var order = orders.findById(orderId).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.REJECTED);
        assertThat(order.getRejectionReason()).isEqualTo("Insufficient stock");
        assertThat(processedCount(eventId)).isEqualTo(1);
        assertThat(outboxCount(orderId, "OrderRejected")).isEqualTo(1);
        EventEnvelope<OrderRejectedPayload> message = readEvent(orderId, new TypeReference<>() {});
        assertThat(message.payload().reason()).isEqualTo("Insufficient stock");
        assertThat(message.payload().customerEmail()).isEqualTo("standard@example.test");
    }

    @Test
    void ignoresLateStockResultForCancelledOrder() {
        UUID orderId = createOrder();
        cancellation.cancel(orderId, OrderAccess.admin());
        UUID eventId = UUID.randomUUID();

        results.reserved(eventId, orderId);

        assertThat(orders.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(processedCount(eventId)).isEqualTo(1);
        assertThat(outboxCount(orderId, "OrderConfirmed")).isZero();
    }

    @Test
    void rollsBackStatusAndProcessedMarkerWhenOutboxWriteFails() {
        UUID orderId = createOrder();
        UUID eventId = UUID.randomUUID();
        doThrow(new IllegalStateException("Outbox unavailable")).when(outbox).write(any());

        assertThatThrownBy(() -> results.reserved(eventId, orderId))
            .isInstanceOf(IllegalStateException.class);

        assertThat(orders.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(processedCount(eventId)).isZero();
        assertThat(outboxCount(orderId, "OrderConfirmed")).isZero();
    }

    @Test
    void leavesContradictoryResultUnprocessedForInvestigation() {
        UUID orderId = createOrder();
        results.reserved(UUID.randomUUID(), orderId);
        UUID failedEventId = UUID.randomUUID();

        assertThatThrownBy(() -> results.failed(failedEventId, orderId, "Insufficient stock"))
            .isInstanceOf(InvalidOrderStateException.class);

        assertThat(orders.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(processedCount(failedEventId)).isZero();
        assertThat(outboxCount(orderId, "OrderRejected")).isZero();
    }

    private UUID createOrder() {
        return transactions.execute(status -> {
            var customer = customers.findById(CUSTOMER_ID).orElseThrow();
            var order = Order.place(customer,
                List.of(new Order.PricedLine("CHAIR-001", 1, new BigDecimal("84.90"))),
                new BigDecimal("22.92"), CREATED);
            return orders.saveAndFlush(order).getId();
        });
    }

    private int processedCount(UUID eventId) {
        return jdbc.queryForObject("select count(*) from orders.processed_event where event_id = ?",
            Integer.class, eventId);
    }

    private int outboxCount(UUID orderId, String type) {
        return jdbc.queryForObject("""
            select count(*) from orders.outbox_event where aggregate_id = ? and event_type = ?
            """, Integer.class, orderId, type);
    }

    private <T> EventEnvelope<T> readEvent(UUID orderId, TypeReference<EventEnvelope<T>> type) {
        String body = jdbc.queryForObject("""
            select payload::text from orders.outbox_event where aggregate_id = ?
            and event_type in ('OrderConfirmed', 'OrderRejected')
            """, String.class, orderId);
        return json.readValue(body, type);
    }
}
