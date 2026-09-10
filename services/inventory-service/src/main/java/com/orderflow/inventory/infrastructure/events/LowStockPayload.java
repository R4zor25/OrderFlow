package com.orderflow.inventory.infrastructure.events;

public record LowStockPayload(String sku, int available, int threshold) {
}
