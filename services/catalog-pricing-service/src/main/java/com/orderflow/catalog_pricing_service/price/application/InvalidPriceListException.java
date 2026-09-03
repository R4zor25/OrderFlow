package com.orderflow.catalog_pricing_service.price.application;

public class InvalidPriceListException extends RuntimeException {

    public InvalidPriceListException(String message) {
        super(message);
    }
}
