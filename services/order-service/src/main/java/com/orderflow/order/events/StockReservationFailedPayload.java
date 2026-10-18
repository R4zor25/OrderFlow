package com.orderflow.order.events;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record StockReservationFailedPayload(UUID orderId, String reason,
                                            List<String> unavailableSkus) {
}
