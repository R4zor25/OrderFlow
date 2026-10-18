package com.orderflow.order.pricing;

import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import static org.assertj.core.api.Assertions.assertThat;

class PricingRetryPolicyTest {

    @Test
    void retriesServerErrorWrappedByPricingGateway() {
        var error = new PricingUnavailableException("Pricing service returned an error",
            new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE));

        assertThat(PricingRetryPolicy.isRetryable(error)).isTrue();
    }

    @Test
    void retriesConnectionRefusedWrappedByPricingGateway() {
        var error = new PricingUnavailableException("Pricing service could not be reached",
            new ConnectException("Connection refused"));

        assertThat(PricingRetryPolicy.isRetryable(error)).isTrue();
    }

    @Test
    void retriesConnectTimeoutBeforeApplyingGeneralTimeoutRule() {
        var error = new PricingUnavailableException("Pricing service could not be reached",
            new HttpConnectTimeoutException("Connect timed out"));

        assertThat(PricingRetryPolicy.isRetryable(error)).isTrue();
    }

    @Test
    void doesNotRetryReadTimeout() {
        var error = new PricingUnavailableException("Pricing service could not be reached",
            new HttpTimeoutException("Read timed out"));

        assertThat(PricingRetryPolicy.isRetryable(error)).isFalse();
    }

    @Test
    void doesNotRetryClientRejection() {
        var error = new PricingRequestRejectedException(HttpStatus.UNPROCESSABLE_CONTENT,
            new IllegalArgumentException("Invalid quote request"));

        assertThat(PricingRetryPolicy.isRetryable(error)).isFalse();
    }

    @Test
    void doesNotRetryUnclassifiedFailure() {
        assertThat(PricingRetryPolicy.isRetryable(new IllegalStateException("Unexpected failure")))
            .isFalse();
    }

    @Test
    void doesNotRetryTokenAcquisitionFailure() {
        var failure = new PricingUnavailableException("Pricing credentials unavailable",
            new OAuth2AuthorizationException(new OAuth2Error("invalid_token_response"),
                new ResourceAccessException("down", new ConnectException("refused"))));

        assertThat(PricingRetryPolicy.isRetryable(failure)).isFalse();
    }
}
