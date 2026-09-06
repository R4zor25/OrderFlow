package com.orderflow.order.infrastructure.events;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.orderflow.order.domain.CustomerType;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderPlacedPayload(UUID orderId, UUID customerId, CustomerType customerType,
                                 List<Line> lines) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Line(String sku, int quantity) {
    }
}
