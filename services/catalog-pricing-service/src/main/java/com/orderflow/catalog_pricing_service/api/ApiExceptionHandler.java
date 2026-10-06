package com.orderflow.catalog_pricing_service.api;

import java.net.URI;
import java.sql.SQLException;
import java.util.Map;
import java.util.TreeMap;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.orderflow.catalog_pricing_service.product.domain.InvalidProductException;
import com.orderflow.catalog_pricing_service.price.application.UnknownCustomerGroupException;
import com.orderflow.catalog_pricing_service.price.application.UnknownSkusException;
import com.orderflow.catalog_pricing_service.price.application.InvalidPriceListException;

@RestControllerAdvice
@NullMarked
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @Override
    protected @Nullable ResponseEntity<Object> handleMethodArgumentNotValid(
        MethodArgumentNotValidException exception, HttpHeaders headers, HttpStatusCode status, WebRequest request
    ) {
        var problem = problem(HttpStatus.BAD_REQUEST, "validation", "Validation failed", "Request fields are invalid");
        Map<String, String> violations = new TreeMap<>();
        exception.getBindingResult().getFieldErrors().forEach(error -> {
            var message = error.getDefaultMessage();
            violations.putIfAbsent(error.getField(), message == null ? "Invalid value" : message);
        });
        problem.setProperty("violations", violations);
        return handleExceptionInternal(exception, problem, headers, status, request);
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleHttpMessageNotReadable(
        HttpMessageNotReadableException exception, HttpHeaders headers, HttpStatusCode status, WebRequest request
    ) {
        return handleExceptionInternal(exception,
            problem(HttpStatus.BAD_REQUEST, "validation", "Validation failed", "Request body is invalid"),
            headers, status, request);
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleTypeMismatch(
        TypeMismatchException exception, HttpHeaders headers, HttpStatusCode status, WebRequest request
    ) {
        return handleExceptionInternal(exception,
            problem(HttpStatus.BAD_REQUEST, "validation", "Validation failed", "Request parameter has an invalid type"),
            headers, status, request);
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleErrorResponseException(
        ErrorResponseException exception, HttpHeaders headers, HttpStatusCode status, WebRequest request
    ) {
        var kind = status.value() == 404 ? "not-found" : status.value() == 400 ? "validation" : "http-error";
        var title = status.value() == 404 ? "Not found" : status.value() == 400 ? "Validation failed" : "Request failed";
        return handleExceptionInternal(exception,
            problem(status, kind, title, exception.getBody().getDetail()), headers, status, request);
    }

    @ExceptionHandler(InvalidProductException.class)
    public ProblemDetail invalidProduct(InvalidProductException exception) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "invalid-product", "Invalid product", exception.getMessage());
    }

    @ExceptionHandler(UnknownSkusException.class)
    public ProblemDetail unknownSkus(UnknownSkusException exception) {
        var problem = problem(HttpStatus.UNPROCESSABLE_CONTENT, "unknown-skus", "Unknown SKUs", exception.getMessage());
        problem.setProperty("skus", exception.getSkus());
        return problem;
    }

    @ExceptionHandler(UnknownCustomerGroupException.class)
    public ProblemDetail unknownCustomerGroup(UnknownCustomerGroupException exception) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "unknown-customer-group", "Unknown customer group",
            exception.getMessage());
    }

    @ExceptionHandler(InvalidPriceListException.class)
    public ProblemDetail invalidPriceList(InvalidPriceListException exception) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "invalid-price-list", "Invalid price list", exception.getMessage());
    }

    @ExceptionHandler({CannotCreateTransactionException.class, DataAccessResourceFailureException.class,
        QueryTimeoutException.class})
    ResponseEntity<ProblemDetail> databaseUnavailable(Exception exception) {
        log.warn("Database unavailable cause={}", exception.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .header("Retry-After", "5")
            .body(problem(HttpStatus.SERVICE_UNAVAILABLE, "database-unavailable", "Database unavailable",
                "The database is temporarily unavailable"));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail dataConflict(DataIntegrityViolationException exception) {
        if (isUniqueViolation(exception)) {
            return problem(HttpStatus.CONFLICT, "sku-conflict", "SKU conflict", "A product with this SKU already exists");
        }
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "invalid-product", "Invalid product", "Product violates a database constraint");
    }

    private boolean isUniqueViolation(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && "23505".equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    private ProblemDetail problem(HttpStatusCode status, String kind, String title, @Nullable String detail) {
        var problem = ProblemDetail.forStatusAndDetail(status, detail == null ? title : detail);
        problem.setType(URI.create("/problems/" + kind));
        problem.setTitle(title);
        return problem;
    }
}
