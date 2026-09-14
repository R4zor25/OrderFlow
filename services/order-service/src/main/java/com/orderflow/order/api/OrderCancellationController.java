package com.orderflow.order.api;

import java.util.UUID;

import com.orderflow.order.application.CancelOrderService;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderCancellationController {

    private final CancelOrderService cancellation;
    private final OrderAccessResolver access;

    public OrderCancellationController(CancelOrderService cancellation, OrderAccessResolver access) {
        this.cancellation = cancellation;
        this.access = access;
    }

    @PostMapping("/{id}/cancel")
    public OrderResponse cancel(@PathVariable UUID id, JwtAuthenticationToken authentication) {
        return OrderResponse.from(cancellation.cancel(id, access.access(authentication)));
    }
}
