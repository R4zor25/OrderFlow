package com.orderflow.inventory.stock;

public record StockLevel(String sku, int onHand, int reserved) {

    public int available() {
        return onHand - reserved;
    }
}
