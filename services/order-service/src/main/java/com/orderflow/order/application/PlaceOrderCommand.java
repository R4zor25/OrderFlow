package com.orderflow.order.application;

import java.util.List;
import java.util.UUID;

public record PlaceOrderCommand(UUID customerId, List<Line> lines) {

    public record Line(String sku, int quantity) {
    }
}
