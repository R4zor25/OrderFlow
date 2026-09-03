package com.orderflow.order.infrastructure.pricing;

import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpServerErrorException;

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
}
