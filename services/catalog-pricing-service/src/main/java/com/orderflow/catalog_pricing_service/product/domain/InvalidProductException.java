package com.orderflow.catalog_pricing_service.product.domain;

public class InvalidProductException extends RuntimeException {

    public InvalidProductException(String message) {
        super(message);
    }
}
