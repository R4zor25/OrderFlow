package com.orderflow.inventory.stock;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/stock")
public class StockController {

    private final StockService stock;

    public StockController(StockService stock) {
        this.stock = stock;
    }

    @GetMapping("/{sku}")
    public StockResponse get(@PathVariable String sku) {
        return StockResponse.from(stock.get(sku));
    }

    @PutMapping("/{sku}")
    public StockResponse setOnHand(@PathVariable String sku, @Valid @RequestBody SetStockRequest request) {
        return StockResponse.from(stock.setOnHand(sku, request.onHand()));
    }
}
