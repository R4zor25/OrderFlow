package com.orderflow.order.application;

import java.time.Clock;
import java.util.UUID;

import com.orderflow.order.domain.Order;
import com.orderflow.order.domain.OrderNotFoundException;
import com.orderflow.order.domain.OrderStatus;
import com.orderflow.order.infrastructure.OrderRepository;
import com.orderflow.order.infrastructure.events.ProcessedEventRepository;
import com.orderflow.order.infrastructure.outbox.OrderOutboxWriter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StockResultService {

    private final ProcessedEventRepository processedEvents;
    private final OrderRepository orders;
    private final OrderOutboxWriter outbox;
    private final Clock clock;

    public StockResultService(ProcessedEventRepository processedEvents, OrderRepository orders,
                              OrderOutboxWriter outbox, Clock clock) {
        this.processedEvents = processedEvents;
        this.orders = orders;
        this.outbox = outbox;
        this.clock = clock;
    }

    @Transactional
    public void reserved(UUID eventId, UUID orderId) {
        if (!processedEvents.markIfNew(eventId)) {
            return;
        }
        Order order = findOrder(orderId);
        if (order.getStatus() == OrderStatus.CANCELLED) {
            return;
        }
        var now = clock.instant();
        order.confirm(now);
        orders.flush();
        outbox.confirmed(order, now);
    }

    @Transactional
    public void failed(UUID eventId, UUID orderId, String reason) {
        if (!processedEvents.markIfNew(eventId)) {
            return;
        }
        Order order = findOrder(orderId);
        if (order.getStatus() == OrderStatus.CANCELLED) {
            return;
        }
        var now = clock.instant();
        order.reject(reason, now);
        orders.flush();
        outbox.rejected(order, now);
    }

    private Order findOrder(UUID orderId) {
        return orders.findById(orderId)
            .orElseThrow(() -> new OrderNotFoundException("Order not found: " + orderId));
    }
}
