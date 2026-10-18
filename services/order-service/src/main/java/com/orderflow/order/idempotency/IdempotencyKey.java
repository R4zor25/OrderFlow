package com.orderflow.order.idempotency;

public record IdempotencyKey(String key, String requestHash) {
}
