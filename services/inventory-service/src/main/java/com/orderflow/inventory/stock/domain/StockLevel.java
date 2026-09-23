package com.orderflow.inventory.stock.domain;

public record StockLevel(String sku, int onHand, int reserved) {

    public int available() {
        return onHand - reserved;
    }
}
