package com.orderflow.catalog_pricing_service.product.application;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.orderflow.catalog_pricing_service.product.domain.Product;
import com.orderflow.catalog_pricing_service.product.infrastructure.ProductRepository;

@Service
public class ProductQueryService {

    private final ProductRepository repository;

    public ProductQueryService(ProductRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public Page<Product> list(String category, Pageable pageable) {
        return category == null || category.isBlank()
            ? repository.findAll(pageable)
            : repository.findByCategory(category, pageable);
    }

    @Transactional(readOnly = true)
    public Optional<Product> findBySku(String sku) {
        return repository.findBySku(sku);
    }
}
