package com.orderflow.catalog_pricing_service.price.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "price_list_entry", schema = "catalog")
public class PriceListEntry {

    @Id
    private UUID id;

    @Column(name = "customer_group_code", nullable = false, length = 32)
    private String customerGroupCode;

    @Column(nullable = false, length = 32)
    private String sku;

    @Column(name = "min_qty", nullable = false)
    private int minQty;

    @Column(name = "unit_price_net", nullable = false, precision = 12, scale = 2)
    private BigDecimal unitPriceNet;

    @Column(name = "valid_from", nullable = false)
    private LocalDate validFrom;

    @Column(name = "valid_to")
    private LocalDate validTo;

    protected PriceListEntry() {
    }

    private PriceListEntry(String customerGroupCode, String sku, int minQty, BigDecimal unitPriceNet,
                           LocalDate validFrom, LocalDate validTo) {
        this.id = UUID.randomUUID();
        this.customerGroupCode = customerGroupCode;
        this.sku = sku;
        this.minQty = minQty;
        this.unitPriceNet = unitPriceNet;
        this.validFrom = validFrom;
        this.validTo = validTo;
    }

    public static PriceListEntry create(String group, String sku, int minQty, BigDecimal unitPriceNet,
                                        LocalDate validFrom, LocalDate validTo) {
        return new PriceListEntry(group, sku, minQty, unitPriceNet, validFrom, validTo);
    }

    public String getCustomerGroupCode() {
        return customerGroupCode;
    }

    public String getSku() {
        return sku;
    }

    public int getMinQty() {
        return minQty;
    }

    public BigDecimal getUnitPriceNet() {
        return unitPriceNet;
    }

    public LocalDate getValidFrom() {
        return validFrom;
    }

    public LocalDate getValidTo() {
        return validTo;
    }
}
