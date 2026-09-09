package com.orderflow.inventory.infrastructure.events;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderCancelledPayload(UUID orderId, UUID customerId, String customerEmail,
                                    String previousStatus) {
}
