package com.orderflow.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.orderflow.order.domain.Customer;
import com.orderflow.order.domain.Order;
import com.orderflow.order.infrastructure.CustomerRepository;
import com.orderflow.order.infrastructure.OrderRepository;
import com.orderflow.order.infrastructure.events.EventEnvelope;
import com.orderflow.order.infrastructure.events.OrderConfirmedPayload;
import com.orderflow.order.infrastructure.events.OrderRejectedPayload;
import com.orderflow.order.infrastructure.outbox.OrderOutboxWriter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class OrderOutboxWriterIntegrationTest {

    private static final Instant CREATED = Instant.parse("2026-09-25T10:00:00Z");
    private static final Instant CHANGED = Instant.parse("2026-09-25T11:00:00Z");

    @Autowired
    private CustomerRepository customers;

    @Autowired
    private OrderRepository orders;

    @Autowired
    private OrderOutboxWriter outbox;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper json;

    @Test
    void confirmedOrderPayloadMatchesPersistedState() {
        UUID id = createOrder();

        transactions.executeWithoutResult(status -> {
            Order order = orders.findAllWithCustomerAndLines(List.of(id)).getFirst();
            order.confirm(CHANGED);
            orders.flush();
            outbox.confirmed(order, CHANGED);
        });

        EventEnvelope<OrderConfirmedPayload> event = readEvent(id, "OrderConfirmed", new TypeReference<>() {});
        assertThat(event.payload().orderId()).isEqualTo(id);
        assertThat(event.payload().customerEmail()).isEqualTo("standard@example.test");
        assertThat(event.payload().totalGross()).isEqualByComparingTo("107.82");
        assertThat(event.payload().currency()).isEqualTo("EUR");
        assertThat(event.occurredAt()).isEqualTo(CHANGED);
    }

    @Test
    void rejectedOrderPayloadContainsPersistedReason() {
        UUID id = createOrder();

        transactions.executeWithoutResult(status -> {
            Order order = orders.findAllWithCustomerAndLines(List.of(id)).getFirst();
            order.reject("No stock", CHANGED);
            orders.flush();
            outbox.rejected(order, CHANGED);
        });

        EventEnvelope<OrderRejectedPayload> event = readEvent(id, "OrderRejected", new TypeReference<>() {});
        assertThat(event.payload().orderId()).isEqualTo(id);
        assertThat(event.payload().reason()).isEqualTo("No stock");
        assertThat(event.payload().customerEmail()).isEqualTo("standard@example.test");
        assertThat(event.occurredAt()).isEqualTo(CHANGED);
    }

    @Test
    void writesEmptyPropagationHeadersWhenNoSpanIsActive() {
        UUID id = createOrder();
        transactions.executeWithoutResult(status -> {
            Order order = orders.findAllWithCustomerAndLines(List.of(id)).getFirst();
            outbox.placed(order, CREATED);
        });
        String headers = jdbc.queryForObject(
            "select headers::text from orders.outbox_event where aggregate_id = ?", String.class, id);
        assertThat(json.readTree(headers).isEmpty()).isTrue();
    }

    @Test
    void refusesOutboxWriteOutsideBusinessTransaction() {
        UUID id = createOrder();
        Order order = transactions.execute(status -> orders.findAllWithCustomerAndLines(List.of(id)).getFirst());

        assertThatThrownBy(() -> outbox.placed(order, CREATED))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Outbox write requires an active transaction");
        assertThat(jdbc.queryForObject("""
            select count(*) from orders.outbox_event where aggregate_id = ?
            """, Integer.class, id)).isZero();
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

    private <T> EventEnvelope<T> readEvent(UUID id, String type, TypeReference<EventEnvelope<T>> valueType) {
        String message = jdbc.queryForObject("""
            select payload::text from orders.outbox_event
            where aggregate_id = ? and event_type = ? and published_at is null
            """, String.class, id, type);
        return json.readValue(message, valueType);
    }
}
