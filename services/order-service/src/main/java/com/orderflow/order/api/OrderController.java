package com.orderflow.order.api;

import java.net.URI;

import com.orderflow.order.application.PlaceOrderCommand;
import com.orderflow.order.application.IdempotentPlaceOrderService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    private final IdempotentPlaceOrderService placeOrderService;

    public OrderController(IdempotentPlaceOrderService placeOrderService) {
        this.placeOrderService = placeOrderService;
    }

    @PostMapping
    public ResponseEntity<PlaceOrderResponse> place(
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @Valid @RequestBody PlaceOrderRequest request) {
        var lines = request.lines().stream()
            .map(line -> new PlaceOrderCommand.Line(line.sku(), line.quantity()))
            .toList();
        var result = placeOrderService.place(idempotencyKey,
            new PlaceOrderCommand(request.customerId(), lines));
        return ResponseEntity.accepted()
            .location(URI.create("/api/v1/orders/" + result.id()))
            .body(PlaceOrderResponse.from(result));
    }
}
