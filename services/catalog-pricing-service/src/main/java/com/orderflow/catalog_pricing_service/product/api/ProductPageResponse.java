package com.orderflow.catalog_pricing_service.product.api;

import java.util.List;

import org.springframework.data.domain.Page;

import com.orderflow.catalog_pricing_service.product.domain.Product;

public record ProductPageResponse(
    List<ProductResponse> items,
    int page,
    int size,
    long totalElements,
    int totalPages
) {
    public static ProductPageResponse from(Page<Product> products) {
        return new ProductPageResponse(
            products.getContent().stream().map(ProductResponse::from).toList(),
            products.getNumber(), products.getSize(), products.getTotalElements(), products.getTotalPages()
        );
    }
}
