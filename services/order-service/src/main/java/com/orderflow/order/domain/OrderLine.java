package com.orderflow.order.domain;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "order_line", schema = "orders")
public class OrderLine {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Column(nullable = false, length = 32)
    private String sku;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "unit_price_net", nullable = false, precision = 12, scale = 2)
    private BigDecimal unitPriceNet;

    @Column(name = "line_total_net", nullable = false, precision = 12, scale = 2)
    private BigDecimal lineTotalNet;

    protected OrderLine() {
    }

    OrderLine(Order order, String sku, int quantity, BigDecimal unitPriceNet) {
        this.id = UUID.randomUUID();
        this.order = order;
        this.sku = sku;
        this.quantity = quantity;
        this.unitPriceNet = unitPriceNet;
        this.lineTotalNet = unitPriceNet.multiply(BigDecimal.valueOf(quantity));
    }

    public UUID getId() {
        return id;
    }

    public Order getOrder() {
        return order;
    }

    public String getSku() {
        return sku;
    }

    public int getQuantity() {
        return quantity;
    }

    public BigDecimal getUnitPriceNet() {
        return unitPriceNet;
    }

    public BigDecimal getLineTotalNet() {
        return lineTotalNet;
    }
}
