package com.orderflow.order.orders.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import com.orderflow.order.customer.Customer;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "orders", schema = "orders")
public class Order {

    // Order amounts are stored as numeric(12,2).
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("9999999999.99");

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "total_net", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalNet;

    @Column(name = "total_vat", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalVat;

    @Column(name = "total_gross", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalGross;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // Null until the first persist: with an assigned UUID, a null version is how Spring Data recognises a new
    // order and calls persist instead of merge.
    @Version
    @Column(nullable = false)
    private Long version;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<OrderLine> lines = new ArrayList<>();

    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;

    @Column(name = "idempotency_key", length = 128)
    private String idempotencyKey;

    @Column(name = "request_hash", length = 64)
    private String requestHash;

    protected Order() {
    }

    private Order(Customer customer, BigDecimal totalVat, Instant now) {
        this.id = UUID.randomUUID();
        this.customer = customer;
        this.status = OrderStatus.PENDING;
        this.currency = "EUR";
        this.totalNet = new BigDecimal("0.00");
        this.totalVat = totalVat;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static Order place(Customer customer, List<PricedLine> pricedLines, BigDecimal totalVat, Instant now) {
        if (customer == null || now == null || pricedLines == null || pricedLines.isEmpty()) {
            throw new InvalidOrderException("Customer, time and at least one line are required");
        }
        if (totalVat == null || totalVat.signum() < 0 || totalVat.scale() > 2) {
            throw new InvalidOrderException("totalVat must be a non-negative amount with at most two decimals");
        }
        Order order = new Order(customer, totalVat.setScale(2, RoundingMode.UNNECESSARY), now);
        for (PricedLine line : pricedLines) {
            order.addLine(line);
        }
        order.totalGross = order.totalNet.add(order.totalVat);
        if (order.totalNet.compareTo(MAX_AMOUNT) > 0 || order.totalVat.compareTo(MAX_AMOUNT) > 0
            || order.totalGross.compareTo(MAX_AMOUNT) > 0) {
            throw new InvalidOrderException("Order total exceeds the supported amount");
        }
        return order;
    }

    private void addLine(PricedLine line) {
        if (line == null || line.sku() == null || line.sku().isBlank() || line.sku().length() > 32
            || line.quantity() <= 0 || line.unitPriceNet() == null
            || line.unitPriceNet().signum() < 0 || line.unitPriceNet().scale() > 2) {
            throw new InvalidOrderException("Each line needs a SKU, positive quantity and valid net unit price");
        }
        OrderLine orderLine = new OrderLine(this, line.sku(), line.quantity(),
            line.unitPriceNet().setScale(2, RoundingMode.UNNECESSARY));
        if (orderLine.getLineTotalNet().compareTo(MAX_AMOUNT) > 0) {
            throw new InvalidOrderException("Order line total exceeds the supported amount");
        }
        lines.add(orderLine);
        totalNet = totalNet.add(orderLine.getLineTotalNet());
    }

    public void confirm(Instant now) {
        if (status != OrderStatus.PENDING) {
            throw new InvalidOrderStateException("Cannot confirm order in " + status + " state");
        }
        requireTime(now);
        updatedAt = now;
        status = OrderStatus.CONFIRMED;
    }

    public void reject(String reason, Instant now) {
        if (status != OrderStatus.PENDING) {
            throw new InvalidOrderStateException("Cannot reject order in " + status + " state");
        }
        requireTime(now);
        if (reason == null || reason.isBlank() || reason.trim().length() > 500) {
            throw new InvalidOrderException("Rejection reason must contain 1 to 500 characters");
        }
        updatedAt = now;
        rejectionReason = reason.trim();
        status = OrderStatus.REJECTED;
    }

    public void cancel(Instant now) {
        if (status != OrderStatus.PENDING && status != OrderStatus.CONFIRMED) {
            throw new InvalidOrderStateException("Cannot cancel order in " + status + " state");
        }
        requireTime(now);
        updatedAt = now;
        status = OrderStatus.CANCELLED;
    }

    public void recordIdempotencyKey(String key, String hash) {
        if (idempotencyKey != null) {
            throw new InvalidOrderStateException("Idempotency key is already recorded");
        }
        if (key == null || key.isBlank() || key.length() > 128 || hash == null || hash.length() != 64) {
            throw new InvalidOrderException("Idempotency key needs 1 to 128 characters and a 64-character hash");
        }
        idempotencyKey = key;
        requestHash = hash;
    }

    private static void requireTime(Instant now) {
        if (now == null) {
            throw new InvalidOrderException("Transition time is required");
        }
    }

    public record PricedLine(String sku, int quantity, BigDecimal unitPriceNet) {
    }

    public UUID getId() {
        return id;
    }

    public Customer getCustomer() {
        return customer;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getTotalNet() {
        return totalNet;
    }

    public BigDecimal getTotalVat() {
        return totalVat;
    }

    public BigDecimal getTotalGross() {
        return totalGross;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Long getVersion() {
        return version;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public List<OrderLine> getLines() {
        return Collections.unmodifiableList(lines);
    }
}
