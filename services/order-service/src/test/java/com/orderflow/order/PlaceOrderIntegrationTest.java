package com.orderflow.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.orderflow.order.infrastructure.pricing.PricingGateway;
import com.orderflow.order.infrastructure.pricing.PricingUnavailableException;
import com.orderflow.order.infrastructure.pricing.QuoteLineResponse;
import com.orderflow.order.infrastructure.pricing.QuoteRequest;
import com.orderflow.order.infrastructure.pricing.QuoteResponse;
import com.orderflow.order.infrastructure.outbox.OutboxWriter;
import com.orderflow.order.infrastructure.events.EventEnvelope;
import com.orderflow.order.infrastructure.events.OrderPlacedPayload;
import com.orderflow.order.infrastructure.idempotency.RedisIdempotencyStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.core.type.TypeReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestConfiguration.class, RedisTestConfiguration.class})
class PlaceOrderIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private RedisIdempotencyStore idempotencyStore;

    @MockitoBean
    private PricingGateway pricing;

    @MockitoSpyBean
    private OutboxWriter outbox;

    @Test
    void quotesOutsideTransactionAndWritesOrderWithOutboxAtomically() throws Exception {
        when(pricing.quote(any(QuoteRequest.class))).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(((QuoteRequest) invocation.getArgument(0)).customerGroup()).isEqualTo("B2B_STANDARD");
            return validQuote();
        });

        var response = mvc.perform(post("/api/v1/orders")
                .header("Idempotency-Key", UUID.randomUUID().toString())
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
        EventEnvelope<OrderPlacedPayload> typedEvent = json.readValue(payload, new TypeReference<>() {});
        assertThat(event.get("eventType").asText()).isEqualTo("OrderPlaced");
        assertThat(event.get("aggregateId").asText()).isEqualTo(orderId.toString());
        assertThat(event.get("payload").get("customerType").asText()).isEqualTo("B2B");
        assertThat(event.get("payload").get("lines").get(0).get("sku").asText()).isEqualTo("CHAIR-001");
        assertThat(typedEvent.payload().orderId()).isEqualTo(orderId);
        assertThat(typedEvent.payload().lines().getFirst().quantity()).isEqualTo(2);
    }

    @Test
    void doesNotWriteOrderOrOutboxWhenPricingFails() throws Exception {
        long beforeOrders = jdbc.queryForObject("select count(*) from orders.orders", Long.class);
        long beforeEvents = jdbc.queryForObject("select count(*) from orders.outbox_event", Long.class);
        when(pricing.quote(any(QuoteRequest.class))).thenThrow(
            new PricingUnavailableException("Unavailable", new RuntimeException()));

        mvc.perform(post("/api/v1/orders")
                .header("Idempotency-Key", UUID.randomUUID().toString())
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
            .when(outbox).write(any());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> mvc.perform(post("/api/v1/orders")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content(requestBody())))
            .hasRootCauseInstanceOf(IllegalStateException.class);

        assertThat(jdbc.queryForObject("select count(*) from orders.orders", Long.class)).isEqualTo(beforeOrders);
        assertThat(jdbc.queryForObject("select count(*) from orders.outbox_event", Long.class)).isEqualTo(beforeEvents);
    }

    @Test
    void returnsStoredResponseForSameKeyAndRequestWithoutCreatingSecondOrder() throws Exception {
        long beforeOrders = jdbc.queryForObject("select count(*) from orders.orders", Long.class);
        long beforeEvents = jdbc.queryForObject("select count(*) from orders.outbox_event", Long.class);
        when(pricing.quote(any(QuoteRequest.class))).thenReturn(validQuote());
        String key = UUID.randomUUID().toString();

        MockHttpServletResponse first = postOrder(key, requestBody());
        MockHttpServletResponse replay = postOrder(key, requestBody());

        assertThat(first.getStatus()).isEqualTo(202);
        assertThat(replay.getStatus()).isEqualTo(202);
        assertThat(replay.getHeader("Location")).isEqualTo(first.getHeader("Location"));
        assertThat(replay.getContentAsString()).isEqualTo(first.getContentAsString());
        assertThat(jdbc.queryForObject("select count(*) from orders.orders", Long.class)).isEqualTo(beforeOrders + 1);
        assertThat(jdbc.queryForObject("select count(*) from orders.outbox_event", Long.class)).isEqualTo(beforeEvents + 1);
        verify(pricing, times(1)).quote(any(QuoteRequest.class));
        assertThat(redis.getExpire("idem:33333333-3333-4333-8333-333333333333:" + key))
            .isBetween(1L, 86400L);
    }

    @Test
    void rejectsSameKeyWithDifferentBody() throws Exception {
        when(pricing.quote(any(QuoteRequest.class))).thenReturn(validQuote());
        String key = UUID.randomUUID().toString();
        assertThat(postOrder(key, requestBody()).getStatus()).isEqualTo(202);

        MockHttpServletResponse changed = postOrder(key,
            requestBody().replace("\"quantity\":2", "\"quantity\":3"));

        assertThat(changed.getStatus()).isEqualTo(422);
        assertThat(json.readTree(changed.getContentAsString()).get("type").asText())
            .isEqualTo("/problems/idempotency-key-reused");
        verify(pricing, times(1)).quote(any(QuoteRequest.class));
    }

    @Test
    void releasesClaimAfterPricingFailureSoSameRequestCanRetry() throws Exception {
        when(pricing.quote(any(QuoteRequest.class)))
            .thenThrow(new PricingUnavailableException("Unavailable", new RuntimeException()))
            .thenReturn(validQuote());
        String key = UUID.randomUUID().toString();

        assertThat(postOrder(key, requestBody()).getStatus()).isEqualTo(503);
        assertThat(redis.opsForValue().get("idem:33333333-3333-4333-8333-333333333333:" + key)).isNull();
        assertThat(postOrder(key, requestBody()).getStatus()).isEqualTo(202);
        verify(pricing, times(2)).quote(any(QuoteRequest.class));
    }

    @Test
    void returnsConflictWhileSameKeyIsInProgress() throws Exception {
        var enteredPricing = new CountDownLatch(1);
        var finishPricing = new CountDownLatch(1);
        when(pricing.quote(any(QuoteRequest.class))).thenAnswer(invocation -> {
            enteredPricing.countDown();
            assertThat(finishPricing.await(5, TimeUnit.SECONDS)).isTrue();
            return validQuote();
        });
        String key = UUID.randomUUID().toString();
        var executor = Executors.newSingleThreadExecutor();
        try {
            var first = executor.submit(() -> postOrder(key, requestBody()));
            assertThat(enteredPricing.await(5, TimeUnit.SECONDS)).isTrue();

            MockHttpServletResponse concurrent = postOrder(key, requestBody());

            assertThat(concurrent.getStatus()).isEqualTo(409);
            assertThat(json.readTree(concurrent.getContentAsString()).get("type").asText())
                .isEqualTo("/problems/idempotency-in-progress");
            finishPricing.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS).getStatus()).isEqualTo(202);
            verify(pricing, times(1)).quote(any(QuoteRequest.class));
        } finally {
            finishPricing.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void scopesClientKeyToCustomerAndOnlyReleasesItsOwner() {
        String clientKey = UUID.randomUUID().toString();
        UUID firstCustomer = UUID.randomUUID();
        UUID secondCustomer = UUID.randomUUID();
        var first = (RedisIdempotencyStore.Acquired) idempotencyStore.claim(firstCustomer, clientKey, "hash");
        var second = (RedisIdempotencyStore.Acquired) idempotencyStore.claim(secondCustomer, clientKey, "hash");

        assertThat(first.redisKey()).isNotEqualTo(second.redisKey());
        idempotencyStore.release(first);
        assertThat(redis.opsForValue().get(second.redisKey())).isNotNull();
        idempotencyStore.release(second);
    }

    @Test
    void staleOwnerCannotDeleteAReplacementClaim() {
        UUID customerId = UUID.randomUUID();
        String clientKey = UUID.randomUUID().toString();
        var first = (RedisIdempotencyStore.Acquired) idempotencyStore.claim(customerId, clientKey, "hash");
        idempotencyStore.release(first);
        var replacement = (RedisIdempotencyStore.Acquired) idempotencyStore.claim(customerId, clientKey, "hash");

        idempotencyStore.release(first);

        assertThat(redis.opsForValue().get(replacement.redisKey())).isEqualTo(replacement.pendingJson());
        assertThatThrownBy(() -> idempotencyStore.claim(customerId, clientKey, "hash"))
            .isInstanceOf(com.orderflow.order.domain.IdempotencyException.class)
            .hasMessageContaining("still in progress");
        idempotencyStore.release(replacement);
    }

    private MockHttpServletResponse postOrder(String key, String body) throws Exception {
        return mvc.perform(post("/api/v1/orders")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andReturn().getResponse();
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
