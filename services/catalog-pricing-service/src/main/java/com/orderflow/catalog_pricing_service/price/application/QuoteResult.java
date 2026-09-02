package com.orderflow.catalog_pricing_service.price.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.orderflow.catalog_pricing_service.price.domain.PriceCalculation;

public record QuoteResult(String customerGroup, List<Line> lines, BigDecimal totalNet,
                          BigDecimal totalVat, BigDecimal totalGross, Instant quotedAt) {

    public record Line(String sku, int quantity, PriceCalculation calculation) {
    }
}
