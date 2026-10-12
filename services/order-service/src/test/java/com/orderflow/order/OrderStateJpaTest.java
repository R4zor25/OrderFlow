package com.orderflow.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

import com.orderflow.order.domain.Customer;
import com.orderflow.order.domain.Order;
import com.orderflow.order.domain.OrderStatus;
import com.orderflow.order.infrastructure.CustomerRepository;
import com.orderflow.order.infrastructure.OrderRepository;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestConfiguration.class)
class OrderStateJpaTest {

    private final CustomerRepository customers;
    private final OrderRepository orders;
    private final EntityManager entityManager;

    @Autowired
    OrderStateJpaTest(CustomerRepository customers, OrderRepository orders, EntityManager entityManager) {
        this.customers = customers;
        this.orders = orders;
        this.entityManager = entityManager;
    }

    @Test
    void persistsRejectionReasonAndUpdatedTime() {
        Customer customer = customers.findById(UUID.fromString("33333333-3333-4333-8333-333333333333"))
            .orElseThrow();
        Order order = Order.place(customer, List.of(
            new Order.PricedLine("CHAIR-001", 1, new BigDecimal("84.90"))
        ), new BigDecimal("22.92"), Instant.parse("2026-09-25T09:00:00Z"));
        order = orders.saveAndFlush(order);
        Instant rejectedAt = Instant.parse("2026-09-25T09:10:00Z");
        order.reject("No stock", rejectedAt);
        orders.flush();
        entityManager.clear();

        Order loaded = orders.findById(order.getId()).orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo(OrderStatus.REJECTED);
        assertThat(loaded.getRejectionReason()).isEqualTo("No stock");
        assertThat(loaded.getUpdatedAt()).isEqualTo(rejectedAt);
        assertThat(loaded.getVersion()).isEqualTo(1L);
    }
}
