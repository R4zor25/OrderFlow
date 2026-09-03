package com.orderflow.order.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

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
}
