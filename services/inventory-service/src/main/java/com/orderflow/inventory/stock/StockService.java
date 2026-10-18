package com.orderflow.inventory.stock;

import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StockService {

    private static final Logger log = LoggerFactory.getLogger(StockService.class);

    private final StockRepository stock;
    private final Clock clock;

    public StockService(StockRepository stock, Clock clock) {
        this.stock = stock;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public StockLevel get(String sku) {
        return stock.find(sku).orElseThrow(() -> new StockNotFoundException(sku));
    }

    @Transactional
    public StockLevel setOnHand(String sku, int onHand) {
        StockLevel updated = stock.setOnHand(sku, onHand, clock.instant()).orElseThrow(() -> {
            StockLevel current = stock.find(sku).orElseThrow(() -> new StockNotFoundException(sku));
            return new StockBelowReservedException(onHand, current.reserved());
        });
        log.info("Stock set sku={} onHand={}", sku, onHand);
        return updated;
    }
}
