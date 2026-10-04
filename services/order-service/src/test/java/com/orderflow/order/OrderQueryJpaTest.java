package com.orderflow.order;

import com.orderflow.order.application.OrderAccess;
import com.orderflow.order.application.OrderDetails;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import com.orderflow.order.application.OrderQueryService;
import com.orderflow.order.domain.Customer;
import com.orderflow.order.domain.Order;
import com.orderflow.order.domain.OrderNotFoundException;
import com.orderflow.order.domain.OrderStatus;
import com.orderflow.order.infrastructure.CustomerRepository;
import com.orderflow.order.infrastructure.OrderRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true", showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PostgresTestConfiguration.class, OrderQueryService.class})
class OrderQueryJpaTest {

    @Autowired
    private CustomerRepository customers;

    @Autowired
    private OrderRepository orders;

    @Autowired
    private OrderQueryService queries;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private EntityManagerFactory factory;

    @Test
    void loadsPageOfOrdersAndLinesInThreeStatements() {
        Customer customer = customers.findById(UUID.fromString("33333333-3333-4333-8333-333333333333"))
            .orElseThrow();
        for (int i = 0; i < 10; i++) {
            orders.save(order(customer, i));
        }
        entityManager.flush();
        entityManager.clear();
        var statistics = factory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        var result = queries.list(OrderAccess.admin(), customer.getId(), OrderStatus.PENDING, 0, 5);

        assertThat(result.getContent()).hasSize(5)
            .allSatisfy(item -> assertThat(item.lines()).hasSize(2));
        assertThat(result.getTotalElements()).isEqualTo(10);
        assertThat(result.getTotalPages()).isEqualTo(2);
        assertThat(result.getContent().stream().map(item -> item.createdAt()).toList())
            .isSortedAccordingTo((left, right) -> right.compareTo(left));
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(3);

        var secondPage = queries.list(OrderAccess.admin(), customer.getId(), OrderStatus.PENDING, 1, 5);
        assertThat(secondPage.getContent()).hasSize(5);
        assertThat(secondPage.getContent().stream().map(item -> item.id()).toList())
            .doesNotContainAnyElementsOf(result.getContent().stream().map(item -> item.id()).toList());
    }

    @Test
    void filtersByCustomerAndStatusAndReturnsEmptyPage() {
        Customer standard = customers.findById(UUID.fromString("33333333-3333-4333-8333-333333333333"))
            .orElseThrow();
        Customer gold = customers.findById(UUID.fromString("44444444-4444-4444-8444-444444444444"))
            .orElseThrow();
        Order confirmed = order(standard, 1);
        confirmed.confirm(Instant.parse("2026-10-01T11:00:00Z"));
        orders.saveAll(List.of(confirmed, order(standard, 2), order(gold, 3)));
        entityManager.flush();
        entityManager.clear();

        var result = queries.list(OrderAccess.admin(), standard.getId(), OrderStatus.CONFIRMED, 0, 10);
        var empty = queries.list(OrderAccess.admin(), standard.getId(), OrderStatus.CONFIRMED, 1, 10);
        var unfiltered = queries.list(OrderAccess.admin(), null, null, 0, 10);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().getFirst().id()).isEqualTo(confirmed.getId());
        assertThat(result.getContent().getFirst().status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(empty.getContent()).isEmpty();
        assertThat(empty.getTotalElements()).isEqualTo(1);
        assertThat(unfiltered.getTotalElements()).isEqualTo(3);
    }

    @Test
    void listsEachFilterNewestFirstWithIdBreakingTies() {
        Customer standard = customers.findById(UUID.fromString("33333333-3333-4333-8333-333333333333"))
            .orElseThrow();
        Customer gold = customers.findById(UUID.fromString("44444444-4444-4444-8444-444444444444"))
            .orElseThrow();
        Order older = order(standard, 1);
        Order tiedFirst = order(standard, 2);
        Order tiedSecond = order(standard, 2);
        Order confirmed = order(standard, 3);
        confirmed.confirm(Instant.parse("2026-10-01T11:00:00Z"));
        Order goldOrder = order(gold, 4);
        orders.saveAll(List.of(older, tiedFirst, tiedSecond, confirmed, goldOrder));
        entityManager.flush();
        entityManager.clear();
        // PostgreSQL orders uuid values by their bytes, like their hex strings; UUID.compareTo uses signed longs.
        var tied = Stream.of(tiedFirst.getId(), tiedSecond.getId())
            .sorted(Comparator.comparing(UUID::toString).reversed()).toList();

        assertThat(ids(queries.list(OrderAccess.admin(), null, null, 0, 10)))
            .containsExactly(goldOrder.getId(), confirmed.getId(), tied.get(0), tied.get(1), older.getId());
        assertThat(ids(queries.list(OrderAccess.admin(), standard.getId(), null, 0, 10)))
            .containsExactly(confirmed.getId(), tied.get(0), tied.get(1), older.getId());
        assertThat(ids(queries.list(OrderAccess.admin(), null, OrderStatus.PENDING, 0, 10)))
            .containsExactly(goldOrder.getId(), tied.get(0), tied.get(1), older.getId());
        var standardPending = queries.list(OrderAccess.admin(), standard.getId(), OrderStatus.PENDING, 0, 2);
        assertThat(ids(standardPending)).containsExactly(tied.get(0), tied.get(1));
        assertThat(standardPending.getTotalElements()).isEqualTo(3);
        assertThat(ids(queries.list(OrderAccess.admin(), standard.getId(), OrderStatus.PENDING, 1, 2)))
            .containsExactly(older.getId());
    }

    private static List<UUID> ids(Page<OrderDetails> page) {
        return page.getContent().stream().map(item -> item.id()).toList();
    }

    @Test
    void loadsSingleOrderWithLinesAndReportsMissingOrder() {
        Customer customer = customers.findById(UUID.fromString("33333333-3333-4333-8333-333333333333"))
            .orElseThrow();
        UUID id = orders.saveAndFlush(order(customer, 1)).getId();
        entityManager.clear();

        var result = queries.get(id, OrderAccess.admin());

        assertThat(result.id()).isEqualTo(id);
        assertThat(result.lines()).hasSize(2);
        assertThatThrownBy(() -> queries.get(UUID.randomUUID(), OrderAccess.admin()))
            .isInstanceOf(OrderNotFoundException.class);
    }

    @Test
    void customerQueryHidesOtherCustomersOrder() {
        Customer customer = customers.findById(UUID.fromString("33333333-3333-4333-8333-333333333333"))
            .orElseThrow();
        UUID id = orders.saveAndFlush(order(customer, 1)).getId();
        entityManager.clear();
        UUID other = UUID.fromString("44444444-4444-4444-8444-444444444444");

        assertThatThrownBy(() -> queries.get(id, OrderAccess.customer(other)))
            .isInstanceOf(OrderNotFoundException.class);
        assertThat(queries.get(id, OrderAccess.customer(customer.getId())).id()).isEqualTo(id);
    }

    private static Order order(Customer customer, int index) {
        return Order.place(customer, List.of(
            new Order.PricedLine("CHAIR-001", 2, new BigDecimal("84.90")),
            new Order.PricedLine("DESK-001", 1, new BigDecimal("100.00"))),
            new BigDecimal("50.00"), Instant.parse("2026-10-01T10:00:00Z").plusSeconds(index));
    }
}
