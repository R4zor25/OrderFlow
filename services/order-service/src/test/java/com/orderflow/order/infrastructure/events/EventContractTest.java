package com.orderflow.order.infrastructure.events;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.orderflow.order.domain.CustomerType;
import com.orderflow.order.domain.OrderStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventContractTest {

    private final ObjectMapper json = JsonMapper.builder().findAndAddModules().build();

    @ParameterizedTest
    @MethodSource("samples")
    void samplesHaveValidEnvelopeAndAggregateKey(String file, String eventType) throws IOException {
        EventEnvelope<JsonNode> event = read(file, new TypeReference<>() {});

        assertThat(event.eventId()).isNotNull();
        assertThat(event.eventType()).isEqualTo(eventType);
        assertThat(event.eventVersion()).isEqualTo(1);
        String timestamp = json.readTree(sample(file)).get("occurredAt").asText();
        assertThat(timestamp).endsWith("Z");
        assertThat(event.occurredAt()).isEqualTo(Instant.parse(timestamp));
        if (eventType.equals("LowStock")) {
            assertThat(event.aggregateId()).isEqualTo(event.payload().get("sku").asText());
        } else {
            assertThat(event.aggregateId()).isEqualTo(event.payload().get("orderId").asText());
        }
    }

    static List<org.junit.jupiter.params.provider.Arguments> samples() {
        return List.of(
            org.junit.jupiter.params.provider.Arguments.of("order-placed.json", "OrderPlaced"),
            org.junit.jupiter.params.provider.Arguments.of("order-confirmed.json", "OrderConfirmed"),
            org.junit.jupiter.params.provider.Arguments.of("order-rejected.json", "OrderRejected"),
            org.junit.jupiter.params.provider.Arguments.of("order-cancelled.json", "OrderCancelled"),
            org.junit.jupiter.params.provider.Arguments.of("stock-reserved.json", "StockReserved"),
            org.junit.jupiter.params.provider.Arguments.of("stock-reservation-failed.json", "StockReservationFailed"),
            org.junit.jupiter.params.provider.Arguments.of("stock-released.json", "StockReleased"),
            org.junit.jupiter.params.provider.Arguments.of("low-stock.json", "LowStock")
        );
    }

    @Test
    void orderAndStockPayloadSamplesDeserializeToLocalRecords() throws IOException {
        EventEnvelope<OrderPlacedPayload> placed = read("order-placed.json", new TypeReference<>() {});
        EventEnvelope<OrderConfirmedPayload> confirmed = read("order-confirmed.json", new TypeReference<>() {});
        EventEnvelope<OrderRejectedPayload> rejected = read("order-rejected.json", new TypeReference<>() {});
        EventEnvelope<OrderCancelledPayload> cancelled = read("order-cancelled.json", new TypeReference<>() {});
        EventEnvelope<StockReservedPayload> reserved = read("stock-reserved.json", new TypeReference<>() {});
        EventEnvelope<StockReservationFailedPayload> failed =
            read("stock-reservation-failed.json", new TypeReference<>() {});

        assertThat(placed.payload().customerType()).isEqualTo(CustomerType.B2B);
        assertThat(placed.payload().lines().getFirst().quantity()).isEqualTo(2);
        assertThat(confirmed.payload().totalGross()).isEqualByComparingTo("215.65");
        assertThat(confirmed.payload().customerEmail()).isEqualTo("standard@example.test");
        assertThat(rejected.payload().reason()).isEqualTo("Insufficient stock");
        assertThat(cancelled.payload().previousStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(reserved.payload().lines().getFirst().sku()).isEqualTo("CHAIR-001");
        assertThat(failed.payload().unavailableSkus()).containsExactly("CHAIR-001");
    }

    @Test
    void unknownOptionalFieldsDoNotBreakV1Consumer() throws IOException {
        String newerMessage = sample("order-placed.json")
            .replace("\"payload\": {", "\"futureEnvelopeField\": true, \"payload\": {\"futurePayloadField\": 7,")
            .replace("\"quantity\": 2", "\"quantity\": 2, \"futureLineField\": \"ignored\"");

        EventEnvelope<OrderPlacedPayload> event = json.readValue(newerMessage, new TypeReference<>() {});

        assertThat(event.payload().orderId().toString()).isEqualTo(event.aggregateId());
        assertThat(event.payload().lines()).hasSize(1);
    }

    @Test
    void envelopeRequiresIdentityVersionTimeAndPayload() {
        assertThatThrownBy(() -> new EventEnvelope<>(UUID.randomUUID(), "OrderPlaced", 0,
            Instant.now(), "order-1", new OrderPlacedPayload(UUID.randomUUID(), UUID.randomUUID(),
                CustomerType.B2B, List.of())))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private <T> T read(String file, TypeReference<T> type) throws IOException {
        return json.readValue(sample(file), type);
    }

    private static String sample(String file) throws IOException {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null && !Files.isDirectory(directory.resolve("contracts/events"))) {
            directory = directory.getParent();
        }
        if (directory == null) {
            throw new IllegalStateException("Cannot find contracts/events from test working directory");
        }
        return Files.readString(directory.resolve("contracts/events").resolve(file));
    }
}
