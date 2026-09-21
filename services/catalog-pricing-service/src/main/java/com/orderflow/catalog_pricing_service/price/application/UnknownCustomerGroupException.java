package com.orderflow.catalog_pricing_service.price.application;

public class UnknownCustomerGroupException extends RuntimeException {

    public UnknownCustomerGroupException(String code) {
        super("Unknown customer group: " + code);
    }
}
