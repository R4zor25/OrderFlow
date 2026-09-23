package com.orderflow.inventory.reservation.api;

import java.time.Instant;

import com.orderflow.inventory.reservation.infrastructure.ReservationRepository.ReservationView;

public record ReservationResponse(String sku, int quantity, String status, Instant createdAt) {

    static ReservationResponse from(ReservationView view) {
        return new ReservationResponse(view.sku(), view.quantity(), view.status(), view.createdAt());
    }
}
