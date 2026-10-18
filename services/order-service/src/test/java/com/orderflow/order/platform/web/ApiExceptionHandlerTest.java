package com.orderflow.order.platform.web;

import java.net.URI;
import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;

import com.orderflow.order.customer.CustomerNotFoundException;
import com.orderflow.order.orders.domain.InvalidOrderException;
import com.orderflow.order.orders.domain.InvalidOrderStateException;
import com.orderflow.order.pricing.PricingRequestRejectedException;
import com.orderflow.order.pricing.PricingUnavailableException;
import com.orderflow.order.pricing.config.PricingResilienceProperties;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
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

    @Test
    void mapsMissingCustomerToNotFound() {
        var problem = handler.customerNotFound(new CustomerNotFoundException(UUID.randomUUID()));

        assertThat(problem.getStatus()).isEqualTo(404);
        assertThat(problem.getType()).isEqualTo(URI.create("/problems/customer-not-found"));
    }

    @Test
    void logsSchemaIdentifiersButNotStoredValuesForDataIntegrityViolation(CapturedOutput output) {
        var violation = new DataIntegrityViolationException("could not execute statement",
            new ConstraintViolationException("could not execute statement",
                new SQLException("ERROR: duplicate key value violates unique constraint; Key (email)=(buyer@example.test)",
                    "23505"),
                "uq_customer_email"));

        var problem = handler.dataIntegrity(violation);

        assertThat(problem.getStatus()).isEqualTo(500);
        assertThat(output.getOut()).contains("sqlState=23505").contains("constraint=uq_customer_email")
            .doesNotContain("buyer@example.test");
    }
}
