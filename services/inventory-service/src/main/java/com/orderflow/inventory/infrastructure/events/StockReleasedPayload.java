package com.orderflow.inventory.infrastructure.events;

import java.util.UUID;

public record StockReleasedPayload(UUID orderId) {
}
