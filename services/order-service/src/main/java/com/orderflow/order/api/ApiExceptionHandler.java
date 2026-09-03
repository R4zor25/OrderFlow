package com.orderflow.order.api;

import java.net.URI;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.orderflow.order.domain.InvalidOrderException;
import com.orderflow.order.domain.InvalidOrderStateException;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(InvalidOrderStateException.class)
    public ProblemDetail invalidOrderState(InvalidOrderStateException exception) {
        return problem(HttpStatus.CONFLICT, "invalid-order-state", "Invalid order state", exception.getMessage());
    }

    @ExceptionHandler(InvalidOrderException.class)
    public ProblemDetail invalidOrder(InvalidOrderException exception) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "invalid-order", "Invalid order", exception.getMessage());
    }

    private ProblemDetail problem(HttpStatus status, String kind, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("/problems/" + kind));
        problem.setTitle(title);
        return problem;
    }
}
