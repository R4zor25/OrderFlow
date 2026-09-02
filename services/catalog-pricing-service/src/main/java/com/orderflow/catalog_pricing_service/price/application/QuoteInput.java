package com.orderflow.catalog_pricing_service.price.application;

import java.util.List;

public record QuoteInput(String customerGroup, List<Line> lines) {

    public record Line(String sku, int quantity) {
    }
}
