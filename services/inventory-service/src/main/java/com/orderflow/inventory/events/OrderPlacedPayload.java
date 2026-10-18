package com.orderflow.inventory.events;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderPlacedPayload(UUID orderId, UUID customerId, String customerType, List<Line> lines) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Line(String sku, int quantity) {
    }
}
