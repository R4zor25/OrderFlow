package com.orderflow.order;

import com.orderflow.order.customer.Customer;
import com.orderflow.order.customer.CustomerRepository;
import com.orderflow.order.events.EventEnvelope;
import com.orderflow.order.events.OrderCancelledPayload;
import com.orderflow.order.orders.application.CancelOrderService;
import com.orderflow.order.orders.application.OrderAccess;
import com.orderflow.order.orders.application.OrderQueryService;
import com.orderflow.order.orders.domain.InvalidOrderStateException;
import com.orderflow.order.orders.domain.Order;
import com.orderflow.order.orders.domain.OrderNotFoundException;
import com.orderflow.order.orders.domain.OrderStatus;
import com.orderflow.order.orders.infrastructure.OrderRepository;
import com.orderflow.order.outbox.OrderOutboxWriter;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.OptimisticLockException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.ArgumentMatchers.any;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class CancelOrderIntegrationTest {

    private static final Instant CREATED = Instant.parse("2026-09-25T10:00:00Z");
    private static final Instant CANCELLED = Instant.parse("2026-09-25T11:00:00Z");

    @Autowired
    private CancelOrderService cancellation;

    @Autowired
    private OrderQueryService queries;

    @Autowired
    private OrderRepository orders;

    @Autowired
    private CustomerRepository customers;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper json;

    @MockitoBean
    private Clock clock;

    @MockitoSpyBean
    private OrderOutboxWriter outbox;

    @Test
    void cancelsPendingOrderAndPersistsNewVersion() {
        UUID id = createOrder();
        when(clock.instant()).thenReturn(CANCELLED);

        var result = cancellation.cancel(id, OrderAccess.admin());

        assertThat(result.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(result.updatedAt()).isEqualTo(CANCELLED);
        assertThat(result.lines()).hasSize(1);
        assertThat(queries.get(id, OrderAccess.admin()).status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(orders.findById(id).orElseThrow().getVersion()).isEqualTo(1L);
        assertThatThrownBy(() -> cancellation.cancel(id, OrderAccess.admin()))
            .isInstanceOf(InvalidOrderStateException.class);
    }

    @Test
    void cancellingOrderPersistsAnEventWithPreviousStatus() {
        UUID id = createOrder();
        when(clock.instant()).thenReturn(CANCELLED);

        cancellation.cancel(id, OrderAccess.admin());

        String message = jdbc.queryForObject("""
            select payload::text from orders.outbox_event
            where aggregate_id = ? and event_type = 'OrderCancelled'
            """, String.class, id);
        EventEnvelope<OrderCancelledPayload> event = json.readValue(message, new TypeReference<>() {});
        assertThat(event.eventType()).isEqualTo("OrderCancelled");
        assertThat(event.occurredAt()).isEqualTo(CANCELLED);
        assertThat(event.aggregateId()).isEqualTo(id.toString());
        assertThat(event.payload().orderId()).isEqualTo(id);
        assertThat(event.payload().previousStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(event.payload().customerEmail()).isEqualTo("standard@example.test");
    }

    @Test
    void cancelsConfirmedOrderButRejectsRejectedOrder() {
        UUID confirmedId = createOrder();
        UUID rejectedId = createOrder();
        transactions.executeWithoutResult(status -> {
            orders.findById(confirmedId).orElseThrow().confirm(CREATED.plusSeconds(60));
            orders.findById(rejectedId).orElseThrow().reject("No stock", CREATED.plusSeconds(60));
        });
        when(clock.instant()).thenReturn(CANCELLED);

        assertThat(cancellation.cancel(confirmedId, OrderAccess.admin()).status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(cancelEvent(confirmedId).payload().previousStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThatThrownBy(() -> cancellation.cancel(rejectedId, OrderAccess.admin()))
            .isInstanceOf(InvalidOrderStateException.class);
        assertThat(queries.get(rejectedId, OrderAccess.admin()).status()).isEqualTo(OrderStatus.REJECTED);
        assertThat(jdbc.queryForObject("""
            select count(*) from orders.outbox_event where aggregate_id = ?
            """, Integer.class, rejectedId)).isZero();
    }

    @Test
    void outboxFailureRollsBackCancellation() {
        UUID id = createOrder();
        when(clock.instant()).thenReturn(CANCELLED);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("Failed after outbox insert");
        }).when(outbox).cancelled(any(), any(), any());

        assertThatThrownBy(() -> cancellation.cancel(id, OrderAccess.admin()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Failed after outbox insert");

        assertThat(queries.get(id, OrderAccess.admin()).status()).isEqualTo(OrderStatus.PENDING);
        assertThat(orders.findById(id).orElseThrow().getVersion()).isZero();
        assertThat(jdbc.queryForObject("""
            select count(*) from orders.outbox_event where aggregate_id = ?
            """, Integer.class, id)).isZero();
    }

    @Test
    void missingOrderDoesNotCreateAnything() {
        assertThatThrownBy(() -> cancellation.cancel(UUID.randomUUID(), OrderAccess.admin()))
            .isInstanceOf(OrderNotFoundException.class);
    }

    @Test
    void customerCannotCancelAnotherCustomersOrder() {
        UUID id = createOrder();
        UUID other = UUID.fromString("44444444-4444-4444-8444-444444444444");

        assertThatThrownBy(() -> cancellation.cancel(id, OrderAccess.customer(other)))
            .isInstanceOf(OrderNotFoundException.class);

        assertThat(queries.get(id, OrderAccess.admin()).status()).isEqualTo(OrderStatus.PENDING);
        assertThat(jdbc.queryForObject("""
            select count(*) from orders.outbox_event where aggregate_id = ?
            """, Integer.class, id)).isZero();
    }

    @Test
    void staleTransactionCannotOverwriteCommittedTransition() {
        UUID id = createOrder();
        Order stale = transactions.execute(status -> orders.findById(id).orElseThrow());

        transactions.executeWithoutResult(status ->
            orders.findById(id).orElseThrow().confirm(CREATED.plusSeconds(60)));
        stale.cancel(CANCELLED);

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> orders.saveAndFlush(stale)))
            .isInstanceOfAny(OptimisticLockingFailureException.class, OptimisticLockException.class);
        assertThat(queries.get(id, OrderAccess.admin()).status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(orders.findById(id).orElseThrow().getVersion()).isEqualTo(1L);
    }

    private UUID createOrder() {
        return transactions.execute(status -> {
            Customer customer = customers.findById(UUID.fromString("33333333-3333-4333-8333-333333333333"))
                .orElseThrow();
            Order order = Order.place(customer, List.of(
                new Order.PricedLine("CHAIR-001", 1, new BigDecimal("84.90"))),
                new BigDecimal("22.92"), CREATED);
            return orders.saveAndFlush(order).getId();
        });
    }

    private EventEnvelope<OrderCancelledPayload> cancelEvent(UUID id) {
        String message = jdbc.queryForObject("""
            select payload::text from orders.outbox_event
            where aggregate_id = ? and event_type = 'OrderCancelled'
            """, String.class, id);
        return json.readValue(message, new TypeReference<>() {});
    }
}
