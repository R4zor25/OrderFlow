package com.orderflow.order;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.orderflow.order.infrastructure.pricing.PricingGateway;
import com.orderflow.order.infrastructure.pricing.QuoteLineResponse;
import com.orderflow.order.infrastructure.pricing.QuoteRequest;
import com.orderflow.order.infrastructure.pricing.QuoteResponse;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "order.outbox.relay.enabled=true")
@AutoConfigureMockMvc
@Import({PostgresTestConfiguration.class, RedisTestConfiguration.class, KafkaTestConfiguration.class})
class OutboxRelayIntegrationTest {

    private static final String TOPIC = "orders.order-events.v1";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private KafkaContainer kafka;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private PricingGateway pricing;

    @Test
    void publishesUnsentEventWithOrderIdKeyAndMarksItAfterAcknowledgement() throws Exception {
        when(pricing.quote(any(QuoteRequest.class))).thenReturn(new QuoteResponse(
            "B2B_STANDARD", "EUR", List.of(new QuoteLineResponse(
                "CHAIR-001", 2, new BigDecimal("84.90"), new BigDecimal("169.80"),
                new BigDecimal("45.85"), null, "BASE")),
            new BigDecimal("169.80"), new BigDecimal("45.85"), new BigDecimal("215.65"),
            Instant.parse("2026-10-01T10:15:30Z")));
        var response = mvc.perform(post("/api/v1/orders")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"customerId":"33333333-3333-4333-8333-333333333333",
                     "lines":[{"sku":"CHAIR-001","quantity":2}]}
                    """))
            .andExpect(status().isAccepted())
            .andReturn().getResponse();
        UUID orderId = UUID.fromString(json.readTree(response.getContentAsString()).get("id").asText());
        UUID eventId = jdbc.queryForObject("""
            select id from orders.outbox_event where aggregate_id = ? and event_type = 'OrderPlaced'
            """, UUID.class, orderId);
        String payload = jdbc.queryForObject("""
            select payload::text from orders.outbox_event where id = ?
            """, String.class, eventId);

        try (var consumer = new KafkaConsumer<String, String>(Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
            ConsumerConfig.GROUP_ID_CONFIG, "relay-test-" + UUID.randomUUID(),
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
            consumer.subscribe(List.of(TOPIC));
            org.apache.kafka.clients.consumer.ConsumerRecord<String, String> found = null;
            Instant deadline = Instant.now().plusSeconds(10);
            while (found == null && Instant.now().isBefore(deadline)) {
                for (var record : consumer.poll(Duration.ofMillis(500))) {
                    if (record.value().contains(eventId.toString())) {
                        found = record;
                        break;
                    }
                }
            }

            assertThat(found).isNotNull();
            assertThat(found.key()).isEqualTo(orderId.toString());
            assertThat(json.readTree(found.value())).isEqualTo(json.readTree(payload));
            assertThat(found.headers().lastHeader("eventType").value())
                .isEqualTo("OrderPlaced".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            long duplicateCount = 0;
            for (var record : consumer.poll(Duration.ofSeconds(1))) {
                if (record.value().contains(eventId.toString())) {
                    duplicateCount++;
                }
            }
            assertThat(duplicateCount).isZero();
        }
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
            assertThat(jdbc.queryForObject("""
                select published_at is not null from orders.outbox_event where id = ?
                """, Boolean.class, eventId)).isTrue());
    }

}
