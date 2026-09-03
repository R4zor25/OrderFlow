package com.orderflow.order.api;

import java.net.URI;
import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.orderflow.order.domain.InvalidOrderException;
import com.orderflow.order.domain.InvalidOrderStateException;
import com.orderflow.order.config.PricingResilienceProperties;
import com.orderflow.order.infrastructure.pricing.PricingUnavailableException;
import com.orderflow.order.infrastructure.pricing.PricingRequestRejectedException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler(new PricingResilienceProperties(
        2, Duration.ofMillis(100), 2, 0.2, 10, 10, 50, Duration.ofSeconds(10), 2));

    @Test
    void mapsForbiddenTransitionToConflict() {
        var problem = handler.invalidOrderState(
            new InvalidOrderStateException("Cannot cancel order in REJECTED state")
        );

        assertThat(problem.getStatus()).isEqualTo(409);
        assertThat(problem.getType()).isEqualTo(URI.create("/problems/invalid-order-state"));
        assertThat(problem.getDetail()).contains("REJECTED");
    }

    @Test
    void mapsInvalidReasonToUnprocessableContent() {
        var problem = handler.invalidOrder(new InvalidOrderException("Rejection reason is required"));

        assertThat(problem.getStatus()).isEqualTo(422);
        assertThat(problem.getType()).isEqualTo(URI.create("/problems/invalid-order"));
    }

    @Test
    void mapsOpenPricingCircuitToServiceUnavailableWithRetryAfter() {
        CircuitBreaker breaker = CircuitBreaker.ofDefaults("pricing");
        var response = handler.pricingCircuitOpen(CallNotPermittedException.createCallNotPermittedException(breaker));

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("10");
        assertThat(response.getBody().getType()).isEqualTo(URI.create("/problems/pricing-circuit-open"));
    }

    @Test
    void mapsPricingFailureToServiceUnavailableWithoutRetryHint() {
        var problem = handler.pricingUnavailable(new PricingUnavailableException("failed", new RuntimeException()));

        assertThat(problem.getStatus()).isEqualTo(503);
        assertThat(problem.getType()).isEqualTo(URI.create("/problems/pricing-unavailable"));
    }

    @Test
    void mapsPricingRequestRejectionToUnprocessableContent() {
        var problem = handler.pricingRequestRejected(
            new PricingRequestRejectedException(HttpStatus.UNPROCESSABLE_CONTENT, new RuntimeException()));

        assertThat(problem.getStatus()).isEqualTo(422);
        assertThat(problem.getType()).isEqualTo(URI.create("/problems/pricing-request-rejected"));
    }
}
