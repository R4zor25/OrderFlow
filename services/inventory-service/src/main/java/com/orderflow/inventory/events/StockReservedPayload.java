package com.orderflow.inventory.events;

import java.util.List;
import java.util.UUID;

public record StockReservedPayload(UUID orderId, List<Line> lines) {

    public record Line(String sku, int quantity) {
    }
}
