package com.orderflow.catalog_pricing_service.product.infrastructure;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.orderflow.catalog_pricing_service.product.domain.Product;

public interface ProductRepository extends JpaRepository<Product, UUID> {

    Optional<Product> findBySku(String sku);

    List<Product> findAllBySkuIn(Collection<String> skus);

    Page<Product> findByCategory(String category, Pageable pageable);
}
