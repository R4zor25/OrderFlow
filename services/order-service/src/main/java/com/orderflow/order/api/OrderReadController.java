package com.orderflow.order.api;

import java.util.UUID;

import com.orderflow.order.application.OrderQueryService;
import com.orderflow.order.domain.OrderStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderReadController {

    private final OrderQueryService orders;

    public OrderReadController(OrderQueryService orders) {
        this.orders = orders;
    }

    @GetMapping("/{id}")
    public OrderResponse get(@PathVariable UUID id) {
        return OrderResponse.from(orders.get(id));
    }

    @GetMapping
    public OrderPageResponse list(@RequestParam(required = false) UUID customerId,
                                  @RequestParam(required = false) OrderStatus status,
                                  @RequestParam(defaultValue = "0") int page,
                                  @RequestParam(defaultValue = "20") int size) {
        return OrderPageResponse.from(orders.list(customerId, status, page, size));
    }
}
