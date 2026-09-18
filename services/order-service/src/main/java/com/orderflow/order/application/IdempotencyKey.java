package com.orderflow.order.application;

public record IdempotencyKey(String key, String requestHash) {
}
