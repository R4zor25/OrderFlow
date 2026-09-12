package com.orderflow.order.application;

import java.time.Clock;
import java.util.UUID;

import com.orderflow.order.domain.Order;
import com.orderflow.order.domain.OrderNotFoundException;
import com.orderflow.order.domain.OrderStatus;
import com.orderflow.order.infrastructure.OrderRepository;
import com.orderflow.order.infrastructure.metrics.OrderBusinessMetrics;
import com.orderflow.order.infrastructure.events.ProcessedEventRepository;
import com.orderflow.order.infrastructure.outbox.OrderOutboxWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StockResultService {

    private static final Logger log = LoggerFactory.getLogger(StockResultService.class);

    private final ProcessedEventRepository processedEvents;
    private final OrderRepository orders;
    private final OrderOutboxWriter outbox;
    private final Clock clock;
    private final OrderBusinessMetrics metrics;

    public StockResultService(ProcessedEventRepository processedEvents, OrderRepository orders,
                              OrderOutboxWriter outbox, Clock clock, OrderBusinessMetrics metrics) {
        this.processedEvents = processedEvents;
        this.orders = orders;
        this.outbox = outbox;
        this.clock = clock;
        this.metrics = metrics;
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
        log.info("Order confirmed orderId={} eventId={}", orderId, eventId);
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
        metrics.rejectedAfterCommit(order.getCustomer().getType());
        log.info("Order rejected orderId={} eventId={}", orderId, eventId);
    }

    private Order findOrder(UUID orderId) {
        return orders.findById(orderId)
            .orElseThrow(() -> new OrderNotFoundException("Order not found: " + orderId));
    }
}
