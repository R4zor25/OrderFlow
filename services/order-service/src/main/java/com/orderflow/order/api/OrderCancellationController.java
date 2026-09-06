package com.orderflow.order.api;

import java.util.UUID;

import com.orderflow.order.application.CancelOrderService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderCancellationController {

    private final CancelOrderService cancellation;

    public OrderCancellationController(CancelOrderService cancellation) {
        this.cancellation = cancellation;
    }

    @PostMapping("/{id}/cancel")
    public OrderResponse cancel(@PathVariable UUID id) {
        return OrderResponse.from(cancellation.cancel(id));
    }
}
