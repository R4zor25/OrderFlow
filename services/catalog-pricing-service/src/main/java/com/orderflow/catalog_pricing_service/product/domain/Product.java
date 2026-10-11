package com.orderflow.catalog_pricing_service.product.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "product", schema = "catalog")
public class Product {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, length = 32)
    private String sku;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, length = 64)
    private String category;

    @Column(name = "list_price_net", nullable = false, precision = 12, scale = 2)
    private BigDecimal listPriceNet;

    @Column(name = "vat_rate", nullable = false, precision = 4, scale = 2)
    private BigDecimal vatRate;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "price_version", nullable = false)
    private long priceVersion;

    protected Product() {
    }

    private Product(UUID id, String sku, String name, String category,
                    BigDecimal listPriceNet, BigDecimal vatRate, Instant createdAt) {
        this.id = id;
        this.sku = sku;
        this.name = name;
        this.category = category;
        this.listPriceNet = listPriceNet;
        this.vatRate = vatRate;
        this.active = true;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    public static Product create(String sku, String name, String category,
                                 BigDecimal listPriceNet, BigDecimal vatRate, Instant createdAt) {
        if (vatRate == null || vatRate.signum() < 0 || vatRate.compareTo(BigDecimal.ONE) > 0) {
            throw new InvalidProductException("vatRate must be between 0 and 1");
        }
        if (listPriceNet == null || listPriceNet.signum() <= 0) {
            throw new InvalidProductException("listPriceNet must be positive");
        }
        return new Product(UUID.randomUUID(), sku, name, category, listPriceNet, vatRate, createdAt);
    }

    public UUID getId() {
        return id;
    }

    public String getSku() {
        return sku;
    }

    public String getName() {
        return name;
    }

    public String getCategory() {
        return category;
    }

    public BigDecimal getListPriceNet() {
        return listPriceNet;
    }

    public BigDecimal getVatRate() {
        return vatRate;
    }

    public boolean isActive() {
        return active;
    }

    // Called under the product row lock, so versions grow in commit order and the cache can reject older lists.
    public long nextPriceVersion() {
        priceVersion++;
        return priceVersion;
    }
}
