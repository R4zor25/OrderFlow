package com.orderflow.order.orders.api;

import java.util.UUID;

import com.orderflow.order.orders.application.OrderQueryService;
import com.orderflow.order.orders.domain.OrderStatus;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderReadController {

    private final OrderQueryService orders;
    private final OrderAccessResolver access;

    public OrderReadController(OrderQueryService orders, OrderAccessResolver access) {
        this.orders = orders;
        this.access = access;
    }

    @GetMapping("/{id}")
    public OrderResponse get(@PathVariable UUID id, JwtAuthenticationToken authentication) {
        return OrderResponse.from(orders.get(id, access.access(authentication)));
    }

    @GetMapping
    public OrderPageResponse list(JwtAuthenticationToken authentication,
                                  @RequestParam(required = false) UUID customerId,
                                  @RequestParam(required = false) OrderStatus status,
                                  @RequestParam(defaultValue = "0") int page,
                                  @RequestParam(defaultValue = "20") int size) {
        return OrderPageResponse.from(orders.list(access.access(authentication), customerId, status, page, size));
    }
}
