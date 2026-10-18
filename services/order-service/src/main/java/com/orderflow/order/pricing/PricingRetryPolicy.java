package com.orderflow.order.pricing;

import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;

import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.web.client.RestClientResponseException;

public final class PricingRetryPolicy {

    private PricingRetryPolicy() {
    }

    public static boolean isRetryable(Throwable error) {
        if (!(error instanceof PricingUnavailableException)) {
            return false;
        }
        for (Throwable cause = error.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof OAuth2AuthorizationException) {
                return false;
            }
            if (cause instanceof RestClientResponseException response) {
                return response.getStatusCode().is5xxServerError();
            }
            if (cause instanceof HttpConnectTimeoutException || cause instanceof ConnectException) {
                return true;
            }
            if (cause instanceof HttpTimeoutException) {
                return false;
            }
        }
        return false;
    }
}
