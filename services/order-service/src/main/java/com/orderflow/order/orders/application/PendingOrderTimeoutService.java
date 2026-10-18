package com.orderflow.order.orders.application;

import java.time.Clock;
import java.time.Instant;

import com.orderflow.order.orders.domain.OrderStatus;
import com.orderflow.order.orders.infrastructure.OrderRepository;
import com.orderflow.order.orders.infrastructure.OrderSagaProperties;
import com.orderflow.order.outbox.OrderOutboxWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PendingOrderTimeoutService {

    private static final Logger log = LoggerFactory.getLogger(PendingOrderTimeoutService.class);
    private static final int BATCH_SIZE = 100;

    private final OrderRepository orders;
    private final OrderOutboxWriter outbox;
    private final OrderSagaProperties properties;
    private final Clock clock;

    public PendingOrderTimeoutService(OrderRepository orders, OrderOutboxWriter outbox,
                                      OrderSagaProperties properties, Clock clock) {
        this.orders = orders;
        this.outbox = outbox;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public int cancelExpired() {
        Instant now = clock.instant();
        var expired = orders.lockExpiredPendingIds(now.minus(properties.pendingTimeout()), BATCH_SIZE);
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
