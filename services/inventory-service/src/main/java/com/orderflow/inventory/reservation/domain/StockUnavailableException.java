package com.orderflow.inventory.reservation.domain;

public class StockUnavailableException extends RuntimeException {

    private final String sku;

    public StockUnavailableException(String sku) {
        super("Stock is unavailable for SKU " + sku);
        this.sku = sku;
    }

    public String sku() {
        return sku;
    }
}
