package com.orderflow.inventory.platform.web;

import java.net.URI;
import java.util.Map;
import java.util.TreeMap;

import com.orderflow.inventory.stock.StockBelowReservedException;
import com.orderflow.inventory.stock.StockNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessResourceFailureException;
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

    @ExceptionHandler(StockNotFoundException.class)
    ProblemDetail stockNotFound(StockNotFoundException exception) {
        return problem(HttpStatus.NOT_FOUND, "stock-not-found", "Stock not found", exception.getMessage());
    }

    @ExceptionHandler(StockBelowReservedException.class)
    ProblemDetail stockBelowReserved(StockBelowReservedException exception) {
        return problem(HttpStatus.CONFLICT, "stock-below-reserved", "Stock below reserved", exception.getMessage());
    }

    @ExceptionHandler({CannotCreateTransactionException.class, DataAccessResourceFailureException.class,
        QueryTimeoutException.class})
    ResponseEntity<ProblemDetail> databaseUnavailable(Exception exception) {
        // The most specific cause tells pool exhaustion apart from authentication or DNS failures.
        log.warn("Database unavailable cause={} rootCause={}", exception.getClass().getSimpleName(),
            NestedExceptionUtils.getMostSpecificCause(exception).getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .header("Retry-After", "5")
            .body(problem(HttpStatus.SERVICE_UNAVAILABLE, "database-unavailable", "Database unavailable",
                "The database is temporarily unavailable"));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException exception,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        ProblemDetail problem = problem(HttpStatus.UNPROCESSABLE_CONTENT, "validation-failed",
            "Validation failed", "The request is invalid");
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
            int code = status.value();
            problem.setType(URI.create("/problems/" + (code == 400 ? "bad-request" : code == 404 ? "not-found" : "http-error")));
            if (code == 400 || code == 404) {
                problem.setTitle(code == 400 ? "Bad request" : "Not found");
            }
        }
        return super.createResponseEntity(body, headers, status, request);
    }

    private ProblemDetail problem(HttpStatus status, String kind, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("/problems/" + kind));
        problem.setTitle(title);
        return problem;
    }
}
