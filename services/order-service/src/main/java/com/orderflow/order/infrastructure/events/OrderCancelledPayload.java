package com.orderflow.order.infrastructure.events;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.orderflow.order.domain.OrderStatus;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderCancelledPayload(UUID orderId, UUID customerId, String customerEmail,
                                    OrderStatus previousStatus) {
}
