package com.orderflow.order.api;

import java.net.URI;

import org.junit.jupiter.api.Test;

import com.orderflow.order.domain.InvalidOrderException;
import com.orderflow.order.domain.InvalidOrderStateException;

import static org.assertj.core.api.Assertions.assertThat;

class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

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
}
