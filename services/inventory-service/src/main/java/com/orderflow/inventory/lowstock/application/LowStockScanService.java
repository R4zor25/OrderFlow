package com.orderflow.inventory.lowstock.application;

import com.orderflow.inventory.infrastructure.outbox.InventoryOutboxWriter;
import com.orderflow.inventory.lowstock.infrastructure.LowStockRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LowStockScanService {

    private final LowStockRepository repository;
    private final InventoryOutboxWriter outbox;

    public LowStockScanService(LowStockRepository repository, InventoryOutboxWriter outbox) {
        this.repository = repository;
        this.outbox = outbox;
    }

    @Transactional
    public int scan() {
        repository.resetRecovered();
        var candidates = repository.claimLowStock();
        for (var candidate : candidates) {
            outbox.lowStock(candidate.sku(), candidate.available(), candidate.threshold());
        }
        return candidates.size();
    }
}
