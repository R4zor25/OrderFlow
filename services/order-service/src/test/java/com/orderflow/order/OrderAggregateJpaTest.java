package com.orderflow.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceUnitUtil;

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
class OrderAggregateJpaTest {

    private final CustomerRepository customers;
    private final OrderRepository orders;
    private final EntityManager entityManager;
    private final PersistenceUnitUtil persistenceUnitUtil;

    @Autowired
    OrderAggregateJpaTest(CustomerRepository customers, OrderRepository orders,
                          EntityManager entityManager, jakarta.persistence.EntityManagerFactory factory) {
        this.customers = customers;
        this.orders = orders;
        this.entityManager = entityManager;
        this.persistenceUnitUtil = factory.getPersistenceUnitUtil();
    }

    @Test
    void persistsOrderAndLinesThroughAggregateRoot() {
        Customer customer = customers.findById(UUID.fromString("33333333-3333-4333-8333-333333333333"))
            .orElseThrow();
        Order order = Order.place(customer, List.of(
            new Order.PricedLine("CHAIR-001", 2, new BigDecimal("84.90"))
        ), new BigDecimal("45.85"), Instant.parse("2026-09-25T00:00:00Z"));
        UUID id = orders.saveAndFlush(order).getId();
        entityManager.clear();

        Order loaded = orders.findById(id).orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(loaded.getVersion()).isZero();
        assertThat(persistenceUnitUtil.isLoaded(loaded, "lines")).isFalse();
        assertThat(loaded.getLines()).hasSize(1);
        assertThat(loaded.getLines().getFirst().getOrder().getId()).isEqualTo(id);
        assertThat(loaded.getTotalGross()).isEqualByComparingTo("215.65");
    }
}
