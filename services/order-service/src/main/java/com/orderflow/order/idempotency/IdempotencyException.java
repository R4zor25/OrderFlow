package com.orderflow.order.idempotency;

public class IdempotencyException extends RuntimeException {

    public enum Reason {
        KEY_REUSED, IN_PROGRESS, STORE_UNAVAILABLE
    }

    private final Reason reason;

    public IdempotencyException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public IdempotencyException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
