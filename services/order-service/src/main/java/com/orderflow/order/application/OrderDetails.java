package com.orderflow.order.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.orderflow.order.domain.Order;
import com.orderflow.order.domain.OrderLine;
import com.orderflow.order.domain.OrderStatus;

public record OrderDetails(
    UUID id,
    UUID customerId,
    OrderStatus status,
    String currency,
    BigDecimal totalNet,
    BigDecimal totalVat,
    BigDecimal totalGross,
    Instant createdAt,
    Instant updatedAt,
    String rejectionReason,
    List<Line> lines
) {
    public static OrderDetails from(Order order) {
        return new OrderDetails(order.getId(), order.getCustomer().getId(), order.getStatus(),
            order.getCurrency(), order.getTotalNet(), order.getTotalVat(), order.getTotalGross(),
            order.getCreatedAt(), order.getUpdatedAt(), order.getRejectionReason(),
            order.getLines().stream().map(Line::from).toList());
    }

    public record Line(UUID id, String sku, int quantity, BigDecimal unitPriceNet,
                       BigDecimal lineTotalNet) {
        static Line from(OrderLine line) {
            return new Line(line.getId(), line.getSku(), line.getQuantity(),
                line.getUnitPriceNet(), line.getLineTotalNet());
        }
    }
}
