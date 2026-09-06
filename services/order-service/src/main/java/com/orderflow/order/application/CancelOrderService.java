package com.orderflow.order.application;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import com.orderflow.order.domain.OrderNotFoundException;
import com.orderflow.order.infrastructure.OrderRepository;
import com.orderflow.order.infrastructure.outbox.OrderOutboxWriter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CancelOrderService {

    private final OrderRepository orders;
    private final OrderOutboxWriter outbox;
    private final Clock clock;

    public CancelOrderService(OrderRepository orders, OrderOutboxWriter outbox, Clock clock) {
        this.orders = orders;
        this.outbox = outbox;
        this.clock = clock;
    }

    @Transactional
    public OrderDetails cancel(UUID id) {
        var matches = orders.findAllWithCustomerAndLines(List.of(id));
        if (matches.isEmpty()) {
            throw new OrderNotFoundException("Order not found: " + id);
        }
        var order = matches.getFirst();
        var previousStatus = order.getStatus();
        var now = clock.instant();
        order.cancel(now);
        orders.flush();
        outbox.cancelled(order, previousStatus, now);
        return OrderDetails.from(order);
    }
}
