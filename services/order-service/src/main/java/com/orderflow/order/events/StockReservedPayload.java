package com.orderflow.order.events;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record StockReservedPayload(UUID orderId, List<Line> lines) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Line(String sku, int quantity) {
    }
}
