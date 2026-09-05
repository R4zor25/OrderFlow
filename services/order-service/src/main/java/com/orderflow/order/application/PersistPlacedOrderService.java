package com.orderflow.order.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.orderflow.order.domain.Customer;
import com.orderflow.order.domain.Order;
import com.orderflow.order.infrastructure.OrderRepository;
import com.orderflow.order.infrastructure.outbox.OrderPlacedOutboxWriter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PersistPlacedOrderService {

    private final OrderRepository orders;
    private final OrderPlacedOutboxWriter outbox;

    public PersistPlacedOrderService(OrderRepository orders, OrderPlacedOutboxWriter outbox) {
        this.orders = orders;
        this.outbox = outbox;
    }

    @Transactional
    public PlaceOrderResult persist(Customer customer, List<Order.PricedLine> pricedLines,
                                    BigDecimal totalVat, Instant now) {
        Order order = orders.saveAndFlush(Order.place(customer, pricedLines, totalVat, now));
        outbox.write(order, now);
        return PlaceOrderResult.from(order);
    }
}
