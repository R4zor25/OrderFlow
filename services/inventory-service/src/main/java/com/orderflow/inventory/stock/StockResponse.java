package com.orderflow.inventory.stock;

public record StockResponse(String sku, int onHand, int reserved, int available) {

    static StockResponse from(StockLevel level) {
        return new StockResponse(level.sku(), level.onHand(), level.reserved(), level.available());
    }
}
