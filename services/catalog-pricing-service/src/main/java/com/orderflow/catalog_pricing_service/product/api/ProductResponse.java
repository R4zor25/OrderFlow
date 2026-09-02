package com.orderflow.catalog_pricing_service.product.api;

import java.math.BigDecimal;
import java.util.UUID;

import com.orderflow.catalog_pricing_service.product.domain.Product;

public record ProductResponse(
    UUID id,
    String sku,
    String name,
    String category,
    BigDecimal listPriceNet,
    BigDecimal vatRate,
    boolean active
) {
    public static ProductResponse from(Product product) {
        return new ProductResponse(
            product.getId(), product.getSku(), product.getName(), product.getCategory(),
            product.getListPriceNet(), product.getVatRate(), product.isActive()
        );
    }
}
