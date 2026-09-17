package com.orderflow.notification.messaging;

import io.micronaut.json.JsonMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationEventDecoderTest {

    private final NotificationEventDecoder decoder = new NotificationEventDecoder(JsonMapper.createDefault());

    @Test
    void decodesCanonicalOrderConfirmed() throws Exception {
        var event = decoder.decode("orders.order-events.v1", "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
                contract("order-confirmed")).orElseThrow();
        assertThat(event.payload()).isInstanceOf(NotificationEvent.OrderConfirmed.class);
        assertThat(((NotificationEvent.OrderConfirmed) event.payload()).totalGross().toPlainString())
                .isEqualTo("215.65");
    }

    @Test
    void decodesCanonicalOrderRejected() throws Exception {
        var event = decoder.decode("orders.order-events.v1", "ffffffff-ffff-4fff-8fff-ffffffffffff",
                contract("order-rejected")).orElseThrow();
        assertThat(event.payload()).isInstanceOf(NotificationEvent.OrderRejected.class);
    }

    @Test
    void decodesCanonicalOrderCancelled() throws Exception {
        var event = decoder.decode("orders.order-events.v1", "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
                contract("order-cancelled")).orElseThrow();
        assertThat(event.payload()).isInstanceOf(NotificationEvent.OrderCancelled.class);
    }

    @Test
    void decodesCanonicalLowStock() throws Exception {
        var event = decoder.decode("inventory.stock-events.v1", "CHAIR-001", contract("low-stock"))
                .orElseThrow();
        assertThat(event.payload()).isInstanceOf(NotificationEvent.LowStock.class);
        assertThat(((NotificationEvent.LowStock) event.payload()).available()).isEqualTo(3);
    }

    @Test
    void skipsUnknownEventType() throws Exception {
        var json = contract("order-confirmed").replace("OrderConfirmed", "OrderShipped");
        assertThat(decoder.decode("orders.order-events.v1", "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", json))
                .isEmpty();
    }

    @Test
    void rejectsWrongKeyAndUnsupportedVersion() throws Exception {
        var json = contract("low-stock");
        assertThatThrownBy(() -> decoder.decode("inventory.stock-events.v1", "OTHER", json))
                .isInstanceOf(InvalidNotificationEventException.class);
        assertThatThrownBy(() -> decoder.decode("inventory.stock-events.v1", "CHAIR-001",
                json.replace("\"eventVersion\": 1", "\"eventVersion\": 2")))
                .isInstanceOf(InvalidNotificationEventException.class);
    }

    @Test
    void rejectsMissingRequiredPayload() throws Exception {
        var json = contract("order-confirmed").replace("\"customerEmail\": \"standard@example.test\",", "");
        assertThatThrownBy(() -> decoder.decode("orders.order-events.v1",
                "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", json))
                .isInstanceOf(InvalidNotificationEventException.class);
    }

    @Test
    void acceptsAdditionalOptionalFieldsInVersionOne() throws Exception {
        var json = contract("low-stock")
                .replace("\"payload\": {", "\"futureOptional\": \"x\", \"payload\": {")
                .replace("\"threshold\": 5", "\"threshold\": 5, \"futurePayloadField\": \"y\"");
        assertThat(decoder.decode("inventory.stock-events.v1", "CHAIR-001", json))
                .isPresent();
    }

    @Test
    void rejectsMissingLowStockQuantity() throws Exception {
        var json = contract("low-stock").replace("\"available\": 3,", "");
        assertThatThrownBy(() -> decoder.decode("inventory.stock-events.v1", "CHAIR-001", json))
                .isInstanceOf(InvalidNotificationEventException.class);
    }

    @Test
    void rejectsRecipientLongerThan320Characters() throws Exception {
        var json = contract("order-confirmed")
                .replace("standard@example.test", "a".repeat(308) + "@example.test");
        assertThatThrownBy(() -> decoder.decode("orders.order-events.v1",
                "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", json))
                .isInstanceOf(InvalidNotificationEventException.class);
    }

    @Test
    void rejectsLowStockSkuLongerThan32Characters() throws Exception {
        var sku = "S".repeat(33);
        var json = contract("low-stock").replace("CHAIR-001", sku);
        assertThatThrownBy(() -> decoder.decode("inventory.stock-events.v1", sku, json))
                .isInstanceOf(InvalidNotificationEventException.class);
    }

    @Test
    void acceptsRecipientOfExactly320Characters() throws Exception {
        var json = contract("order-confirmed")
                .replace("standard@example.test", "a".repeat(307) + "@example.test");
        assertThat(decoder.decode("orders.order-events.v1", "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", json))
                .isPresent();
    }

    @Test
    void acceptsLowStockSkuOfExactly32Characters() throws Exception {
        var sku = "S".repeat(32);
        var json = contract("low-stock").replace("CHAIR-001", sku);
        assertThat(decoder.decode("inventory.stock-events.v1", sku, json)).isPresent();
    }

    private static String contract(String name) throws Exception {
        return Files.readString(Path.of("../../contracts/events/" + name + ".json"));
    }
}
