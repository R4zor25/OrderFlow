package com.orderflow.order.infrastructure.events;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderRejectedPayload(UUID orderId, UUID customerId, String customerEmail,
                                   String reason) {
}
