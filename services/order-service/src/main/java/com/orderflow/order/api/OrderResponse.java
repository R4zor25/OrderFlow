package com.orderflow.order.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.orderflow.order.application.OrderDetails;
import com.orderflow.order.domain.OrderStatus;

public record OrderResponse(
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
    public static OrderResponse from(OrderDetails details) {
        return new OrderResponse(details.id(), details.customerId(), details.status(),
            details.currency(), details.totalNet(), details.totalVat(), details.totalGross(),
            details.createdAt(), details.updatedAt(), details.rejectionReason(),
            details.lines().stream().map(Line::from).toList());
    }

    public record Line(UUID id, String sku, int quantity, BigDecimal unitPriceNet,
                       BigDecimal lineTotalNet) {
        static Line from(OrderDetails.Line line) {
            return new Line(line.id(), line.sku(), line.quantity(),
                line.unitPriceNet(), line.lineTotalNet());
        }
    }
}
