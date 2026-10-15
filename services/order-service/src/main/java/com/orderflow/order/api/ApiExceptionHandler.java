package com.orderflow.order.api;

import java.net.URI;
import java.sql.SQLException;
import java.util.Map;
import java.util.TreeMap;

import com.orderflow.order.config.PricingResilienceProperties;
import com.orderflow.order.domain.InvalidOrderException;
import com.orderflow.order.domain.InvalidOrderStateException;
import com.orderflow.order.domain.OrderAccessDeniedException;
import com.orderflow.order.domain.OrderNotFoundException;
import com.orderflow.order.domain.CustomerNotFoundException;
import com.orderflow.order.domain.IdempotencyException;
import com.orderflow.order.infrastructure.pricing.PricingRequestRejectedException;
import com.orderflow.order.infrastructure.pricing.PricingUnavailableException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import jakarta.persistence.OptimisticLockException;
import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private static final URI DEFAULT_TYPE = URI.create("about:blank");

    private final PricingResilienceProperties pricingResilience;

    public ApiExceptionHandler(PricingResilienceProperties pricingResilience) {
        this.pricingResilience = pricingResilience;
    }

    @ExceptionHandler(InvalidOrderStateException.class)
    public ProblemDetail invalidOrderState(InvalidOrderStateException exception) {
        return problem(HttpStatus.CONFLICT, "invalid-order-state", "Invalid order state", exception.getMessage());
    }

    @ExceptionHandler({OptimisticLockingFailureException.class, OptimisticLockException.class})
    public ProblemDetail orderModifiedConcurrently(Exception exception) {
        return problem(HttpStatus.CONFLICT, "order-modified-concurrently",
            "Order modified concurrently", "The order changed while this request was being processed; reload it before retrying");
    }

    @ExceptionHandler(InvalidOrderException.class)
    public ProblemDetail invalidOrder(InvalidOrderException exception) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "invalid-order", "Invalid order", exception.getMessage());
    }

    @ExceptionHandler(CustomerNotFoundException.class)
    public ProblemDetail customerNotFound(CustomerNotFoundException exception) {
        return problem(HttpStatus.NOT_FOUND, "customer-not-found", "Customer not found", exception.getMessage());
    }

    @ExceptionHandler(OrderNotFoundException.class)
    public ProblemDetail orderNotFound(OrderNotFoundException exception) {
        return problem(HttpStatus.NOT_FOUND, "order-not-found", "Order not found", exception.getMessage());
    }

    @ExceptionHandler(OrderAccessDeniedException.class)
    public ProblemDetail orderAccessDenied(OrderAccessDeniedException exception) {
        return problem(HttpStatus.FORBIDDEN, "forbidden", "Forbidden", exception.getMessage());
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException exception,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        ProblemDetail problem = problem(HttpStatus.UNPROCESSABLE_CONTENT, "invalid-order",
            "Invalid order", "The order request is invalid");
        Map<String, String> violations = new TreeMap<>();
        exception.getBindingResult().getFieldErrors().forEach(error -> violations.putIfAbsent(error.getField(),
            error.getDefaultMessage() == null ? "Invalid value" : error.getDefaultMessage()));
        problem.setProperty("violations", violations);
        return handleExceptionInternal(exception, problem, headers, HttpStatus.UNPROCESSABLE_CONTENT, request);
    }

    @Override
    protected ResponseEntity<Object> createResponseEntity(Object body, HttpHeaders headers, HttpStatusCode status,
                                                          WebRequest request) {
        // Framework errors arrive without a specific type (null or about:blank); the API promises stable relative types.
        if (body instanceof ProblemDetail problem
            && (problem.getType() == null || DEFAULT_TYPE.equals(problem.getType()))) {
            switch (status.value()) {
                case 400 -> {
                    problem.setType(URI.create("/problems/bad-request"));
                    problem.setTitle("Bad request");
                }
                case 404 -> {
                    problem.setType(URI.create("/problems/not-found"));
                    problem.setTitle("Not found");
                }
                default -> problem.setType(URI.create("/problems/http-error"));
            }
        }
        return super.createResponseEntity(body, headers, status, request);
    }

    @ExceptionHandler({CannotCreateTransactionException.class, DataAccessResourceFailureException.class,
        QueryTimeoutException.class})
    public ResponseEntity<ProblemDetail> databaseUnavailable(Exception exception) {
        // The most specific cause tells pool exhaustion apart from authentication or DNS failures.
        log.warn("Database unavailable cause={} rootCause={}", exception.getClass().getSimpleName(),
            NestedExceptionUtils.getMostSpecificCause(exception).getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .header("Retry-After", "5")
            .body(problem(HttpStatus.SERVICE_UNAVAILABLE, "database-unavailable", "Database unavailable",
                "The database is temporarily unavailable"));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail dataIntegrity(DataIntegrityViolationException exception) {
        // The constraint message can contain stored values, so only schema identifiers are logged.
        log.error("Unexpected data integrity violation sqlState={} constraint={} cause={}",
            sqlState(exception), constraintName(exception),
            exception.getMostSpecificCause().getClass().getSimpleName());
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "data-integrity", "Data integrity violation",
            "The request could not be stored");
    }

    private static String sqlState(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
        }
        return "unknown";
    }

    private static String constraintName(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation && violation.getConstraintName() != null) {
                return violation.getConstraintName();
            }
        }
        return "unknown";
    }

    @ExceptionHandler(IdempotencyException.class)
    public ProblemDetail idempotency(IdempotencyException exception) {
        return switch (exception.getReason()) {
            case KEY_REUSED -> problem(HttpStatus.UNPROCESSABLE_CONTENT, "idempotency-key-reused",
                "Idempotency key reused", exception.getMessage());
            case IN_PROGRESS -> problem(HttpStatus.CONFLICT, "idempotency-in-progress",
                "Order request in progress", exception.getMessage());
            case STORE_UNAVAILABLE -> problem(HttpStatus.SERVICE_UNAVAILABLE, "idempotency-unavailable",
                "Idempotency unavailable", exception.getMessage());
        };
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
