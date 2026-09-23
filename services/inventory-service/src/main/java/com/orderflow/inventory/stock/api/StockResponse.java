package com.orderflow.inventory.stock.api;

import com.orderflow.inventory.stock.domain.StockLevel;

public record StockResponse(String sku, int onHand, int reserved, int available) {

    static StockResponse from(StockLevel level) {
        return new StockResponse(level.sku(), level.onHand(), level.reserved(), level.available());
    }
}
