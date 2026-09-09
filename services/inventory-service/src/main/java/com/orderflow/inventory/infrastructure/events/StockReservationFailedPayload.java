package com.orderflow.inventory.infrastructure.events;

import java.util.List;
import java.util.UUID;

public record StockReservationFailedPayload(UUID orderId, String reason, List<String> unavailableSkus) {
}
