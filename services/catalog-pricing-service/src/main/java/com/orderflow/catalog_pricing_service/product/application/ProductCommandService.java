package com.orderflow.catalog_pricing_service.product.application;

import java.math.BigDecimal;
import java.time.Clock;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.orderflow.catalog_pricing_service.product.domain.Product;
import com.orderflow.catalog_pricing_service.product.infrastructure.ProductRepository;

@Service
public class ProductCommandService {

    private final ProductRepository repository;
    private final Clock clock;

    public ProductCommandService(ProductRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public Product create(String sku, String name, String category, BigDecimal listPriceNet, BigDecimal vatRate) {
        var product = Product.create(sku, name, category, listPriceNet, vatRate, clock.instant());
        return repository.saveAndFlush(product);
    }
}
