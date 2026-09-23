package com.orderflow.inventory.reservation.api;

import java.util.List;
import java.util.UUID;

import com.orderflow.inventory.reservation.infrastructure.ReservationRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ReservationController {

    private final ReservationRepository reservations;

    public ReservationController(ReservationRepository reservations) {
        this.reservations = reservations;
    }

    @GetMapping("/api/v1/reservations")
    public List<ReservationResponse> byOrder(@RequestParam UUID orderId) {
        return reservations.findByOrderId(orderId).stream().map(ReservationResponse::from).toList();
    }
}
