package com.orderflow.order.pricing;

public class PricingUnavailableException extends RuntimeException {

    public PricingUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
