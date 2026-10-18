package com.orderflow.catalog_pricing_service.price.api;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.orderflow.catalog_pricing_service.price.application.PriceUpdateService.TierInput;
import com.orderflow.catalog_pricing_service.price.application.PriceUpdateService;

@RestController
@RequestMapping("/api/v1/products/{sku}/prices")
public class PriceController {

    private final PriceUpdateService priceUpdateService;

    public PriceController(PriceUpdateService priceUpdateService) {
        this.priceUpdateService = priceUpdateService;
    }

    @PutMapping
    public ResponseEntity<Void> replace(@PathVariable String sku,
                                         @Valid @RequestBody ReplacePricesRequest request) {
        priceUpdateService.replace(sku, request.customerGroup(), request.tiers().stream()
            .map(tier -> new TierInput(tier.minQty(), tier.unitPriceNet(), tier.validFrom(), tier.validTo()))
            .toList());
        return ResponseEntity.noContent().build();
    }
}
