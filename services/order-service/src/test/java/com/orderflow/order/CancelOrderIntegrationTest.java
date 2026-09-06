package com.orderflow.order;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.orderflow.order.application.CancelOrderService;
import com.orderflow.order.application.OrderQueryService;
import com.orderflow.order.domain.Customer;
import com.orderflow.order.domain.InvalidOrderStateException;
import com.orderflow.order.domain.Order;
import com.orderflow.order.domain.OrderNotFoundException;
import com.orderflow.order.domain.OrderStatus;
import com.orderflow.order.infrastructure.CustomerRepository;
import com.orderflow.order.infrastructure.OrderRepository;
import jakarta.persistence.OptimisticLockException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

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

    @MockitoBean
    private Clock clock;

    @Test
    void cancelsPendingOrderAndPersistsNewVersion() {
        UUID id = createOrder();
        when(clock.instant()).thenReturn(CANCELLED);

        var result = cancellation.cancel(id);

        assertThat(result.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(result.updatedAt()).isEqualTo(CANCELLED);
        assertThat(result.lines()).hasSize(1);
        assertThat(queries.get(id).status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(orders.findById(id).orElseThrow().getVersion()).isEqualTo(1);
        assertThatThrownBy(() -> cancellation.cancel(id))
            .isInstanceOf(InvalidOrderStateException.class);
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

        assertThat(cancellation.cancel(confirmedId).status()).isEqualTo(OrderStatus.CANCELLED);
        assertThatThrownBy(() -> cancellation.cancel(rejectedId))
            .isInstanceOf(InvalidOrderStateException.class);
        assertThat(queries.get(rejectedId).status()).isEqualTo(OrderStatus.REJECTED);
    }

    @Test
    void missingOrderDoesNotCreateAnything() {
        assertThatThrownBy(() -> cancellation.cancel(UUID.randomUUID()))
            .isInstanceOf(OrderNotFoundException.class);
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
        assertThat(queries.get(id).status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(orders.findById(id).orElseThrow().getVersion()).isEqualTo(1);
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
}
