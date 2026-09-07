package com.orderflow.inventory.reservation.domain;

public record ReservationLine(String sku, int quantity) {

    public ReservationLine {
        if (sku == null || sku.isBlank() || sku.length() > 32) {
            throw new IllegalArgumentException("SKU must have between 1 and 32 characters");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("Reservation quantity must be positive");
        }
    }
}
