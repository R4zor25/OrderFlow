package com.orderflow.inventory.infrastructure.events;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class OrderCancellationContractTest {

    @Test
    void deserializesCanonicalOrderCancelledEvent() throws Exception {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null && !Files.isDirectory(directory.resolve("contracts/events"))) {
            directory = directory.getParent();
        }
        assertThat(directory).isNotNull();
        String sample = Files.readString(directory.resolve("contracts/events/order-cancelled.json"));

        EventEnvelope<OrderCancelledPayload> event = JsonMapper.builder().findAndAddModules().build()
            .readValue(sample, new TypeReference<>() {});

        UUID orderId = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
        assertThat(event.eventType()).isEqualTo("OrderCancelled");
        assertThat(event.eventVersion()).isEqualTo(1);
        assertThat(event.aggregateId()).isEqualTo(orderId.toString());
        assertThat(event.payload().orderId()).isEqualTo(orderId);
        assertThat(event.payload().previousStatus()).isEqualTo("CONFIRMED");
    }
}
