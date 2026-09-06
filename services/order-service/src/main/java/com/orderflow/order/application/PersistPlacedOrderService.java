package com.orderflow.order.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.orderflow.order.domain.Customer;
import com.orderflow.order.domain.Order;
import com.orderflow.order.infrastructure.OrderRepository;
import com.orderflow.order.infrastructure.outbox.OrderOutboxWriter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PersistPlacedOrderService {

    private final OrderRepository orders;
    private final OrderOutboxWriter outbox;

    public PersistPlacedOrderService(OrderRepository orders, OrderOutboxWriter outbox) {
        this.orders = orders;
        this.outbox = outbox;
    }

    @Transactional
    public PlaceOrderResult persist(Customer customer, List<Order.PricedLine> pricedLines,
                                    BigDecimal totalVat, Instant now) {
        Order order = orders.saveAndFlush(Order.place(customer, pricedLines, totalVat, now));
        outbox.placed(order, now);
        return PlaceOrderResult.from(order);
    }
}
