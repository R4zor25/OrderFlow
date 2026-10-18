package com.orderflow.order.events;

import java.math.BigDecimal;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderConfirmedPayload(UUID orderId, UUID customerId, String customerEmail,
                                    BigDecimal totalGross, String currency) {
}
