package com.orderflow.order.orders.api;

import java.net.URI;

import com.orderflow.order.orders.application.IdempotentPlaceOrderService;
import com.orderflow.order.orders.application.PlaceOrderCommand;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    private final IdempotentPlaceOrderService placeOrderService;
    private final OrderAccessResolver access;

    public OrderController(IdempotentPlaceOrderService placeOrderService, OrderAccessResolver access) {
        this.placeOrderService = placeOrderService;
        this.access = access;
    }

    @PostMapping
    public ResponseEntity<PlaceOrderResponse> place(
        JwtAuthenticationToken authentication,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @Valid @RequestBody PlaceOrderRequest request) {
        var lines = request.lines().stream()
            .map(line -> new PlaceOrderCommand.Line(line.sku(), line.quantity()))
            .toList();
        var result = placeOrderService.place(idempotencyKey,
            new PlaceOrderCommand(access.customerId(authentication), lines));
        return ResponseEntity.accepted()
            .location(URI.create("/api/v1/orders/" + result.id()))
            .body(PlaceOrderResponse.from(result));
    }
}
