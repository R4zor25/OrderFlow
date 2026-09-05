package com.orderflow.order.api;

import java.net.URI;

import com.orderflow.order.application.PlaceOrderService;
import com.orderflow.order.application.PlaceOrderCommand;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    private final PlaceOrderService placeOrderService;

    public OrderController(PlaceOrderService placeOrderService) {
        this.placeOrderService = placeOrderService;
    }

    @PostMapping
    public ResponseEntity<PlaceOrderResponse> place(@Valid @RequestBody PlaceOrderRequest request) {
        var lines = request.lines().stream()
            .map(line -> new PlaceOrderCommand.Line(line.sku(), line.quantity()))
            .toList();
        var result = placeOrderService.place(new PlaceOrderCommand(request.customerId(), lines));
        return ResponseEntity.accepted()
            .location(URI.create("/api/v1/orders/" + result.id()))
            .body(PlaceOrderResponse.from(result));
    }
}
