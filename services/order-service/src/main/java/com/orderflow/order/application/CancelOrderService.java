package com.orderflow.order.application;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import com.orderflow.order.domain.OrderNotFoundException;
import com.orderflow.order.infrastructure.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CancelOrderService {

    private final OrderRepository orders;
    private final Clock clock;

    public CancelOrderService(OrderRepository orders, Clock clock) {
        this.orders = orders;
        this.clock = clock;
    }

    @Transactional
    public OrderDetails cancel(UUID id) {
        var matches = orders.findAllWithCustomerAndLines(List.of(id));
        if (matches.isEmpty()) {
            throw new OrderNotFoundException("Order not found: " + id);
        }
        var order = matches.getFirst();
        order.cancel(clock.instant());
        orders.flush();
        return OrderDetails.from(order);
    }
}
