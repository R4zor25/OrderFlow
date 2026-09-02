package com.orderflow.catalog_pricing_service.price.application;

import java.util.List;

public class UnknownSkusException extends RuntimeException {

    private final List<String> skus;

    public UnknownSkusException(List<String> skus) {
        super("Unknown or inactive SKUs: " + String.join(", ", skus));
        this.skus = List.copyOf(skus);
    }

    public List<String> getSkus() {
        return skus;
    }
}
