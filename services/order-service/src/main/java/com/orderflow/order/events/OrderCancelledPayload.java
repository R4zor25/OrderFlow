package com.orderflow.order.events;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.orderflow.order.orders.domain.OrderStatus;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderCancelledPayload(UUID orderId, UUID customerId, String customerEmail,
                                    OrderStatus previousStatus) {
}
