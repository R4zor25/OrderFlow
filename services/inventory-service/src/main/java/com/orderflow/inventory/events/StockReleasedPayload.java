package com.orderflow.inventory.events;

import java.util.UUID;

public record StockReleasedPayload(UUID orderId) {
}
