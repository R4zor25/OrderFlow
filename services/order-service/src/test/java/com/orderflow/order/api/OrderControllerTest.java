package com.orderflow.order.api;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.orderflow.order.application.PlaceOrderResult;
import com.orderflow.order.application.IdempotentPlaceOrderService;
import com.orderflow.order.config.PricingResilienceProperties;
import com.orderflow.order.domain.OrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OrderControllerTest {

    private final IdempotentPlaceOrderService service = mock(IdempotentPlaceOrderService.class);
    private static final String CUSTOMER = "33333333-3333-4333-8333-333333333333";

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        var validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        var properties = new PricingResilienceProperties(
            2, Duration.ofMillis(100), 2, 0.2, 10, 10, 50, Duration.ofSeconds(10), 2);
        mvc = MockMvcBuilders.standaloneSetup(new OrderController(service, new OrderAccessResolver()))
            .setControllerAdvice(new ApiExceptionHandler(properties))
            .setValidator(validator)
            .build();
    }

    @Test
    void returnsAcceptedPendingOrderAndLocation() throws Exception {
        UUID orderId = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
        when(service.place(any(), any())).thenReturn(new PlaceOrderResult(orderId, OrderStatus.PENDING, "EUR",
            new BigDecimal("169.80"), new BigDecimal("45.85"), new BigDecimal("215.65")));

        mvc.perform(post("/api/v1/orders")
                .principal(customer(CUSTOMER))
                .header("Idempotency-Key", "request-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"lines":[{"sku":"CHAIR-001","quantity":2}]}
                    """))
            .andExpect(status().isAccepted())
            .andExpect(header().string("Location", "/api/v1/orders/" + orderId))
            .andExpect(jsonPath("$.id").value(orderId.toString()))
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.totalGross").value(215.65));
    }

    @Test
    void rejectsInvalidLineBeforeCallingApplicationService() throws Exception {
        mvc.perform(post("/api/v1/orders")
                .principal(customer(CUSTOMER))
                .header("Idempotency-Key", "request-2")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"lines":[{"sku":"bad sku","quantity":0}]}
                    """))
            .andExpect(status().isUnprocessableContent())
            .andExpect(jsonPath("$.type").value("/problems/invalid-order"));
        verifyNoInteractions(service);
    }

    @Test
    void requiresIdempotencyKeyHeader() throws Exception {
        mvc.perform(post("/api/v1/orders")
                .principal(customer(CUSTOMER))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"lines":[{"sku":"CHAIR-001","quantity":2}]}
                    """))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    private static JwtAuthenticationToken customer(String customerId) {
        var jwt = Jwt.withTokenValue("token").header("alg", "none").subject("user")
            .claim("customer_id", customerId)
            .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")));
    }
}
