package com.orderflow.order.application;

import java.math.BigDecimal;
import java.util.UUID;

import com.orderflow.order.domain.Order;
import com.orderflow.order.domain.OrderStatus;

public record PlaceOrderResult(
    UUID id,
    OrderStatus status,
    String currency,
    BigDecimal totalNet,
    BigDecimal totalVat,
    BigDecimal totalGross
) {
    public static PlaceOrderResult from(Order order) {
        return new PlaceOrderResult(order.getId(), order.getStatus(), order.getCurrency(),
            order.getTotalNet(), order.getTotalVat(), order.getTotalGross());
    }
}
