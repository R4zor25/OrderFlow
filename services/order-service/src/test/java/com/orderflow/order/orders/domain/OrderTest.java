package com.orderflow.order.orders.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.orderflow.order.customer.Customer;
import com.orderflow.order.customer.CustomerType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderTest {

    private final Customer customer = Customer.create(
        UUID.fromString("33333333-3333-4333-8333-333333333333"),
        "Sample Business Standard", "standard@example.test", CustomerType.B2B, "B2B_STANDARD"
    );

    @Test
    void placesPendingOrderWithConsistentLinesAndTotals() {
        Order order = Order.place(customer, List.of(
            new Order.PricedLine("CHAIR-001", 2, new BigDecimal("84.90")),
            new Order.PricedLine("LAMP-002", 1, new BigDecimal("44.90"))
        ), new BigDecimal("57.97"), Instant.parse("2026-09-25T00:00:00Z"));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.getCurrency()).isEqualTo("EUR");
        assertThat(order.getTotalNet()).isEqualByComparingTo("214.70");
        assertThat(order.getTotalVat()).isEqualByComparingTo("57.97");
        assertThat(order.getTotalGross()).isEqualByComparingTo("272.67");
        assertThat(order.getLines()).hasSize(2);
        assertThat(order.getLines().getFirst().getOrder()).isSameAs(order);
        assertThat(order.getLines().getFirst().getLineTotalNet()).isEqualByComparingTo("169.80");
    }

    @Test
    void rejectsEmptyOrders() {
        assertThatThrownBy(() -> Order.place(customer, List.of(), new BigDecimal("0.00"), Instant.now()))
            .isInstanceOf(InvalidOrderException.class);
    }

    @Test
    void rejectsNonPositiveQuantity() {
        assertThatThrownBy(() -> Order.place(customer, List.of(
            new Order.PricedLine("CHAIR-001", 0, new BigDecimal("84.90"))
        ), new BigDecimal("0.00"), Instant.now()))
            .isInstanceOf(InvalidOrderException.class);
    }

    @Test
    void recordsIdempotencyKeyOnce() {
        Order order = Order.place(customer, List.of(
            new Order.PricedLine("CHAIR-001", 1, new BigDecimal("84.90"))), new BigDecimal("22.92"), Instant.now());

        order.recordIdempotencyKey("key-1", "a".repeat(64));

        assertThat(order.getIdempotencyKey()).isEqualTo("key-1");
        assertThat(order.getRequestHash()).isEqualTo("a".repeat(64));
        assertThatThrownBy(() -> order.recordIdempotencyKey("key-2", "b".repeat(64)))
            .isInstanceOf(InvalidOrderStateException.class);
    }

    @Test
    void rejectsInvalidIdempotencyKeyOrHash() {
        Order order = Order.place(customer, List.of(
            new Order.PricedLine("CHAIR-001", 1, new BigDecimal("84.90"))), new BigDecimal("22.92"), Instant.now());

        assertThatThrownBy(() -> order.recordIdempotencyKey(" ", "a".repeat(64)))
            .isInstanceOf(InvalidOrderException.class);
        assertThatThrownBy(() -> order.recordIdempotencyKey("k".repeat(129), "a".repeat(64)))
            .isInstanceOf(InvalidOrderException.class);
        assertThatThrownBy(() -> order.recordIdempotencyKey("key-1", "a".repeat(63)))
            .isInstanceOf(InvalidOrderException.class);
    }

    @Test
    void rejectsLineTotalAboveSupportedAmount() {
        assertThatThrownBy(() -> Order.place(customer, List.of(
            new Order.PricedLine("CHAIR-001", 2, new BigDecimal("9999999999.99"))), new BigDecimal("0.00"), Instant.now()))
            .isInstanceOf(InvalidOrderException.class)
            .hasMessage("Order line total exceeds the supported amount");
    }

    @Test
    void rejectsGrossTotalAboveSupportedAmount() {
        assertThatThrownBy(() -> Order.place(customer, List.of(
            new Order.PricedLine("CHAIR-001", 1, new BigDecimal("9999999999.00"))), new BigDecimal("1.00"), Instant.now()))
            .isInstanceOf(InvalidOrderException.class)
            .hasMessage("Order total exceeds the supported amount");
    }

    @Test
    void acceptsTotalsAtTheSupportedAmount() {
        Order order = Order.place(customer, List.of(
            new Order.PricedLine("CHAIR-001", 1, new BigDecimal("9999999998.99"))), new BigDecimal("1.00"), Instant.now());

        assertThat(order.getTotalGross()).isEqualByComparingTo("9999999999.99");
    }
}
