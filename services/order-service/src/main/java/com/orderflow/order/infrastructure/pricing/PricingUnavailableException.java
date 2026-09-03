package com.orderflow.order.infrastructure.pricing;

public class PricingUnavailableException extends RuntimeException {

    public PricingUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
