package com.orderflow.order;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.orderflow.order.customer.CustomerRepository;
import com.orderflow.order.orders.application.PendingOrderTimeoutService;
import com.orderflow.order.orders.domain.Order;
import com.orderflow.order.orders.domain.OrderStatus;
import com.orderflow.order.orders.infrastructure.OrderRepository;
import com.orderflow.order.orders.infrastructure.PendingOrderTimeoutJob;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class PendingOrderTimeoutIntegrationTest {

    private static final UUID CUSTOMER_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");

    @Autowired
    private PendingOrderTimeoutService timeouts;

    @Autowired
    private PendingOrderTimeoutJob job;

    @Autowired
    private CustomerRepository customers;

    @Autowired
    private OrderRepository orders;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MeterRegistry meters;

    @Test
    void cancelsOnlyExpiredPendingOrders() {
        UUID expired = createOrder(Instant.now().minus(Duration.ofMinutes(16)), false);
        UUID fresh = createOrder(Instant.now().minus(Duration.ofMinutes(5)), false);
        UUID confirmed = createOrder(Instant.now().minus(Duration.ofMinutes(30)), true);

        int cancelled = drainExpired();

        assertThat(cancelled).isGreaterThanOrEqualTo(1);
        assertThat(orders.findById(expired).orElseThrow().getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(orders.findById(fresh).orElseThrow().getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(orders.findById(confirmed).orElseThrow().getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(jdbc.queryForObject("""
            select payload -> 'payload' ->> 'previousStatus' from orders.outbox_event
            where aggregate_id = ? and event_type = 'OrderCancelled'
            """, String.class, expired)).isEqualTo("PENDING");
    }

    @Test
    void jobRunsUnderTheSharedDatabaseLock() {
        job.run();

        assertThat(jdbc.queryForObject("""
            select count(*) from orders.shedlock where name = 'pending-order-timeout'
            """, Integer.class)).isEqualTo(1);
    }

    @Test
    void reportsTheAgeOfTheOldestPendingOrder() {
        drainExpired();
        createOrder(Instant.now().minus(Duration.ofMinutes(14)), false);

        double age = meters.get("orders.pending.oldest.age.seconds").gauge().value();

        assertThat(age).isBetween(840.0, 900.0);
    }

    private int drainExpired() {
        int total = 0;
        int cancelled;
        while ((cancelled = timeouts.cancelExpired()) > 0) {
            total += cancelled;
        }
        return total;
    }

    private UUID createOrder(Instant createdAt, boolean confirm) {
        return transactions.execute(status -> {
            var customer = customers.findById(CUSTOMER_ID).orElseThrow();
            var order = Order.place(customer,
                List.of(new Order.PricedLine("CHAIR-001", 1, new BigDecimal("84.90"))),
                new BigDecimal("22.92"), createdAt);
            if (confirm) {
                order.confirm(createdAt);
            }
            return orders.saveAndFlush(order).getId();
        });
    }
}
