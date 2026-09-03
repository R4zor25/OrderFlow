package com.orderflow.order.api;

import java.net.URI;

import com.orderflow.order.config.PricingResilienceProperties;
import com.orderflow.order.domain.InvalidOrderException;
import com.orderflow.order.domain.InvalidOrderStateException;
import com.orderflow.order.infrastructure.pricing.PricingRequestRejectedException;
import com.orderflow.order.infrastructure.pricing.PricingUnavailableException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    private final PricingResilienceProperties pricingResilience;

    public ApiExceptionHandler(PricingResilienceProperties pricingResilience) {
        this.pricingResilience = pricingResilience;
    }

    @ExceptionHandler(InvalidOrderStateException.class)
    public ProblemDetail invalidOrderState(InvalidOrderStateException exception) {
        return problem(HttpStatus.CONFLICT, "invalid-order-state", "Invalid order state", exception.getMessage());
    }

    @ExceptionHandler(InvalidOrderException.class)
    public ProblemDetail invalidOrder(InvalidOrderException exception) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "invalid-order", "Invalid order", exception.getMessage());
    }

    @ExceptionHandler(PricingRequestRejectedException.class)
    public ProblemDetail pricingRequestRejected(PricingRequestRejectedException exception) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "pricing-request-rejected",
            "Pricing request rejected", "The pricing service rejected the quote request");
    }

    @ExceptionHandler(PricingUnavailableException.class)
    public ProblemDetail pricingUnavailable(PricingUnavailableException exception) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "pricing-unavailable",
            "Pricing unavailable", "The pricing service is temporarily unavailable");
    }

    @ExceptionHandler(CallNotPermittedException.class)
    public ResponseEntity<ProblemDetail> pricingCircuitOpen(CallNotPermittedException exception) {
        ProblemDetail body = problem(HttpStatus.SERVICE_UNAVAILABLE, "pricing-circuit-open",
            "Pricing unavailable", "Pricing requests are temporarily paused");
        long retryAfterSeconds = Math.max(1, (pricingResilience.openWait().toMillis() + 999) / 1000);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .header("Retry-After", Long.toString(retryAfterSeconds))
            .body(body);
    }

    private ProblemDetail problem(HttpStatus status, String kind, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("/problems/" + kind));
        problem.setTitle(title);
        return problem;
    }
}
