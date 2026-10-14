package com.orderflow.inventory.reservation.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

import com.orderflow.inventory.reservation.domain.ReservationLine;
import com.orderflow.inventory.reservation.domain.StockUnavailableException;
import com.orderflow.inventory.reservation.infrastructure.ReservationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReservationService {

    private final ReservationRepository repository;

    public ReservationService(ReservationRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public List<ReservationLine> reserve(UUID orderId, List<ReservationLine> lines) {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(lines, "lines");
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("Order must contain at least one reservation line");
        }

        var quantityBySku = new TreeMap<String, Integer>();
        for (ReservationLine line : lines) {
            Objects.requireNonNull(line, "reservation line");
            quantityBySku.merge(line.sku(), line.quantity(), Math::addExact);
        }

        var reserved = new ArrayList<ReservationLine>();
        for (var entry : quantityBySku.entrySet()) {
            String sku = entry.getKey();
            int quantity = entry.getValue();
            if (!repository.reserveIfAvailable(sku, quantity)) {
                throw new StockUnavailableException(sku);
            }
            repository.insertReservation(orderId, sku, quantity);
            reserved.add(new ReservationLine(sku, quantity));
        }
        return List.copyOf(reserved);
    }
}
