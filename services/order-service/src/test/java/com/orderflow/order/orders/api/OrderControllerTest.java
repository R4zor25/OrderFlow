package com.orderflow.order.orders.api;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.orderflow.order.orders.application.IdempotentPlaceOrderService;
import com.orderflow.order.orders.application.OrderQueryService;
import com.orderflow.order.orders.application.PlaceOrderResult;
import com.orderflow.order.orders.domain.OrderStatus;
import com.orderflow.order.platform.web.ApiExceptionHandler;
import com.orderflow.order.pricing.config.PricingResilienceProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OrderControllerTest {

    private final IdempotentPlaceOrderService service = mock(IdempotentPlaceOrderService.class);
    private final OrderQueryService queries = mock(OrderQueryService.class);
    private static final String CUSTOMER = "33333333-3333-4333-8333-333333333333";

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        var validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        var properties = new PricingResilienceProperties(
            2, Duration.ofMillis(100), 2, 0.2, 10, 10, 50, Duration.ofSeconds(10), 2);
        mvc = MockMvcBuilders.standaloneSetup(new OrderController(service, new OrderAccessResolver()),
                new OrderReadController(queries, new OrderAccessResolver()))
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

    @Test
    void missingIdempotencyKeyIsProblemDetail() throws Exception {
        mvc.perform(post("/api/v1/orders")
                .principal(customer(CUSTOMER))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"lines":[{"sku":"CHAIR-001","quantity":2}]}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.type").value("/problems/bad-request"))
            .andExpect(jsonPath("$.title").value("Bad request"));
    }

    @Test
    void malformedJsonIsProblemDetail() throws Exception {
        mvc.perform(post("/api/v1/orders")
                .principal(customer(CUSTOMER))
                .header("Idempotency-Key", "request-3")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"lines\":["))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.type").value("/problems/bad-request"));
        verifyNoInteractions(service);
    }

    @Test
    void nonUuidOrderIdIsProblemDetail() throws Exception {
        mvc.perform(get("/api/v1/orders/not-a-uuid").principal(customer(CUSTOMER)))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.type").value("/problems/bad-request"));
    }

    @Test
    void unknownStatusFilterIsProblemDetail() throws Exception {
        mvc.perform(get("/api/v1/orders").param("status", "SHIPPED").principal(customer(CUSTOMER)))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.type").value("/problems/bad-request"));
    }

    @Test
    void unknownPathIsProblemDetail() throws Exception {
        mvc.perform(get("/api/v1/unknown").principal(customer(CUSTOMER)))
            .andExpect(status().isNotFound())
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.type").value("/problems/not-found"))
            .andExpect(jsonPath("$.title").value("Not found"));
    }

    @Test
    void otherFrameworkErrorKeepsItsTitleUnderTheGenericType() throws Exception {
        mvc.perform(put("/api/v1/orders").principal(customer(CUSTOMER)))
            .andExpect(status().isMethodNotAllowed())
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.type").value("/problems/http-error"))
            .andExpect(jsonPath("$.title").value("Method Not Allowed"));
    }

    @Test
    void reportsFieldViolationsForInvalidOrder() throws Exception {
        mvc.perform(post("/api/v1/orders")
                .principal(customer(CUSTOMER))
                .header("Idempotency-Key", "request-4")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"lines":[{"sku":"CHAIR-001","quantity":0}]}
                    """))
            .andExpect(status().isUnprocessableContent())
            .andExpect(jsonPath("$.type").value("/problems/invalid-order"))
            .andExpect(jsonPath("$.violations['lines[0].quantity']").exists());
    }

    @Test
    void unreachableDatabaseIsServiceUnavailable() throws Exception {
        when(service.place(any(), any())).thenThrow(new CannotCreateTransactionException("Could not open JPA EntityManager"));

        mvc.perform(post("/api/v1/orders")
                .principal(customer(CUSTOMER))
                .header("Idempotency-Key", "request-5")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"lines":[{"sku":"CHAIR-001","quantity":2}]}
                    """))
            .andExpect(status().isServiceUnavailable())
            .andExpect(header().string("Retry-After", "5"))
            .andExpect(jsonPath("$.type").value("/problems/database-unavailable"));
    }

    @Test
    void hidesDataIntegrityDetails() throws Exception {
        when(service.place(any(), any())).thenThrow(
            new DataIntegrityViolationException("duplicate key value violates constraint ck_secret_detail"));

        mvc.perform(post("/api/v1/orders")
                .principal(customer(CUSTOMER))
                .header("Idempotency-Key", "request-6")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"lines":[{"sku":"CHAIR-001","quantity":2}]}
                    """))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.type").value("/problems/data-integrity"))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("ck_secret_detail"))));
    }

    @Test
    void rejectsDuplicateSkus() throws Exception {
        mvc.perform(post("/api/v1/orders")
                .principal(customer(CUSTOMER))
                .header("Idempotency-Key", "request-7")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"lines":[{"sku":"CHAIR-001","quantity":1},{"sku":"CHAIR-001","quantity":2}]}
                    """))
            .andExpect(status().isUnprocessableContent())
            .andExpect(jsonPath("$.type").value("/problems/invalid-order"))
            .andExpect(jsonPath("$.violations.distinctSkus").value("SKUs must be unique"));
        verifyNoInteractions(service);
    }

    @Test
    void rejectsQuantityAboveLimit() throws Exception {
        mvc.perform(post("/api/v1/orders")
                .principal(customer(CUSTOMER))
                .header("Idempotency-Key", "request-8")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"lines":[{"sku":"CHAIR-001","quantity":10001}]}
                    """))
            .andExpect(status().isUnprocessableContent())
            .andExpect(jsonPath("$.violations['lines[0].quantity']").exists());
        verifyNoInteractions(service);
    }

    private static JwtAuthenticationToken customer(String customerId) {
        var jwt = Jwt.withTokenValue("token").header("alg", "none").subject("user")
            .claim("customer_id", customerId)
            .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")));
    }
}
