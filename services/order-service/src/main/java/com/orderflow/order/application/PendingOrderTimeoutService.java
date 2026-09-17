package com.orderflow.order.application;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import com.orderflow.order.config.OrderSagaProperties;
import com.orderflow.order.domain.OrderStatus;
import com.orderflow.order.infrastructure.OrderRepository;
import com.orderflow.order.infrastructure.outbox.OrderOutboxWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PendingOrderTimeoutService {

    private static final Logger log = LoggerFactory.getLogger(PendingOrderTimeoutService.class);

    private final JdbcTemplate jdbc;
    private final OrderRepository orders;
    private final OrderOutboxWriter outbox;
    private final OrderSagaProperties properties;
    private final Clock clock;

    public PendingOrderTimeoutService(JdbcTemplate jdbc, OrderRepository orders, OrderOutboxWriter outbox,
                                      OrderSagaProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.orders = orders;
        this.outbox = outbox;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public int cancelExpired() {
        Instant now = clock.instant();
        var expired = jdbc.queryForList("""
            select id from orders.orders
            where status = 'PENDING' and created_at < ?
            order by created_at, id
            limit 100
            for update skip locked
            """, UUID.class, Timestamp.from(now.minus(properties.pendingTimeout())));
        if (expired.isEmpty()) {
            return 0;
        }
        for (var order : orders.findAllWithCustomerAndLines(expired)) {
            order.cancel(now);
            orders.flush();
            outbox.cancelled(order, OrderStatus.PENDING, now);
            log.warn("Cancelled order after pending timeout orderId={} pendingSince={}",
                order.getId(), order.getCreatedAt());
        }
        return expired.size();
    }
}
