package com.orderflow.order.api;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.orderflow.order.application.CancelOrderService;
import com.orderflow.order.application.OrderAccess;
import com.orderflow.order.application.OrderDetails;
import com.orderflow.order.config.PricingResilienceProperties;
import com.orderflow.order.domain.InvalidOrderStateException;
import com.orderflow.order.domain.OrderNotFoundException;
import com.orderflow.order.domain.OrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OrderCancellationControllerTest {

    private final CancelOrderService service = mock(CancelOrderService.class);
    private static final UUID CUSTOMER = UUID.fromString("33333333-3333-4333-8333-333333333333");

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        var pricing = new PricingResilienceProperties(
            2, Duration.ofMillis(100), 2, 0.2, 10, 10, 50, Duration.ofSeconds(10), 2);
        mvc = MockMvcBuilders.standaloneSetup(new OrderCancellationController(service, new OrderAccessResolver()))
            .setControllerAdvice(new ApiExceptionHandler(pricing))
            .build();
    }

    @Test
    void returnsCancelledOrder() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.cancel(id, OrderAccess.customer(CUSTOMER))).thenReturn(cancelled(id));

        mvc.perform(post("/api/v1/orders/{id}/cancel", id)
                .principal(customer(CUSTOMER.toString())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(id.toString()))
            .andExpect(jsonPath("$.status").value("CANCELLED"))
            .andExpect(jsonPath("$.lines[0].sku").value("CHAIR-001"));
    }

    @Test
    void reportsMissingOrder() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.cancel(id, OrderAccess.customer(CUSTOMER))).thenThrow(new OrderNotFoundException("Order not found: " + id));

        mvc.perform(post("/api/v1/orders/{id}/cancel", id)
                .principal(customer(CUSTOMER.toString())))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.type").value("/problems/order-not-found"));
    }

    @Test
    void reportsInvalidTransitionAsConflict() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.cancel(id, OrderAccess.customer(CUSTOMER))).thenThrow(new InvalidOrderStateException("Cannot cancel order"));

        mvc.perform(post("/api/v1/orders/{id}/cancel", id)
                .principal(customer(CUSTOMER.toString())))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.type").value("/problems/invalid-order-state"));
    }

    @Test
    void reportsOptimisticLockConflict() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.cancel(id, OrderAccess.customer(CUSTOMER))).thenThrow(new ObjectOptimisticLockingFailureException("Order", id));

        mvc.perform(post("/api/v1/orders/{id}/cancel", id)
                .principal(customer(CUSTOMER.toString())))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.type").value("/problems/order-modified-concurrently"));
    }

    private static OrderDetails cancelled(UUID id) {
        Instant now = Instant.parse("2026-10-01T10:00:00Z");
        return new OrderDetails(id, UUID.randomUUID(), OrderStatus.CANCELLED,
            "EUR", new BigDecimal("84.90"), new BigDecimal("22.92"),
            new BigDecimal("107.82"), now, now, null,
            List.of(new OrderDetails.Line(UUID.randomUUID(), "CHAIR-001", 1,
                new BigDecimal("84.90"), new BigDecimal("84.90"))));
    }

    private static JwtAuthenticationToken customer(String customerId) {
        var jwt = Jwt.withTokenValue("token").header("alg", "none").subject("user")
            .claim("customer_id", customerId)
            .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")));
    }
}
