package com.orderflow.inventory.events;

public record LowStockPayload(String sku, int available, int threshold) {
}
