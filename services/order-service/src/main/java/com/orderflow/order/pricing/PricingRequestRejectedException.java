package com.orderflow.order.pricing;

import org.springframework.http.HttpStatusCode;

public class PricingRequestRejectedException extends RuntimeException {

    private final HttpStatusCode status;

    public PricingRequestRejectedException(HttpStatusCode status, Throwable cause) {
        super("Pricing rejected quote request with status " + status.value(), cause);
        this.status = status;
    }

    public HttpStatusCode getStatus() {
        return status;
    }
}
