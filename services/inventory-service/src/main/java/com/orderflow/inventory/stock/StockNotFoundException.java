package com.orderflow.inventory.stock;

public class StockNotFoundException extends RuntimeException {

    public StockNotFoundException(String sku) {
        super("No stock item for SKU " + sku);
    }
}
