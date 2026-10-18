package com.orderflow.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.orderflow.order.pricing.PricingGateway;
import com.orderflow.order.pricing.quote.QuoteLineResponse;
import com.orderflow.order.pricing.quote.QuoteResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestConfiguration.class, RedisTestConfiguration.class, KeycloakTestConfiguration.class})
class OrderSecurityIntegrationTest {

    private static final String STANDARD = "33333333-3333-4333-8333-333333333333";
    private static final String ORDER_BODY = """
        {"lines":[{"sku":"CHAIR-001","quantity":2}]}
        """;

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private PricingGateway pricing;

    @BeforeEach
    void quote() {
        when(pricing.quote(any())).thenAnswer(invocation -> new QuoteResponse("B2B_STANDARD", "EUR",
            List.of(new QuoteLineResponse("CHAIR-001", 2, new BigDecimal("84.90"), new BigDecimal("169.80"),
                new BigDecimal("45.85"), null, "TIER_MIN_QTY_1")),
            new BigDecimal("169.80"), new BigDecimal("45.85"), new BigDecimal("215.65"), Instant.now()));
    }

    @Test
    void placesOrderForTheCustomerInTheToken() throws Exception {
        mvc.perform(placeAs(KeycloakTokens.password("standard")))
            .andExpect(status().isAccepted());
        String id = placedOrderId(KeycloakTokens.password("standard"));
        mvc.perform(get("/api/v1/orders/" + id).header("Authorization", bearer(KeycloakTokens.password("standard"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.customerId").value(STANDARD));
    }

    @Test
    void hidesOrdersOfOtherCustomersAndShowsThemToAdmin() throws Exception {
        String id = placedOrderId(KeycloakTokens.password("standard"));
        String gold = bearer(KeycloakTokens.password("gold"));
        mvc.perform(get("/api/v1/orders/" + id).header("Authorization", gold))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.type").value("/problems/order-not-found"));
        mvc.perform(post("/api/v1/orders/" + id + "/cancel").header("Authorization", gold))
            .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/orders/" + id).header("Authorization", bearer(KeycloakTokens.password("admin"))))
            .andExpect(status().isOk());
    }

    @Test
    void scopesCustomerListToOwnOrders() throws Exception {
        placedOrderId(KeycloakTokens.password("standard"));
        String standard = bearer(KeycloakTokens.password("standard"));
        mvc.perform(get("/api/v1/orders").header("Authorization", standard))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[*].customerId").value(org.hamcrest.Matchers.everyItem(
                org.hamcrest.Matchers.equalTo(STANDARD))));
        mvc.perform(get("/api/v1/orders").param("customerId", "44444444-4444-4444-8444-444444444444")
                .header("Authorization", standard))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.type").value("/problems/forbidden"));
    }

    @Test
    void adminCannotPlaceOrders() throws Exception {
        mvc.perform(placeAs(KeycloakTokens.password("admin")))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.type").value("/problems/forbidden"));
    }

    @Test
    void rejectsMissingTokenWithProblemDetail() throws Exception {
        mvc.perform(get("/api/v1/orders"))
            .andExpect(status().isUnauthorized())
            .andExpect(header().string("WWW-Authenticate", org.hamcrest.Matchers.startsWith("Bearer")))
            .andExpect(jsonPath("$.type").value("/problems/unauthorized"));
    }

    @Test
    void rejectsTokenNotSignedByRealm() throws Exception {
        // A valid, unexpired token signed with a foreign key: only the signature check can reject it.
        mvc.perform(get("/api/v1/orders").header("Authorization",
                bearer(TestJwts.customer(UUID.fromString(STANDARD)))))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.type").value("/problems/unauthorized"));
    }

    @Test
    void serviceTokenCannotReadOrders() throws Exception {
        mvc.perform(get("/api/v1/orders").header("Authorization", bearer(KeycloakTokens.clientCredentials())))
            .andExpect(status().isForbidden());
    }

    private org.springframework.test.web.servlet.RequestBuilder placeAs(String token) {
        return post("/api/v1/orders")
            .header("Authorization", bearer(token))
            .header("Idempotency-Key", "security-" + UUID.randomUUID())
            .contentType(MediaType.APPLICATION_JSON)
            .content(ORDER_BODY);
    }

    private String placedOrderId(String token) throws Exception {
        String body = mvc.perform(placeAs(token)).andExpect(status().isAccepted())
            .andReturn().getResponse().getContentAsString();
        return tools.jackson.databind.json.JsonMapper.builder().build().readTree(body).path("id").asString();
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
