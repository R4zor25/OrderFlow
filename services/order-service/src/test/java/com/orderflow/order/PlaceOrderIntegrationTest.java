package com.orderflow.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.orderflow.order.infrastructure.pricing.PricingGateway;
import com.orderflow.order.infrastructure.pricing.PricingUnavailableException;
import com.orderflow.order.infrastructure.pricing.QuoteLineResponse;
import com.orderflow.order.infrastructure.pricing.QuoteRequest;
import com.orderflow.order.infrastructure.pricing.QuoteResponse;
import com.orderflow.order.infrastructure.outbox.OrderPlacedOutboxWriter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration.class)
class PlaceOrderIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper json;

    @MockitoBean
    private PricingGateway pricing;

    @MockitoSpyBean
    private OrderPlacedOutboxWriter outbox;

    @Test
    void quotesOutsideTransactionAndWritesOrderWithOutboxAtomically() throws Exception {
        when(pricing.quote(any(QuoteRequest.class))).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(((QuoteRequest) invocation.getArgument(0)).customerGroup()).isEqualTo("B2B_STANDARD");
            return validQuote();
        });

        var response = mvc.perform(post("/api/v1/orders")
                .contentType(MediaType.APPLICATION_JSON).content(requestBody()))
            .andExpect(status().isAccepted())
            .andReturn().getResponse();
        UUID orderId = UUID.fromString(json.readTree(response.getContentAsString()).get("id").asText());

        assertThat(response.getHeader("Location")).isEqualTo("/api/v1/orders/" + orderId);
        assertThat(jdbc.queryForObject(
            "select status from orders.orders where id = ?", String.class, orderId)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject(
            "select count(*) from orders.order_line where order_id = ?", Integer.class, orderId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "select count(*) from orders.outbox_event where aggregate_id = ? and event_type = 'OrderPlaced'",
            Integer.class, orderId)).isEqualTo(1);
        String payload = jdbc.queryForObject(
            "select payload::text from orders.outbox_event where aggregate_id = ?", String.class, orderId);
        var event = json.readTree(payload);
        assertThat(event.get("eventType").asText()).isEqualTo("OrderPlaced");
        assertThat(event.get("aggregateId").asText()).isEqualTo(orderId.toString());
        assertThat(event.get("payload").get("customerType").asText()).isEqualTo("B2B");
        assertThat(event.get("payload").get("lines").get(0).get("sku").asText()).isEqualTo("CHAIR-001");
    }

    @Test
    void doesNotWriteOrderOrOutboxWhenPricingFails() throws Exception {
        long beforeOrders = jdbc.queryForObject("select count(*) from orders.orders", Long.class);
        long beforeEvents = jdbc.queryForObject("select count(*) from orders.outbox_event", Long.class);
        when(pricing.quote(any(QuoteRequest.class))).thenThrow(
            new PricingUnavailableException("Unavailable", new RuntimeException()));

        mvc.perform(post("/api/v1/orders")
                .contentType(MediaType.APPLICATION_JSON).content(requestBody()))
            .andExpect(status().isServiceUnavailable());

        assertThat(jdbc.queryForObject("select count(*) from orders.orders", Long.class)).isEqualTo(beforeOrders);
        assertThat(jdbc.queryForObject("select count(*) from orders.outbox_event", Long.class)).isEqualTo(beforeEvents);
    }

    @Test
    void rollsBackOrderWhenOutboxWriteFails() throws Exception {
        long beforeOrders = jdbc.queryForObject("select count(*) from orders.orders", Long.class);
        long beforeEvents = jdbc.queryForObject("select count(*) from orders.outbox_event", Long.class);
        when(pricing.quote(any(QuoteRequest.class))).thenReturn(validQuote());
        doThrow(new IllegalStateException("Outbox unavailable"))
            .when(outbox).write(any(), any());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> mvc.perform(post("/api/v1/orders")
            .contentType(MediaType.APPLICATION_JSON).content(requestBody())))
            .hasRootCauseInstanceOf(IllegalStateException.class);

        assertThat(jdbc.queryForObject("select count(*) from orders.orders", Long.class)).isEqualTo(beforeOrders);
        assertThat(jdbc.queryForObject("select count(*) from orders.outbox_event", Long.class)).isEqualTo(beforeEvents);
    }

    private static String requestBody() {
        return """
            {"customerId":"33333333-3333-4333-8333-333333333333",
             "lines":[{"sku":"CHAIR-001","quantity":2}]}
            """;
    }

    private static QuoteResponse validQuote() {
        return new QuoteResponse("B2B_STANDARD", "EUR", List.of(
            new QuoteLineResponse("CHAIR-001", 2, new BigDecimal("84.90"),
                new BigDecimal("169.80"), new BigDecimal("45.85"), null, "BASE")),
            new BigDecimal("169.80"), new BigDecimal("45.85"), new BigDecimal("215.65"),
            Instant.parse("2026-10-01T10:15:30Z"));
    }
}
